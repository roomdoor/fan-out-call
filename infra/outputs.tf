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

    2) 게이트웨이 기동 (A 호스트에서)
       SSM 접속 기본 사용자는 ssm-user(비root)다. docker 실행과 DB 비밀번호
       파일 읽기에 root가 필요하므로 sudo로 돌린다.

       sudo JAVA_TOOL_OPTIONS="-Dkotlinx.coroutines.io.parallelism=192" \
         gateway-run.sh --app.async-thread-pool.core-pool-size=1700 \
                        --app.async-thread-pool.max-pool-size=1700 \
                        --app.async-thread-pool.queue-capacity=200

    3) 부하 실행 (C 호스트에서, BASE_URL은 이미 환경변수로 설정됨)
       cd /opt/fan-out-call/perf/k6
       MODE=coroutine LOAD_RPM=100 DURATION=2m k6 run load.js

    4) 측정 끝나면 반드시
       terraform destroy
  EOT
}
