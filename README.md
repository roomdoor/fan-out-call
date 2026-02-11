# Loan Limit Gateway (Mock)

Spring Boot + Kotlin + MySQL 기반의 대출 한도 조회 외부 API 호출용 서버 골격입니다.

현재 구현 범위:
- 50개 금융사 API를 동시 호출하는 단일 진입 API
- 금융사별 서비스 인터페이스(`buildRequest`, `callApi`, `toEntity`) 기반 호출 구조
- 모든 금융사 요청/응답 포맷은 현재 `NESTED_JSON`으로 통일
- 실제 외부 연동 대신 Mock 응답 사용
- 배치 실행 이력 + 금융사별 호출 결과 MySQL 저장

Mock 지연 시뮬레이션 기본값:
- 대부분 금융사: 3~15초
- 일부 금융사(기본 2개): 30~45초

## 내부 구조

- 금융사 서비스: `LenderApiService` (`buildRequest`, `callApi`, `toEntity`)
- 금융사별 서비스 레지스트리: `LenderApiServiceRegistry`
- Fan-out 실행 인터페이스: `LenderFanOutExecutor`
- 현재 Fan-out 구현체: `CoroutineLenderFanOutExecutor`

## API

### POST `/api/v1/loan-limit/queries`

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

### GET `/api/v1/loan-limit/queries/{transactionId}`

POST 호출 시 받은 `transactionId`로 진행 상태/완료 결과를 조회합니다.

### GET `/api/v1/loan-limit/queries/number/{transactionNo}`

POST 호출 시 받은 `transactionNo`(유니크 번호)로 진행 상태/완료 결과를 조회합니다.

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
