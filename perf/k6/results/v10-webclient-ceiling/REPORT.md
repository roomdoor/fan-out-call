# v10: WebClient(WebFlux) 천장 탐색

**날짜**: 2026-06-01  
**환경**: realistic mock (정상 7~13s · slow×2개 30s), CORE_POOL=512 / MAX_POOL=1024 / QUEUE=200 / Hikari=10  
**Duration**: 2분/회차, MAX_WAIT_MS=120000

---

## 전체 결과

| RPM | 예상 iters | COMPLETED | PARTIAL | FAILED | 완료율 | e2e avg | e2e p95 | e2e max |
|-----|-----------|-----------|---------|--------|--------|---------|---------|---------|
| 60  | 120       | 120       | 0       | 0      | 100%   | 31,480ms | 31,531ms | - |
| 80  | 160       | 161       | 0       | 0      | 100%   | 31,569ms | 32,290ms | - |
| 100 | 200       | 201       | 0       | 0      | 100%   | 31,472ms | 31,552ms | - |
| 120 | 240       | 240       | 0       | 0      | 100%   | 31,685ms | 32,964ms | 36,928ms |
| 150 | 300       | 301       | 0       | 0      | 100%   | 31,450ms | 31,528ms | 31,764ms |
| 200 | 400       | 400       | 0       | 0      | 100%   | 32,109ms | 34,288ms | 38,711ms |
| 300 | 600       | 601       | 0       | 0      | 100%   | 31,461ms | 31,586ms | 31,784ms |
| 400 | 800       | 801       | 0       | 0      | 100%   | 31,840ms | 33,440ms | 37,335ms |
| **500** | **1000** | **879** | **?** | **0** | **88%** | 38,689ms | 47,405ms | 48,617ms |
| **600** | **1200** | **604** | **597** | **0** | **50%** | 44,421ms | 53,373ms | 55,116ms |
| **700** | **1400** | **459** | **756** | **0** | **33%** | 46,219ms | 62,392ms | 70,489ms |

---

## 결론

### 안정 처리 한계: **400 RPM**

- RPM 60 ~ 400: COMPLETED 100%, FAILED 0, e2e p95 31~34s (slowest bank 기인) → **완전 안정**
- RPM 500부터 PARTIAL 증가, e2e p95 47s로 지연 누적 시작
- RPM 600/700: COMPLETED/PARTIAL 반반 → 시스템은 살아있으나 처리 한계 초과

### FAILED 0의 의미

WebFlux는 아무리 높은 RPM에서도 **RejectedExecutionException 없음**.  
async-threadpool처럼 즉시 fail-fast하지 않고, 슬로우다운(PARTIAL)으로 graceful degradation.  
→ 실서비스에서는 PARTIAL 트랜잭션 처리 전략이 필요 (재시도, SLA 알림 등).

### PARTIAL 발생 원인 (추정)

RPM 500+에서 동시 실행 중인 bank call이 급증:
- 400 RPM × 50 banks × ~13s avg = 동시 bank call ~4,333개
- 500 RPM × 50 banks × ~13s avg = 동시 bank call ~5,417개  
→ mock server(docker 10 shard) 또는 MySQL write 처리량이 bottleneck으로 추정

### async-threadpool vs webclient 최종 비교

| 항목 | async-threadpool | webclient |
|------|-----------------|-----------|
| 안전 처리 한계 | **60 RPM** | **400 RPM** |
| 한계 초과 시 | 즉시 FAILED (RejectedExecution) | 점진적 PARTIAL (graceful) |
| 한계 배수 | — | **6.7× 우위** |
