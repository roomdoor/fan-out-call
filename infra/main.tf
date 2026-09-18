terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = var.tags
  }
}

# state에 평문으로 남으므로 .gitignore가 tfstate를 막고 있다.
# destroy 전까지는 같은 값이 유지된다(keepers 미사용).
resource "random_password" "db" {
  length  = 32
  special = false
}

# ---------------------------------------------------------------------------
# 측정 결과 회수 버킷
#
# 결과는 C 호스트 디스크에만 있고 destroy 하면 같이 사라진다. C 에서 여기로
# 올리고 로컬에서 내려받는다 — 일회용 인스턴스에 GitHub 권한을 두지 않으려고
# 한 단계를 거친다.
# ---------------------------------------------------------------------------

# 버킷 이름은 전역이라 계정 ID 를 붙여 충돌을 피한다.
data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "results" {
  bucket = "${var.name_prefix}-results-${data.aws_caller_identity.current.account_id}"

  # 내려받은 뒤에는 로컬과 저장소에 남으므로 버킷은 비워도 된다. false 면
  # 객체가 있을 때 destroy 가 실패해서 버킷만 남고 요금이 계속 나간다.
  # 내려받기 전에 destroy 하면 사라지므로 fetch-results.sh 를 먼저 돌릴 것.
  force_destroy = true

  tags = { Name = "${var.name_prefix}-results" }
}

resource "aws_s3_bucket_public_access_block" "results" {
  bucket                  = aws_s3_bucket.results.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# user-data에 넣지 않는다. IMDS로 인스턴스의 모든 프로세스가 읽을 수 있고,
# 게이트웨이는 --network host 라 컨테이너 안에서도 읽힌다.
resource "aws_ssm_parameter" "db_password" {
  name  = "/${var.name_prefix}/db-password"
  type  = "SecureString"
  value = random_password.db.result

  tags = { Name = "${var.name_prefix}-db-password" }
}

data "aws_availability_zones" "available" {
  state = "available"
}

# 세 인스턴스를 한 AZ에 둔다. AZ가 갈리면 지연과 요금이 붙어 측정이 오염된다.
locals {
  az = data.aws_availability_zones.available.names[0]
}

# Amazon Linux 2023, x86_64
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---------------------------------------------------------------------------
# 네트워크
#
# 퍼블릭 서브넷 + 인터넷 게이트웨이만 쓴다. NAT Gateway는 만들지 않는다 —
# 트래픽이 0이어도 시간당 요금이 계속 나가고, 여기서는 필요가 없다.
# ---------------------------------------------------------------------------

resource "aws_vpc" "this" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name_prefix }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name_prefix }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = local.az
  map_public_ip_on_launch = true

  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# ---------------------------------------------------------------------------
# 보안 그룹
#
# 3306은 어디에도 열지 않는다. MySQL은 A 호스트 안에서만 쓰이고
# 루프백에만 바인딩되므로 인바운드 규칙 자체가 필요 없다.
# ---------------------------------------------------------------------------

# 인라인 규칙과 standalone rule 리소스를 섞으면 다음 apply가 규칙을 지운다.
# 그래서 egress도 standalone으로 둔다.
resource "aws_security_group" "gateway" {
  name        = "${var.name_prefix}-gateway"
  description = "A host - gateway"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-gateway" }
}

resource "aws_security_group" "mock" {
  name        = "${var.name_prefix}-mock"
  description = "B host - mock fleet"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-mock" }
}

resource "aws_security_group" "k6" {
  name        = "${var.name_prefix}-k6"
  description = "C host - load generator"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-k6" }
}

# GHCR pull, SSM, dnf
resource "aws_vpc_security_group_egress_rule" "all" {
  for_each = {
    gateway = aws_security_group.gateway.id
    mock    = aws_security_group.mock.id
    k6      = aws_security_group.k6.id
  }

  security_group_id = each.value
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "GHCR pull, SSM, dnf"
}

# k6 -> 게이트웨이 (submit, polling, actuator)
resource "aws_vpc_security_group_ingress_rule" "gateway_from_k6" {
  security_group_id            = aws_security_group.gateway.id
  referenced_security_group_id = aws_security_group.k6.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "k6 submit/polling"
}

# 게이트웨이 -> mock 샤드
resource "aws_vpc_security_group_ingress_rule" "mock_from_gateway" {
  security_group_id            = aws_security_group.mock.id
  referenced_security_group_id = aws_security_group.gateway.id
  from_port                    = var.mock_base_port
  to_port                      = var.mock_base_port + var.mock_shard_count - 1
  ip_protocol                  = "tcp"
  description                  = "bank fan-out calls"
}

# k6 -> mock 헬스체크 (sweep 시작 전 샤드 상태 확인)
resource "aws_vpc_security_group_ingress_rule" "mock_health_from_k6" {
  security_group_id            = aws_security_group.mock.id
  referenced_security_group_id = aws_security_group.k6.id
  from_port                    = var.mock_base_port
  to_port                      = var.mock_base_port + var.mock_shard_count - 1
  ip_protocol                  = "tcp"
  description                  = "mock fleet health probe"
}

# SSM이 막힌 환경을 위한 탈출구. allowed_ssh_cidr가 비면 만들어지지 않는다.
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = var.allowed_ssh_cidr == "" ? {} : {
    gateway = aws_security_group.gateway.id
    mock    = aws_security_group.mock.id
    k6      = aws_security_group.k6.id
  }

  security_group_id = each.value
  cidr_ipv4         = var.allowed_ssh_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
  description       = "SSH fallback"
}

# ---------------------------------------------------------------------------
# IAM — SSM Session Manager
#
# SSH 키도, 22번 포트도 쓰지 않고 접속하기 위한 최소 권한이다.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.name_prefix}-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "instance" {
  name = "${var.name_prefix}-instance"
  role = aws_iam_role.instance.name
}

# k6 전용 역할. 명령을 보내는 쪽은 C 하나뿐인데 공용 역할에 붙이면
# mock 호스트와 게이트웨이 컨테이너에서도 측정 대상에 root 명령을 쏠 수 있다.
resource "aws_iam_role" "k6" {
  name               = "${var.name_prefix}-k6"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "k6_ssm" {
  role       = aws_iam_role.k6.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "k6" {
  name = "${var.name_prefix}-k6"
  role = aws_iam_role.k6.name
}

# bench.sh는 C(k6)에서 돌면서 A(게이트웨이)를 SSM으로 제어한다.
# 회차마다 게이트웨이를 재기동하고 로그 카운트를 받아오기 위한 권한이다.
# 대상은 이 스택의 인스턴스와 RunShellScript 문서로 한정한다.
data "aws_region" "current" {}

data "aws_iam_policy_document" "bench_control" {
  statement {
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.gateway.arn,
      "arn:aws:ssm:${data.aws_region.current.name}::document/AWS-RunShellScript",
    ]
  }

  # 명령 결과 조회는 리소스 단위 제한을 지원하지 않는다.
  statement {
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }

  # 측정 결과를 회수 버킷으로 올린다. 쓰기만 준다 — 내려받아 커밋하는 건
  # 로컬에서 하고, 이 호스트에는 GitHub 권한을 두지 않는다.
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.results.arn}/*"]
  }

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.results.arn]
  }
}

# 비밀번호 읽기는 별도 정책으로 둔다. bench_control은 게이트웨이 ARN을
# 참조하므로, 게이트웨이가 그 정책에 depends_on 하면 순환이 생긴다.
# 이 정책은 인스턴스를 참조하지 않으므로 순환 없이 먼저 만들 수 있다.
data "aws_iam_policy_document" "param_read" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
  }

  # SecureString 복호화. Resource에 alias ARN을 쓰면 안 된다 — IAM은 키
  # ARN으로 평가하므로 매칭되지 않고 부팅이 AccessDenied로 막힌다.
  # ViaService 조건으로 좁혀서 SSM 경유 복호화만 허용한다.
  statement {
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${data.aws_region.current.name}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "param_read" {
  name   = "${var.name_prefix}-param-read"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.param_read.json
}

resource "aws_iam_role_policy" "bench_control" {
  name   = "${var.name_prefix}-bench-control"
  role   = aws_iam_role.k6.id
  policy = data.aws_iam_policy_document.bench_control.json
}

# ---------------------------------------------------------------------------
# 인스턴스
#
# 기동 순서가 있다. mock(B)이 먼저 떠야 게이트웨이(A)가 호출할 대상이 생긴다.
# depends_on으로 생성 순서만 강제하고, 실제 준비 완료는 user-data가 헬스체크로 기다린다.
# ---------------------------------------------------------------------------

resource "aws_instance" "mock" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.mock_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.mock.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/templates/mock.sh.tftpl", {
    image       = var.mock_image
    shard_count = var.mock_shard_count
    base_port   = var.mock_base_port
    latency     = var.mock_latency
  })

  # 기본값이 false라 설정을 바꿔도 부팅 스크립트가 다시 돌지 않는다.
  user_data_replace_on_change = true

  # AL2023 SSM 파라미터는 새 이미지가 나올 때마다 바뀌고 ami는 교체를
  # 강제한다. 측정 중 apply 한 번에 세 호스트와 로그가 날아간다.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.name_prefix}-mock" }
}

resource "aws_instance" "gateway" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.gateway_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.gateway.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/templates/gateway.sh.tftpl", {
    image       = var.gateway_image
    mock_host   = aws_instance.mock.private_ip
    base_port   = var.mock_base_port
    shard_count = var.mock_shard_count
    db_param    = aws_ssm_parameter.db_password.name
    region      = var.region
  })

  # 부팅 직후 SSM에서 비밀번호를 받아가므로 정책이 먼저 있어야 한다.
  depends_on = [aws_instance.mock, aws_iam_role_policy.param_read]

  user_data_replace_on_change = true

  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.name_prefix}-gateway" }
}

resource "aws_instance" "k6" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.k6_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.k6.id]
  iam_instance_profile   = aws_iam_instance_profile.k6.name

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/templates/k6.sh.tftpl", {
    gateway_host        = aws_instance.gateway.private_ip
    gateway_instance_id = aws_instance.gateway.id
    mock_host           = aws_instance.mock.private_ip
    base_port           = var.mock_base_port
    shard_count         = var.mock_shard_count
    region              = var.region
    repo_ref            = var.repo_ref
    k6_version          = var.k6_version
  })

  # bench.sh가 ssm:SendCommand를 쓰므로 정책이 먼저 있어야 한다. 부팅
  # 자체는 AWS를 호출하지 않지만, apply 직후 바로 sweep을 돌리면
  # 권한 전파 전이라 AccessDenied를 맞을 수 있다.
  depends_on = [aws_instance.gateway, aws_iam_role_policy.bench_control]

  user_data_replace_on_change = true

  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.name_prefix}-k6" }
}
