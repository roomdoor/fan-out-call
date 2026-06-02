# v11: Coroutine 모드 천장 탐색 (결과 무효 — 커넥션 풀 버그)

**날짜**: 2026-06-02  
**환경**: realistic mock (성공률 100%, 7~13s / slow 30s×2), CORE_POOL=512 / MAX_POOL=1024 / QUEUE=200  
**⚠️ 이 결과는 coroutine 고유 성능을 측정하지 못함. 커넥션 풀 설정 문제로 오염됨.**

---

## 원시 수치

| RPM | 예상 | COMPLETED | PARTIAL_FAILURE | FAILED | 커넥션풀 에러 |
|-----|------|-----------|-----------------|--------|--------------|
| 100 | 200  | 29        | 172             | 0      | 6,868        |
| 200 | 400  | 10        | 390             | 0      | 26,636       |
| 400 | 800  | 10        | 756             | 0      | 66,360       |
| 500 | 1000 | 15        | 986             | 0      | 51,654       |
| 600 | 1200 | 22        | 1,179           | 0      | 54,230       |
| 700 | 1400 | 18        | 1,383           | 0      | 61,274       |

---

## 근본 원인

**`Pending acquire queue has reached its maximum size of 1000`**

coroutine 모드는 은행 50개 각각이 독립 `ExternalBankApiService` 인스턴스를 가지고, 각자 고유한 Reactor Netty WebClient 커넥션 풀을 생성한다. RPM이 높아지면 동시 bank call 수가 폭발하면서 각 풀의 pending acquire queue (기본 1000)가 포화된다.

- RPM 100 × 50 banks × 13s avg = ~1,083 동시 커넥션 시도
- 각 `ExternalBankApiService` WebClient 풀 pending limit = 1,000
- → 즉시 queue 포화 → `WebClientRequestException`

webclient 모드(`WebClientBankFanOutExecutor`)는 단일 WebClient + `Flux.flatMap(maxConcurrency=50)`으로 백프레셔를 제어하므로 같은 문제가 없었다.

---

## 수정 방안 (재테스트 필요)

**Option A: 커넥션 풀 한도 늘리기**

`WebClientConfig`에서 Reactor Netty 커넥션 풀 설정 추가:
```kotlin
@Bean
fun webClientBuilder(): WebClient.Builder {
    val httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .responseTimeout(Duration.ofSeconds(60))
        .doOnConnected { conn ->
            conn.addHandlerLast(ReadTimeoutHandler(60))
            conn.addHandlerLast(WriteTimeoutHandler(60))
        }
    val connector = ReactorClientHttpConnector(
        httpClient.connectionProvider(
            ConnectionProvider.builder("bank-pool")
                .maxConnections(2000)
                .pendingAcquireMaxCount(5000)
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .build()
        )
    )
    return WebClient.builder()
        .clientConnector(connector)
        .filter(bankCallLoggingFilter())
}
```

**Option B: 공유 WebClient 사용**

`ExternalBankApiService`가 개별 WebClient를 생성하지 말고 주입받은 단일 공유 WebClient 사용. webclient 모드와 동일한 구조.

---

## 다음 단계

Option A 또는 B로 수정 후 v12로 재테스트.  
공정한 비교를 위해 webclient 모드와 동일 조건(RPM 100~700)으로 실행.
