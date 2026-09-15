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

# MySQL 비밀번호. 저장소에는 들어가지 않는다.
# state에는 평문으로 저장되므로 infra/.gitignore가 tfstate를 막고 있다.
# destroy/recreate 전까지는 같은 값이 유지된다(keepers 미사용).
resource "random_password" "db" {
  length  = 32
  special = false
}

# 비밀번호를 user-data에 넣지 않는다. user-data는 인스턴스의 모든 프로세스가
# IMDS(169.254.169.254/latest/user-data)로 읽을 수 있고, 게이트웨이 컨테이너는
# --network host로 돌기 때문에 그 안에서도 읽힌다. ec2:DescribeInstanceAttribute
# 권한자도 볼 수 있다. SecureString으로 두고 부팅 때 받아간다.
resource "aws_ssm_parameter" "db_password" {
  name  = "/${var.name_prefix}/db-password"
  type  = "SecureString"
  value = random_password.db.result

  tags = { Name = "${var.name_prefix}-db-password" }
}

data "aws_availability_zones" "available" {
  state = "available"
}

# 세 인스턴스를 한 AZ에 몰아넣는다. AZ가 갈리면 통신이 AZ 간 경로를 타고
# 지연과 요금이 붙는데, 이 측정에서는 둘 다 순수한 오염이다.
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

# 인라인 ingress/egress 블록과 standalone rule 리소스를 같은 보안 그룹에
# 섞으면 안 된다. 섞으면 refresh가 실제 규칙을 인라인 속성으로 읽어들이고,
# 설정에는 없으므로 다음 apply가 그 규칙을 지운다. 측정 도중에 끊긴다.
# 그래서 egress도 전부 standalone으로 둔다.
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

# bench.sh는 C(k6)에서 돌면서 A(게이트웨이)를 SSM으로 제어한다.
# 회차마다 게이트웨이를 재기동하고 로그 카운트를 받아오기 위한 권한이다.
# 대상은 이 스택의 인스턴스와 RunShellScript 문서로 한정한다.
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

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

}

# 비밀번호 읽기는 별도 정책으로 둔다. bench_control은 게이트웨이 ARN을
# 참조하므로, 게이트웨이가 그 정책에 depends_on 하면 순환이 생긴다.
# 이 정책은 인스턴스를 참조하지 않으므로 순환 없이 먼저 만들 수 있다.
data "aws_iam_policy_document" "param_read" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
  }

  # SecureString 복호화. 계정 기본 SSM 키로만 제한한다.
  statement {
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:alias/aws/ssm"]
  }
}

resource "aws_iam_role_policy" "param_read" {
  name   = "${var.name_prefix}-param-read"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.param_read.json
}

resource "aws_iam_role_policy" "bench_control" {
  name   = "${var.name_prefix}-bench-control"
  role   = aws_iam_role.instance.id
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

  tags = { Name = "${var.name_prefix}-gateway" }
}

resource "aws_instance" "k6" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.k6_instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.k6.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

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
  })

  depends_on = [aws_instance.gateway]

  tags = { Name = "${var.name_prefix}-k6" }
}
