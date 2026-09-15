variable "region" {
  description = "측정 인프라를 띄울 리전. 세 인스턴스는 반드시 같은 AZ에 둔다."
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "생성되는 모든 리소스 이름 접두어"
  type        = string
  default     = "fanout-bench"
}

# 인스턴스 타입은 전부 x86(Intel) 계열이다.
# Graviton(arm64)이 더 싸지만 GHCR 이미지가 amd64 단일이라
# 에뮬레이션이 걸리고, JVM 기동과 스케줄링이 왜곡되어 측정값이 오염된다.
variable "gateway_instance_type" {
  description = "A 호스트 — 게이트웨이 + MySQL. 측정 대상이라 여유를 둔다."
  type        = string
  default     = "c7i.2xlarge" # 8 vCPU / 16 GiB
}

variable "mock_instance_type" {
  description = "B 호스트 — mock 10샤드. 대부분 sleep이라 CPU보다 메모리가 중요하다."
  type        = string
  default     = "m7i.xlarge" # 4 vCPU / 16 GiB
}

variable "k6_instance_type" {
  description = "C 호스트 — k6. maxVUs가 RPM x 10이라 600 RPM이면 6,000 VU다."
  type        = string
  default     = "c7i.2xlarge" # 8 vCPU / 16 GiB
}

variable "gateway_image" {
  description = "게이트웨이 컨테이너 이미지. GHCR 패키지가 public이어야 한다."
  type        = string
  default     = "ghcr.io/roomdoor/fan-out-call:latest"
}

variable "mock_image" {
  description = "mock 서버 컨테이너 이미지. GHCR 패키지가 public이어야 한다."
  type        = string
  default     = "ghcr.io/roomdoor/fan-out-api-mock-server:latest"
}

variable "mock_shard_count" {
  description = "mock 샤드 수. 게이트웨이의 sharded-mock-routing.shard-count와 맞춰야 한다."
  type        = number
  default     = 10
}

variable "mock_base_port" {
  description = "mock 샤드 시작 포트. 게이트웨이의 sharded-mock-routing.base-port와 맞춰야 한다."
  type        = number
  default     = 18000
}

# realistic 프로파일. fan-out-api-mock-server/perf/realistic.env 와 같은 값이다.
variable "mock_latency" {
  description = "mock 지연 프로파일. 측정 세대 간 반드시 고정해야 비교가 성립한다."
  type = object({
    min_ms           = number
    max_ms           = number
    slow_min_ms      = number
    slow_max_ms      = number
    slow_bank_count  = number
    success_rate_pct = number
  })
  default = {
    min_ms           = 7000
    max_ms           = 13000
    slow_min_ms      = 30000
    slow_max_ms      = 30000
    slow_bank_count  = 2
    success_rate_pct = 100
  }
}

variable "repo_ref" {
  description = "C 호스트가 클론할 게이트웨이 저장소의 브랜치/태그. bench.sh와 시나리오가 여기서 온다."
  type        = string
  default     = "main"
}

variable "root_volume_gb" {
  description = "루트 볼륨 크기. 게이트웨이 이미지가 500MB를 넘고 로그도 쌓인다."
  type        = number
  default     = 30
}

variable "allowed_ssh_cidr" {
  description = <<-EOT
    비워두면 22번 포트를 열지 않는다(기본값, 권장).
    접속은 SSM Session Manager로 한다 — 키 관리도, 인바운드 포트도 필요 없다.
    SSM이 막힌 환경에서만 본인 IP를 /32로 넣어라.
  EOT
  type        = string
  default     = ""
}

variable "tags" {
  description = "전 리소스 공통 태그. 비용 추적과 destroy 누락 확인에 쓴다."
  type        = map(string)
  default = {
    Project   = "fanout-bench"
    Ephemeral = "true"
  }
}
