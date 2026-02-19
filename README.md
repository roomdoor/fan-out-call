# Loan Limit Gateway (Mock)

Spring Boot + Kotlin + MySQL 기반의 대출 한도 조회 외부 API 호출용 서버 골격입니다.

현재 구현 범위:
- 50개 금융사 API fan-out 호출 submit API (4개 모드)
- 금융사별 서비스 인터페이스(`buildRequest`, `callApi`, `toEntity`) 기반 호출 구조
- 모든 금융사 요청/응답 포맷은 현재 `NESTED_JSON`으로 통일
- 모든 모드는 별도 가짜 은행 서버(`loan-limit-mock-server`)를 호출
- 배치 실행 이력 + 금융사별 호출 결과 MySQL 저장

## 내부 구조

패키지 기준 주요 구조:

- `loanLimitBatchRun`
  - `controller/LoanLimitBatchRunController`: 통합 polling API
  - `service/LoanLimitQueryOrchestrator`: submit 공통 처리 + 백그라운드 fan-out 실행 오케스트레이션
  - `service/LoanLimitBatchRunService`: run 상태 완료/실패 처리 + transactionId/transactionNo 조회
  - `repository/LoanLimitBatchRunRepository`
  - `dto/request|response`, `entity`
- `bankCallResult`
  - `service/BankCallResultService`: 결과 저장 + retry
  - `service/BankCatalogService`: `BANK-01` ~ `BANK-50` 코드 목록 생성
  - `repository/BankCallResultRepository`
  - `dto`, `entity`
- `fanout`
  - `BankFanOutExecutor` (공통 인터페이스)
  - `BankFanOutExecutorRegistry` (modeName -> executor 매핑)
  - `coroutine/CoroutineBankFanOutExecutor`
  - `sequential/SequentialSingleThreadBankFanOutExecutor`
  - `asyncpool/AsyncThreadPoolBankFanOutExecutor`, `AsyncBankCallWorker`
  - `webclient/WebClientBankFanOutExecutor`
  - `*/controller/*LoanLimitQueryController` (모드별 submit API)
- `bank`
  - `BankApiService` (`buildRequest`, `callApi`, `toEntity`)
  - `BankApiServiceRegistry`, `ExternalBankApiService`

요약:
- submit API는 모드별 컨트롤러로 분리되어 있고, 내부에서는 `LoanLimitQueryOrchestrator` 한 곳으로 수렴합니다.
- polling API는 `LoanLimitBatchRunController` 단일 엔드포인트로 통합되어 있습니다.

## API

### Coroutine Mode

- `POST /api/v1/loan-limit/queries`

### Sequential Single-Thread Mode (Bad Case)

- `POST /api/v1/loan-limit/sequential/queries`

### Async ThreadPool Mode

- `POST /api/v1/loan-limit/async-threadpool/queries`

### WebClient Non-Blocking Mode

- `POST /api/v1/loan-limit/webclient/queries`

### Unified Polling (All Modes)

- `GET /api/v1/loan-limit/queries/{transactionId}`
- `GET /api/v1/loan-limit/queries/number/{transactionNo}`

예시 요청 바디:

```json
{
  "borrowerId": "USER-1001",
  "annualIncome": 70000000,
  "requestedAmount": 30000000
}
```

이 API는 `202 Accepted`를 즉시 반환하고, 외부 API fan-out은 백그라운드에서 진행됩니다.
응답에는 `transactionNo`(유니크 번호), `transactionId`(UUID), 진행 상태(`status`), 진행 카운트(`successCount`, `failureCount`, `completedCount`)가 포함됩니다.
진행 중에는 `finishedAt`이 `null`이고 `results`는 비어 있을 수 있습니다.
이후 조회 API에서 `transactionNo` 또는 `transactionId`로 polling 조회합니다.

`@Async + ThreadPool` 설정 기본값:
- `app.async-thread-pool.core-pool-size`: 50
- `app.async-thread-pool.max-pool-size`: 64
- `app.async-thread-pool.queue-capacity`: 100
- `app.async-thread-pool.thread-name-prefix`: `loan-limit-async-`

가짜 은행 서버 호출 설정 기본값:
- `app.web-client-fan-out.mock-base-url`: `http://localhost:18080`
- `app.web-client-fan-out.max-concurrency`: 50

submit 모드(coroutine/sequential/async-threadpool/webclient) 실행 전, 별도 mock 서버(`loan-limit-mock-server`)를 먼저 띄워야 합니다.

## 실행 전 준비

1. MySQL 데이터베이스 생성
2. `src/main/resources/application.yml`의 DB 접속 정보 수정

```sql
CREATE DATABASE loan_limit_gateway;
```

Flyway가 실행 시 테이블을 자동 생성합니다.

## Swagger / OpenAPI

애플리케이션 실행 후 아래 URL에서 API를 확인하고 직접 호출할 수 있습니다.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
