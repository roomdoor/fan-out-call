# Loan Limit Gateway — 50개 외부 금융사 fan-out 호출 성능 측정

Spring Boot 4 + Kotlin 기반의 대출 한도 조회 게이트웨이.
한 트랜잭션이 50개 외부 금융사 API를 병렬 호출(fan-out)하고 결과를 종합·저장한다.

**목적**: 동일한 비즈니스 로직을 **4가지 동시성 모델**로 구현하고, 같은 부하에서 **무엇이 처리 한계를 정하는지** 실측한다.

mock 서버: [`roomdoor/fan-out-api-mock-server`](https://github.com/roomdoor/fan-out-api-mock-server) (Ktor, 10 샤드)

측정 환경: AWS 3호스트 — 게이트웨이+MySQL(c7i.2xlarge), mock(m7i.xlarge), k6(c7i.2xlarge). 같은 AZ.

---

## TL;DR

**블로킹 모델의 천장은 스레드 수가 정한다. 논블로킹은 그 제약이 없다.**

| 모드 | 스레드 | 480 RPM 결과 | 지연이 유지되는 한계 |
| --- | --- | --- | --- |
| **async-threadpool** | @Async 512 + IO 512 ≈ **1,024** | 은행콜 3,409건 거부, 처리율 338.8/분 | 480에서 이미 포화 |
| **coroutine** | IO **512** | 거부 0, 처리율 480.3/분 | **1,600 RPM** |
| **webclient** | IO **512** | 거부 0, 처리율 480.3/분 | **1,600 RPM** |
| sequential | 1 | — | anti-pattern 시연용 |

논블로킹 두 모드는 1,800 RPM에서도 7,200건을 **전부 처리했다.** 거부가 아니라 지연이 늘었을 뿐이다(31.4초 → 56.3 / 44.6초). 위 표의 1,600은 "이론 하한 지연을 유지하는" 한계다.

**스레드를 절반만 쓰고 부하를 전부 받아냈다.** 그때 게이트웨이 CPU는 16%였다 — 자원이 없어서 거부한 게 아니다.

> mock 고정 지연(정상 48개 7~13초, slow 2개 30초, 성공률 100%) 기반의 상대 비교다. 실제 금융사 성능이 아니다.

---

## 1. 스레드 풀 천장은 `pool ÷ 9`

요청 1건이 스레드를 얼마나 쓰는지 세면 나온다.

```
48개 은행 × 10초  = 480 스레드초
 2개 은행 × 30초  =  60 스레드초
────────────────────────────────
                    540 스레드초 = 9 스레드분
```

블로킹 호출은 응답을 기다리는 내내 스레드를 붙잡는다. 그래서 풀 N개가 1분에 공급하는 N 스레드분을 9로 나눈 값이 한계다.

**4배 구간에서 확인했다.**

| pool | 계산 천장 | 깨끗한 점 | 막힌 점 | 거부된 은행콜 |
| --- | --- | --- | --- | --- |
| 512 | 57 | 30 ✅ | 60 | 317 |
| 1024 | 114 | 60 ✅ | 120 | 740 |
| 2048 | 228 | 120 ✅ | 240 | 1,641 |
| 4096 | 455 | 240 ✅ | 480 | 3,409 |

**각 풀에서 막힌 RPM이 다음 풀에서는 정확히 0이다.** 세 번 연속 그랬다. 같은 60 RPM이 pool 512에서는 317건 거부, pool 1024에서는 0건이다.

천장 값 자체(57/114/228/455)는 계산이고 재지 않았다. 잰 것은 위의 구간이다.

원본: [`perf/k6/results/v15-pool512`](perf/k6/results/v15-pool512) 외 3개.

---

## 2. 논블로킹은 다른 자리에서 꺾인다

같은 512 스레드로 RPM을 올리며 e2e p95를 봤다. 이론 하한은 31초(slow 은행 30초 + 오버헤드).

| RPM | coroutine | webclient |
| --- | --- | --- |
| 480 | 31,396ms | 31,392ms |
| 960 | 31,409ms | 31,401ms |
| 1200 | 31,425ms | 31,418ms |
| 1400 | 31,533ms | 31,471ms |
| 1600 | 32,044ms | 31,632ms |
| **1800** | **56,344ms** | **44,550ms** |

**1600까지 이론 하한에 붙어 있다가 1800에서 꺾인다.** 전 구간 거부 0이다.

**포화를 표현하는 방식이 다르다.**

| | 포화 신호 | 받은 요청은 |
| --- | --- | --- |
| async-threadpool | 즉시 거부 (큐 200칸이 차면) | 31초에 끝낸다 |
| 논블로킹 | 지연 증가 | 다 받지만 다 늦어진다 |

거절할 큐가 없으니 일이 그냥 쌓인다. 어느 쪽이 나은지는 요구사항이 정한다 — "늦어도 다 처리"면 논블로킹, "빠르거나 거절"이면 큐 있는 쪽이다.

**두 논블로킹 구현은 1600까지 구분되지 않는다.** 1200에서 7ms 차이다. 1800에서 처음 갈린다(webclient가 21% 빠름). 차이는 "코루틴이냐 Reactor냐"가 아니라 **"스레드를 붙잡느냐 놓느냐"** 에 있다.

원본: [`v15-coroutine`](perf/k6/results/v15-coroutine) · [`v15-webclient`](perf/k6/results/v15-webclient) · [`v15-nonblocking-knee`](perf/k6/results/v15-nonblocking-knee)

---

## 3. 왜 스레드가 8배 많은데 지는가

**스레드가 노는 게 아니라, 막혀 있으면서 자리를 차지한다.**

480 RPM이면 은행 호출 4,320개가 동시에 떠 있다. 블로킹은 그걸 감당하려면 스레드가 4,320개 필요하다 — **스레드가 "일하는 단위"가 아니라 "떠 있는 요청의 자리표"** 가 된다.

CPU가 증거다.

| | CPU |
| --- | --- |
| async-threadpool, 480 RPM에서 3,409건 거부 | 약 16% |
| coroutine, 1,200 RPM 무흠집 | 31% |

기계가 84% 놀고 있는데 거부했다.

---

## 미해결

**1800 RPM에서 지연이 뛰는 원인을 못 찾았다.**

| 후보 | 검증 |
| --- | --- |
| 커넥션 풀 | 20,000 → 40,000, 지연 1.0% 차이 |
| IO 워커 | 512 → 1,024, webclient는 오히려 16% 악화 |
| CPU | k6 5.8% / 게이트웨이 38.4% / mock 20.1% |
| **DB 저장** | 초당 1,500건, 게이트웨이와 같은 호스트. **미검증** |

IO 워커를 늘렸더니 느려진 것이 DB 쪽을 가리킨다. 두 모드 모두 결과 저장을 `Dispatchers.IO` 로 넘기므로, 워커가 두 배면 MySQL로 가는 동시 저장도 두 배다.

**논블로킹 천장도 못 찾았다.** 1800에서 꺾이지만 거부가 없어 "천장"의 정의가 필요하다. SLA를 정하면(예: e2e p95 45초) 그 지점이 천장이 된다.

**전부 n=1이다.** pool512만 두 번 쟀고 e2e p95가 7ms 차이로 재현됐다.

---

## 방법론

### 숫자를 DB에서 센다

k6 지표로는 실효 처리율을 못 잰다 — 풀에서 거부된 트랜잭션도 빠른 `202` 를 받아 k6에는 성공으로 보인다.

그래서 게이트웨이가 남긴 DB 행을 센다. 은행 호출은 다섯 갈래, 트랜잭션 상태는 여섯 갈래로 나누고, **상태별 합이 전체 run 수와 같은지** 회차마다 검사한다. 어느 칸에도 안 잡히는 상태가 생기면 그 회차를 무효로 표시한다.

### 드레인

k6는 `DURATION` 에서 멈추지만 트랜잭션 하나가 최소 31초다. 막바지에 넣은 건들이 끝날 때까지 기다린 뒤에 센다. 짧게 기다리면 처리량이 낮게 나오고, **부하가 셀수록 많이 빠져 천장이 실제보다 낮아 보인다.**

`status='IN_PROGRESS'` 가 0이면 끝이다. 추측하지 않는다.

### 회차가 실패해도 그 회차만 버린다

기동 실패, k6 비정상 종료, DB 쿼리 실패, 드레인 상한, 집계 불일치 — 어느 쪽이든 그 회차에 `valid: false` 를 남기고 다음으로 간다. 스크립트가 실행 중에 "천장이다"라고 판단해서 남은 회차를 건너뛰지 않는다. 그 판단은 사람이 표를 보고 한다.

### 조건 추적

회차마다 manifest에 이미지 **다이제스트**, JVM 플래그, Spring 인자, duration, k6 버전을 남긴다. 태그가 아니라 다이제스트라 `latest` 가 가리키는 대상이 바뀌어도 어느 빌드였는지 남는다.

설계 배경은 [`perf/k6/DECISIONS.md`](perf/k6/DECISIONS.md).

### 자동화

| 경로 | 용도 |
| --- | --- |
| `perf/k6/smoke.sh` | 배포 직후 점검. 모드당 1건, 부하 없음 |
| `perf/k6/bench.sh` | 측정 오케스트레이터. k6 호스트에서 돌며 게이트웨이를 SSM으로 제어 |
| `perf/k6/config/*.env` | 측정 세대 하나 = 파일 하나 |
| `perf/k6/parse.mjs` | 결과 + manifest → 마크다운 표 |
| `perf/k6/fetch-results.sh` | 결과를 S3 경유로 로컬 회수 |
| `infra/` | 측정용 AWS 3호스트 Terraform |

---

## 운영 참고

```yaml
app:
  banks:
    parallelism: 50
    per-call-timeout-ms: 50000
  web-client-fan-out:
    routing-mode: sharded      # 10 샤드에 분산
    max-connections: 20000     # RPM × 9 이상. 8000이면 889 RPM에서 먼저 마른다
```

**모드 선택**

- **coroutine / webclient** — 이 부하 범위에서 차이가 없다. 코드 스타일로 고르면 된다
- **async-threadpool** — 쓰려면 `pool ≥ 목표 RPM × 9` 를 확보해야 한다. 480 RPM이면 4,320개다

**커넥션 풀은 스레드보다 싸다.** 스레드 1개가 1MB 스택을 쓰는 반면 커넥션은 수십 KB다. 논블로킹은 비싼 자원(스레드)을 싼 자원(커넥션)으로 바꾸는 셈이다.

---

## 아키텍처

### 패키지 구조

```
loanLimitBatchRun/      submit 공통 오케스트레이션 + polling
bankCallResult/         은행 결과 저장 + retry
fanout/                 4가지 fan-out 실행 전략
  coroutine/            CoroutineBankFanOutExecutor
  asyncpool/            AsyncThreadPoolBankFanOutExecutor + Worker
  webclient/            WebClientBankFanOutExecutor
  sequential/           SequentialSingleThreadBankFanOutExecutor (bad-case)
bank/                   BankApiService 인터페이스 + ExternalBankApiService 구현
config/                 AppProperties, AsyncExecutionConfig, WebClientConfig
```

- submit API는 모드별 `*LoanLimitQueryController`로 분리되고 내부에서 `LoanLimitQueryOrchestrator`로 수렴
- polling은 `LoanLimitBatchRunController` 단일 엔드포인트
- 각 executor는 `BankFanOutExecutor` 를 구현, `BankFanOutExecutorRegistry` 가 모드별 매핑
- 은행 호출은 네 모드가 **하나의 WebClient 풀을 공유**한다 (`WebClientConfig.sharedBankWebClient`)

### API

```
POST /api/v1/loan-limit/queries                       # coroutine
POST /api/v1/loan-limit/async-threadpool/queries
POST /api/v1/loan-limit/webclient/queries
POST /api/v1/loan-limit/sequential/queries

GET  /api/v1/loan-limit/queries/request/{requestId}   # polling (모드 공통)
GET  /api/v1/loan-limit/queries/number/{transactionNo}
```

요청:
```json
{
  "borrowerId": "USER-1001",
  "annualIncome": 70000000,
  "requestedAmount": 30000000
}
```

submit은 즉시 `202 Accepted` 와 `transactionNo`, `requestId` 를 반환한다. fan-out은 백그라운드에서 진행되고 polling으로 확인한다.

### 실패 격리

은행 하나의 실패가 나머지를 막지 않는다. 막는 자리가 종류별로 다르다.

- **저장 실패** — `LoanLimitQueryOrchestrator` 가 `onEachResult` 를 감싼다. 정의되는 곳이 한 곳뿐이라 네 모드가 같은 정책을 쓴다
- **제출 실패** — 모드마다 구조가 달라 각 executor가 맡는다
- **풀 거부** — async-threadpool만 해당. `REJECTED` 행으로 기록

`status='FAILED'` 는 세 가지로 갈린다 — 은행을 다 호출했는데 성공이 0(부하 신호), 집계 단계에서 막힘(DB 부하), fan-out이 예외로 중단(코드 문제). `fail_reason` 컬럼이 그 구분자다.

---

## 실행

측정 인프라는 [`infra/README.md`](infra/README.md), 측정 스크립트는 [`perf/k6/README.md`](perf/k6/README.md).

### AWS에서 측정

```bash
cd infra
terraform init && terraform apply

# 출력의 next_steps 를 따른다
#   1) 부트스트랩 확인
#   2) ./smoke.sh          배포 직후 점검, 2~3분
#   3) ./bench.sh config/v15-pool512.env
#   4) ./perf/k6/fetch-results.sh   결과 회수 (destroy 전에)
#   5) terraform destroy
```

인스턴스는 쓸 때만 켠다. 측정 사이에는 `stop` 으로 내려두면 결과가 디스크에 남고 EBS 요금만 든다.

### 로컬에서 기동

```bash
git clone git@github.com:roomdoor/fan-out-api-mock-server.git
export MOCK_SERVER_DIR=$PWD/fan-out-api-mock-server
(cd "$MOCK_SERVER_DIR" && ./gradlew installDist)

docker compose up -d mysql

(cd "$MOCK_SERVER_DIR/perf" && \
  set -a && source realistic.env && set +a && \
  docker compose up -d --build)

./gradlew bootJar
java -jar build/libs/loan-limit-gateway-*.jar \
  --app.web-client-fan-out.routing-mode=sharded
```

로컬은 동작 확인용이다. **성능 측정에는 쓰지 않는다** — 아래 참조.

### 필요한 도구

JDK 25, Docker, k6, Node.js, Terraform, AWS CLI.

---

## Tech Stack

**Backend**: Kotlin · Spring Boot 4 · Spring WebFlux · Kotlin Coroutines · JPA + Flyway · MySQL 8.4
**Async**: ThreadPoolTaskExecutor · Reactor Netty · `Dispatchers.IO`
**Mock**: Ktor (별도 저장소)
**측정**: k6 · Terraform · AWS (EC2 · SSM · S3)
**Build**: Gradle (Kotlin DSL) · JDK 25 toolchain

---

## 결과 디렉터리

`perf/k6/results/v15-*` — 이 문서의 모든 수치. AWS 3호스트 실측 24회차.

`perf/k6/results/v1~v14` — 게이트웨이·mock·MySQL·k6를 한 대에 올려 측정했다. 어느 쪽이 병목인지 구분되지 않아 결과를 쓰지 않는다. 기록으로만 남겨둔다.
