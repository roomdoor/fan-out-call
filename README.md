# Loan Limit Gateway — 50개 외부 금융사 fan-out 호출 성능 최적화

Spring Boot 4 + Kotlin 기반의 대출 한도 조회 게이트웨이.
한 트랜잭션이 50개 외부 금융사 API를 병렬 호출(fan-out)하고 결과를 종합·저장하는 구조.

**이 저장소의 목적**: 동일 비즈니스 로직을 **4가지 동시성 모델**로 구현하고, 실제 부하 테스트로 **단일 파드 처리 한계를 정량 비교**.

mock 서버: [`roomdoor/fan-out-api-mock-server`](https://github.com/roomdoor/fan-out-api-mock-server) (Ktor 기반, 10 샤드)

---

## TL;DR — 동시성 모델 선택만으로 처리량 10배 차이

mock 응답 시간 정상 7~13s · slow 30s × 2개 환경에서 단일 파드 안전 처리량:

| 모드 | 구현 핵심 | 안전 한계 (RPM) | async-threadpool 대비 |
| --- | --- | --- | --- |
| **async-threadpool** | `@Async + ThreadPoolTaskExecutor` (blocking) | **60 RPM** | 1× (baseline) |
| **webclient** | `WebFlux` + Reactor non-blocking | **400 RPM** | **6.7×** |
| **coroutine** (공유 WebClient) | Kotlin coroutines + `awaitAll()` | **600 RPM** | **10×** |
| sequential | 단일 스레드 순차 호출 (anti-pattern 시연용) | — | — |

> **mock 고정 지연 기반 · 상대 비교용 수치 · 실제 금융사 성능 보장 아님**

핵심 결론:
- **thread pool 크기 증가는 어느 지점부터 효과 없음** — pool 512 → 1024로 2배 늘려도 처리량 변화 0%
- **blocking I/O 모델의 천장은 thread 점유 시간이 결정** — 1 bank task가 30초 스레드 점유 → pool/30s가 곧 천장
- **non-blocking 전환 시 스레드 자원과 무관해짐** — 처리량 6.7~10배 향상, 한계 초과 시 fail-fast → graceful degradation

---

## 핵심 성능 데이터

### 1. 모드별 처리 양상 (RPM별 COMPLETED 트랜잭션 수)

| RPM | async-threadpool | webclient | coroutine (공유 WebClient) |
| --- | --- | --- | --- |
| 60 | 112 (100%) ✅ | 120 (100%) ✅ | 120 (100%) ✅ |
| 80 | **53 (33%)** ⚠️ | 161 (100%) ✅ | 161 (100%) ✅ |
| 100 | **35 (21%)** ❌ | 201 (100%) ✅ | 201 (100%) ✅ |
| 200 | — | 400 (100%) ✅ | 401 (100%) ✅ |
| 400 | — | 800 (100%) ✅ | 800 (100%) ✅ |
| 500 | — | **879 (88%)** ⚠️ | 1,000 (100%) ✅ |
| 600 | — | **604 (50%)** ❌ | **1,201 (100%)** ✅ |
| 700 | — | 459 (33%) ❌ | 310 (22%) ❌ |

- async-threadpool은 thread pool 거부(`RejectedExecutionException`)로 80 RPM에서 67% 실패
- webclient는 500 RPM부터 PARTIAL(일부 은행만 응답)로 graceful하게 저하
- coroutine은 600 RPM까지 완벽 처리, 700에서 첫 PARTIAL 발생

### 2. e2e 지연 (p95)

| RPM | async-threadpool | coroutine |
| --- | --- | --- |
| 60 | 41.6s | 31.6s |
| 80 | 90.0s (timeout) | 31.7s |
| 100 | 90.0s (timeout) | 31.8s |
| 600 | — | **32.6s** (안정) |
| 700 | — | 106.7s (한계) |

mock 환경의 이론치 최소 e2e = 30s (slow bank 30s). coroutine은 600 RPM에서도 이론치 거의 일치 → 게이트웨이 오버헤드 무시 가능 수준.

---

## 성능 조사 여정 (12세대 sweep)

`perf/k6/results/v1~v12/` 디렉토리에 각 단계의 raw 데이터와 보고서 보관.

| 버전 | 가설/시도 | 결과 |
| --- | --- | --- |
| v1 | pool 크기만 변경 (64/128/256/512), mock 500ms | 작은 풀이 더 빨라보이는 측정 함정 발견 — 실은 빠른 실패가 e2e를 끌어내림 |
| v2 | pool×queue 동반 확장 (320→2560 슬롯, 8×) | p95가 오히려 35× 악화. 슬롯 확장 ≠ 처리량 개선 |
| v3 | mock을 realistic (7~13s + slow 30s)으로 변경 | 진짜 부하 환경 도입. 50 RPS stress 부하는 시스템 한계의 5배라 비교 불가 |
| v4 | 부하를 RPM 단위(20~100)로 변경, 고정 부하 | **단일 파드 안전 한계: 60 RPM 확정** (async-threadpool 기준) |
| v5 | asymmetric pool (core=200/max=500/queue=200) | 천장은 같음. 무너지는 방식만 다름(fail-fast vs timeout) |
| v6 | pool 512/1024/2048 matrix sweep | **pool=1024는 pool=512 대비 0% 개선**. 병목 다른 곳 확정. pool=2048은 macOS native thread 한계 초과로 무효 |
| v7 | elastic vs fixed pool (c=512/m=1024 vs c=m=1024) | sustained 부하에선 사실상 동일 |
| v8 | Hikari 가설 검증 (default 10 vs 50) | **반증** — Hikari는 병목 아님 |
| v9 | WebFlux 모드 도입 | **돌파** — 100 RPM까지 FAILED 0건 |
| v10 | webclient 천장 탐색 (120~700 RPM) | webclient 안전 한계 **400 RPM** 확인 |
| v11 | coroutine 모드 첫 테스트 | Reactor Netty pending acquire queue 포화 — 무효 |
| v12 | coroutine 버그 수정 (공유 WebClient) 후 재테스트 | **600 RPM까지 100% 처리** — 최종 답 |

### 발견한 버그 (v11 → v12)

`ExternalBankApiService`가 50개 은행마다 개별 WebClient 풀을 생성 → 고RPM에서 Reactor Netty `Pending acquire queue` 포화로 PARTIAL 폭증.

수정:
- 신규 `config/WebClientConfig.kt`: `sharedBankWebClient` bean (`maxConnections=2000, pendingAcquireMaxCount=10000`)
- `ExternalBankApiService`: 은행별 개별 WebClient → 공유 WebClient 1개
- `BankApiServiceRegistry`: `@Qualifier("sharedBankWebClient")` 주입

이 한 줄짜리 수정으로 coroutine 안전 한계가 webclient(400) 대비 **1.5배(600 RPM)** 으로 도약.

---

## 방법론

### 부하 테스트 설계
- **mock 환경**: 실서비스 응답 분포를 모사. 정상 은행 48개는 7~13s 랜덤, slow 은행 2개는 30s 고정. 100% 성공.
- **부하 도구**: k6 `constant-arrival-rate` 모드. 도착률 일정하게 유지하며 시스템이 무너지는 지점을 정확히 측정.
- **측정 단위**: 분(RPM). 트랜잭션당 30초가 걸리는 환경에서 초 단위(RPS)는 직관적이지 않음.
- **회차당 시간**: 2분. constant-arrival-rate라 충분히 정상 상태 도달.
- **게이트웨이 매 회차 재기동**: clean state 보장, JVM 워밍업 변동 통제.

### 측정 지표
k6 메트릭(`iterations`, `e2e_completion_time`, `timeout_waiting_rate`, `checks`)만으로는 부족 — 풀에서 거부된 트랜잭션도 빠른 200/202를 받아 "성공한 응답"으로 보일 수 있음. 그래서 게이트웨이 로그를 직접 파싱:

- `Background fan-out completed status=COMPLETED`: 50/50 모두 성공한 트랜잭션
- `Background fan-out completed status=PARTIAL`: 일부 은행만 응답
- `Run marked as FAILED ... ExecutorService ... did not accept task`: 풀 거부

`results/REPORT.md` 표의 "실효 처리율"은 모두 이 게이트웨이 로그 카운트 기반.

### 자동화 스크립트

| 스크립트 | 용도 |
| --- | --- |
| `perf/k6/sweep.sh` | pool:queue 페어 sweep (예: `64:256 128:512 256:1024`) |
| `perf/k6/load_sweep.sh` | RPM 부하 sweep (`CORE_POOL`, `MAX_POOL`, `QUEUE`, `HIKARI_MAX_POOL`, `MODE` 인자 지원) |
| `perf/k6/matrix_sweep.sh` | pool×RPM 2차원 matrix sweep |
| `perf/k6/parse.mjs`, `load_parse.mjs`, `matrix_parse.mjs` | 결과 JSON + 게이트웨이 로그 통합 파싱 → 마크다운 표 |
| `perf/k6/harness.sh` | mock fleet up/down + 단일 테스트 실행 |

새 환경에서도 인자 한 줄로 모든 sweep 재현 가능.

### 환경 제약 발견

테스트 중 시스템 한계 정의를 명확히 한 사례:
- **pool=2048**: macOS `kern.num_taskthreads=2,048` 초과 → `OutOfMemoryError: unable to create native thread` 43건. ulimit 사전 점검 필요성 문서화.

---

## 권장 운영 설정 (요약)

```yaml
app:
  banks:
    parallelism: 50
    per-call-timeout-ms: 50000
  web-client-fan-out:
    routing-mode: sharded   # 10 샤드에 부하 분산
```

```kotlin
// WebClientConfig.kt
WebClient.builder()
  .clientConnector(ReactorClientHttpConnector(
    HttpClient.create(
      ConnectionProvider.builder("shared-bank")
        .maxConnections(2000)
        .pendingAcquireMaxCount(10000)
        .build()
    )
  )).build()
```

**모드 선택**: 동시성 모델 선호와 코드 스타일에 따라
- **coroutine** — 최고 처리량(600 RPM), 코드 동기-유사 스타일
- **webclient** — 안정적인 400 RPM, 순수 Reactor 스타일

**SLA 설정 예 (마진 30%)**: coroutine 모드 기준 **분당 420건/파드**. 그 이상은 수평 확장.

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

- submit API는 모드별 `*LoanLimitQueryController`로 분리되어 있고 내부에서 `LoanLimitQueryOrchestrator`로 수렴
- polling은 `LoanLimitBatchRunController` 단일 엔드포인트
- 각 fan-out executor는 `BankFanOutExecutor` 인터페이스를 구현, `BankFanOutExecutorRegistry`가 모드별 매핑

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

submit은 즉시 `202 Accepted`와 `transactionNo`, `requestId` 반환. fan-out은 백그라운드에서 진행되고 polling으로 진행도 확인.

---

## 실행

자세한 셋업 / sweep 사용법은 [`perf/k6/HANDOFF.md`](perf/k6/HANDOFF.md) 참조.

### 빠른 시작
```bash
# 1. mock 서버 클론 및 설치
git clone git@github.com:roomdoor/fan-out-api-mock-server.git
export MOCK_SERVER_DIR=$PWD/fan-out-api-mock-server
(cd "$MOCK_SERVER_DIR" && ./gradlew installDist)

# 2. MySQL 기동
docker compose up -d mysql

# 3. mock fleet 기동 (realistic 지연 프로파일)
(cd "$MOCK_SERVER_DIR/perf" && \
  set -a && source realistic.env && set +a && \
  docker compose up -d --build)

# 4. 게이트웨이 빌드 & 기동
./gradlew bootJar
java -jar build/libs/loan-limit-gateway-*.jar \
  --app.web-client-fan-out.routing-mode=sharded

# 5. 부하 테스트 (분당 100건 coroutine 모드)
MODE=coroutine LOAD_RPM=100 DURATION=2m \
  k6 run --summary-export=results/test.json perf/k6/load.js
```

### 필요한 도구
JDK 25, Docker, k6, Node.js (파서용).

---

## Tech Stack

**Backend**: Kotlin · Spring Boot 4 · Spring WebFlux · Kotlin Coroutines · JPA + Flyway · MySQL 8.4
**Async**: ThreadPoolTaskExecutor · Reactor Netty · `Dispatchers.IO`
**Mock**: Ktor (별도 저장소)
**Testing**: k6 · Docker Compose · Micrometer (gateway metrics)
**Build/Run**: Gradle (Kotlin DSL) · JDK 25 toolchain

---

## 결과 디렉토리 안내

`perf/k6/results/v1~v12/` — 각 세대 raw JSON + REPORT.md.
`perf/k6/HANDOFF.md` — 새 머신에서 작업 이어받기용 컨텍스트 문서 (인프라 셋업, sweep 명령, 핵심 수치 요약).
