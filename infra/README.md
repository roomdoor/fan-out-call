# 측정 인프라 (Terraform)

fan-out 부하 측정을 **측정할 때만** AWS에 띄우고 끝나면 지우기 위한 구성이다.

로컬 단일 머신 측정의 한계를 없애는 게 목적이다. 기존 결과는 게이트웨이·mock 10샤드·MySQL·k6가 전부 한 기계에 있어서, 400 RPM 이상 구간이 인프라 포화에 오염돼 있었다.

## 호스트 배치

측정 대상만 혼자 둔다는 원칙으로 셋으로 나눈다.

| 호스트 | 올리는 것 | 기본 타입 | 이유 |
| --- | --- | --- | --- |
| A | 게이트웨이 + MySQL | `c7i.2xlarge` | 측정 대상. 여기만 깨끗하면 된다 |
| B | mock 10샤드 | `m7i.xlarge` | 대부분 sleep이라 CPU보다 메모리 |
| C | k6 | `c7i.2xlarge` | maxVUs가 `RPM x 10`. 600 RPM이면 6,000 VU |

k6를 B와 합치지 않는 이유는, k6가 CPU를 먹으면 mock 응답이 느려지고 그게 게이트웨이 성능 저하로 잘못 읽히기 때문이다. 측정 도구가 측정을 오염시키는 상황이 로컬에서 벌어진 일이다.

세 인스턴스는 같은 AZ, 같은 서브넷에 둔다.

## 사전 준비

- `terraform` >= 1.6, `aws` CLI
- AWS 자격증명 (`aws configure` 또는 `AWS_PROFILE`)
- **GHCR 패키지 두 개가 public 이어야 한다.** 저장소가 public이어도 패키지는 private으로 생성된다. 각 저장소 → Packages → Package settings → Change visibility → Public

## 사용

```bash
cd infra
terraform init
terraform apply

# 측정 ...

terraform destroy    # 반드시
```

`apply` 출력의 `next_steps` 에 이후 절차가 들어있다.

접속은 SSM Session Manager로 한다. SSH 키도 22번 포트도 쓰지 않는다.

```bash
aws ssm start-session --target <instance-id> --region ap-northeast-2
```

## 설계 메모

**NAT Gateway를 만들지 않는다.** 퍼블릭 서브넷 + 인터넷 게이트웨이만 쓴다. NAT는 트래픽이 0이어도 시간당 요금이 계속 나가고, 여기서는 필요가 없다.

**3306 인바운드 규칙이 없다.** MySQL은 A 호스트 안에서만 쓰이고 `127.0.0.1:3306` 에만 바인딩된다. 외부 접근 경로 자체를 만들지 않았다.

**MySQL 비밀번호는 `apply` 마다 새로 생성한다.** 저장소에 들어가지 않는다. `terraform output -raw db_password` 로 볼 수 있다.

**x86 전용이다.** Graviton(arm64)이 더 싸지만 GHCR 이미지가 amd64 단일이라 에뮬레이션이 걸리고, JVM 기동과 스케줄링이 왜곡되어 측정값이 오염된다. 인스턴스 타입을 arm64로 바꾸려면 이미지 빌드도 멀티아치로 바꿔야 한다.

**커널 파라미터를 올려둔다.** macOS의 `kern.num_taskthreads=2048` 때문에 로컬에서는 pool 1700이 상한이었고, 그게 async-threadpool 천장 185 RPM의 원인이었다. Linux에는 그 벽이 없으므로 `kernel.threads-max` 와 `pid_max` 를 올려 `RPM ~ pool / 9` 공식을 더 높은 pool에서 검증할 수 있게 했다.

**게이트웨이는 부팅 시 자동 기동하지 않는다.** 측정 스크립트가 회차마다 pool/queue를 바꿔 재기동하기 때문이다. 대신 `/usr/local/bin/gateway-run.sh` 헬퍼가 깔린다. 받은 인자는 그대로 Spring 인자가 된다.

```bash
sudo JAVA_TOOL_OPTIONS="-Dkotlinx.coroutines.io.parallelism=192" \
  gateway-run.sh --app.async-thread-pool.core-pool-size=1700
```

SSM 접속 기본 사용자는 `ssm-user`(비root)다. 헬퍼는 docker 실행과 `/etc/bench-db-password`(0600, root 소유) 읽기가 필요하므로 `sudo` 로 돌려야 한다.

## 비용

인스턴스 셋이 켜져 있는 동안만 과금된다. 공인 IPv4 주소도 개당 과금 대상이다.

- `destroy` 를 잊지 말 것. 태그 `Ephemeral=true` 로 남은 리소스를 찾을 수 있다
- AWS Budgets 알림을 따로 걸어둘 것. 자동화가 실패하는 날이 온다

## 남은 작업

`perf/k6/load_sweep.sh` 는 아직 게이트웨이를 자기 기계에서 `java -jar` 로 띄우고 로그를 `tail` 한다. 원격 기동(SSM)과 로그 수거로 바꿔야 sweep 전체가 자동으로 돈다. 실효 처리율을 게이트웨이 로그 파싱으로 세기 때문에 로그 수거가 핵심이다.
