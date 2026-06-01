# v8: Hikari Connection Pool 가설 검증

**날짜**: 2026-06-01  
**환경**: realistic mock (정상 7~13s · slow×2개 30s), CORE_POOL=512 / MAX_POOL=1024 / QUEUE=200  
**Duration**: 2분/회차, MAX_WAIT_MS=120000

## 가설

> pool 512 → 1024로 2× 늘려도 처리량이 동일했다.  
> 진짜 병목은 thread pool이 아니라 Hikari connection pool (기본값 `maximum-pool-size=10`) 일 수 있다.

---

## 결과 비교

| 설정 | RPM | iters | COMPLETED | FAILED | check_rate | e2e avg | e2e p95 |
|------|-----|-------|-----------|--------|------------|---------|---------|
| Hikari=10 | 60 | 112 | 116 | 0 | 100% | 35,952ms | 41,502ms |
| **Hikari=50** | **60** | **112** | **116** | **0** | **100%** | **35,735ms** | **41,504ms** |
| Hikari=10 | 80 | 160 | 53 | 107 | 86.6% | 12,410ms | 41,477ms |
| **Hikari=50** | **80** | **160** | **53** | **107** | **86.6%** | **12,372ms** | **41,503ms** |

---

## 결론: **Hikari는 병목이 아니다**

Hikari를 10 → 50으로 5× 늘려도 COMPLETED/FAILED 수, check_rate, e2e 응답시간 모두 **완전히 동일**.

RPM 80 실패 원인도 이전과 동일:

```
RejectedExecutionException: pool size = 1024, active threads = 1024, queued tasks = 200
```

**thread pool이 꽉 찬 것**이 fail-fast 트리거. Hikari와 무관.

---

## 다음 가설

thread pool이 꽉 찬 이유:

- 각 요청이 50개 bank call을 병렬로 실행하지만, `async-threadpool` 모드는 bank call 하나가 thread 하나를 점유
- RPM 80 × 50 banks = 초당 ~67 bank call → 각 bank call이 7~30초 걸리므로 동시 점유 thread = 수백~수천
- pool=1024, queue=200 → 총 슬롯 1,224 → 이 이상 유입되면 즉시 RejectedExecution

**다음 검증 포인트**:
1. `coroutine` 모드로 같은 RPM 80 테스트 → RejectedExecution 없이 더 많은 COMPLETED 달성하는지 확인
2. `webclient` 모드 (non-blocking) 와 비교
