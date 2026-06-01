# v9: WebClient (WebFlux) 모드 vs async-threadpool 비교

**날짜**: 2026-06-01  
**환경**: realistic mock (정상 7~13s · slow×2개 30s), CORE_POOL=512 / MAX_POOL=1024 / QUEUE=200  
**Duration**: 2분/회차, MAX_WAIT_MS=120000

---

## 결과 비교

### webclient 모드 (이번 테스트)

| RPM | iters | COMPLETED | FAILED | e2e avg | e2e p95 |
|-----|-------|-----------|--------|---------|---------|
| 60  | 119   | 120       | 0      | 31,480ms | 31,531ms |
| 80  | 158   | 161       | 0      | 31,569ms | 32,290ms |
| 100 | 198   | 201       | 0      | 31,472ms | 31,552ms |

### async-threadpool 모드 (v4~v8 기준값)

| RPM | iters | COMPLETED | FAILED | e2e avg | e2e p95 |
|-----|-------|-----------|--------|---------|---------|
| 60  | 112   | 116       | 0      | 35,952ms | 41,502ms |
| 80  | 160   | 53        | 107    | 12,410ms | 41,477ms |
| 100 | -     | 35        | 165    | -        | -        |

---

## 결론: **WebFlux(webclient)가 압도적으로 우수**

| 항목 | async-threadpool | webclient |
|------|-----------------|-----------|
| 안전 처리량 | **60 RPM** | **100 RPM 이상** (천장 미확인) |
| RPM 80 성공률 | 33% (53/160) | **100%** (161/161) |
| RPM 100 성공률 | 21% (35/200) | **100%** (201/201) |
| e2e p95 | 41s (slowest bank 기인) | 31~32s (오버헤드 감소) |
| 실패 원인 | RejectedExecutionException (thread pool full) | 없음 |

### 왜 차이가 나는가

`async-threadpool`은 bank call 1개 = OS thread 1개 점유. 50개 은행 × 요청이 쌓이면 수천 개 thread가 필요.

`webclient`(WebFlux)는 Reactor non-blocking I/O → **thread를 점유하지 않고 I/O 대기**. 소수의 event loop thread로 수천 개 동시 요청 처리 가능.

### 다음 단계

- RPM 120, 150, 200으로 webclient 천장 탐색
- coroutine 모드와 webclient 모드 직접 비교 (구조는 다르나 둘 다 non-blocking)
