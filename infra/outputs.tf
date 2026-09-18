output "gateway_private_ip" {
  description = "A 호스트 사설 IP. k6의 BASE_URL이 가리키는 곳."
  value       = aws_instance.gateway.private_ip
}

output "mock_private_ip" {
  description = "B 호스트 사설 IP. 게이트웨이의 mock-base-url이 가리키는 곳."
  value       = aws_instance.mock.private_ip
}

output "instance_ids" {
  description = "SSM 접속용 인스턴스 ID"
  value = {
    gateway = aws_instance.gateway.id
    mock    = aws_instance.mock.id
    k6      = aws_instance.k6.id
  }
}

output "connect" {
  description = "각 호스트 접속 명령 (SSH 키 불필요)"
  value = {
    gateway = "aws ssm start-session --target ${aws_instance.gateway.id} --region ${var.region}"
    mock    = "aws ssm start-session --target ${aws_instance.mock.id} --region ${var.region}"
    k6      = "aws ssm start-session --target ${aws_instance.k6.id} --region ${var.region}"
  }
}

output "region" {
  description = "스택이 뜬 리전. 스크립트가 읽는다."
  value       = var.region
}

output "results_bucket" {
  description = "측정 결과 회수 버킷. perf/k6/fetch-results.sh 가 쓴다."
  value       = aws_s3_bucket.results.bucket
}

output "db_password" {
  description = "MySQL root 비밀번호. apply마다 새로 생성되며 저장소에 들어가지 않는다."
  value       = random_password.db.result
  sensitive   = true
}

output "next_steps" {
  description = "apply 후 할 일"
  value       = <<-EOT
    1) 부트스트랩 완료 확인 (세 호스트 모두 /var/lib/bench-ready 생성)
       aws ssm start-session --target ${aws_instance.gateway.id} --region ${var.region}
       ls /var/lib/bench-ready && tail /var/log/bench-bootstrap.log

    2) 스모크 (C 호스트에서, 2~3분)
       부하 없이 모드당 1건씩 넣어 은행 호출과 저장까지 도는지 본다.
       게이트웨이를 손으로 띄울 필요 없다 — 스크립트가 띄운다.

       aws ssm start-session --target ${aws_instance.k6.id} --region ${var.region}
       cd /opt/fan-out-call/perf/k6
       ./smoke.sh

    3) 측정 (스모크가 PASS 한 뒤)
       ./bench.sh config/v15-pool512.env
       node parse.mjs results/v15-pool512

    4) 결과를 로컬로 내려받는다. 여기부터는 SSM 세션이 아니라 로컬에서 친다.
       destroy 하면 C 와 버킷이 같이 사라지므로 반드시 5) 보다 먼저 돌릴 것.

       (로컬, 저장소 루트에서)
       ./perf/k6/fetch-results.sh

    5) 내려받은 결과를 커밋한 뒤
       terraform destroy
  EOT
}
