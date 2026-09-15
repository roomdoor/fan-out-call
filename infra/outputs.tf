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

    2) 측정 실행 (C 호스트에서)
       bench.sh가 회차마다 게이트웨이를 SSM으로 재기동하고, k6를 돌리고,
       로그 카운트를 받아 manifest.json에 조건과 함께 남긴다.
       게이트웨이를 손으로 띄울 필요 없다 — 첫 회차에서 어차피 재기동된다.

       aws ssm start-session --target ${aws_instance.k6.id} --region ${var.region}
       cd /opt/fan-out-call/perf/k6
       ./bench.sh config/v15-baseline.env

    3) 결과 보기
       node parse.mjs results/v15-baseline

    4) 측정 끝나면 반드시
       terraform destroy
  EOT
}
