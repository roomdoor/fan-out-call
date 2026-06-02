# v12: Coroutine 모드 재테스트 (커넥션 풀 수정 후)

**날짜**: 2026-06-02  
**환경**: realistic mock (성공률 100%, 7~13s / slow 30s×2), CORE_POOL=512 / MAX_POOL=1024 / QUEUE=200  
**수정사항**: `ExternalBankApiService` 공유 WebClient 주입 (`maxConnections=2000, pendingAcquireMaxCount=10000`)

---

## 결과

| RPM | 예상 | COMPLETED | PARTIAL | FAILED | 풀에러 | e2e avg | e2e p95 | e2e max |
|-----|------|-----------|---------|--------|--------|---------|---------|---------|
| 100 | 200  | 201       | 0       | 0      | 0      | 31,540ms | 31,841ms | 32,410ms |
| 200 | 400  | 401       | 0       | 0      | 0      | 31,678ms | 32,449ms | 36,789ms |
| 400 | 800  | 800       | 0       | 0      | 0      | 39,616ms | 48,934ms | 52,966ms |
| 500 | 1000 | 1000      | 0       | 0      | 0      | 34,455ms | 41,716ms | 45,971ms |
| **600** | **1200** | **1201** | **0** | **0** | **0** | 31,962ms | 32,629ms | 33,762ms |
| **700** | **1400** | **310**  | **108** | **0** | **0** | 74,051ms | 106,731ms | 108,972ms |

---

## webclient vs coroutine(수정) 최종 비교

| RPM | webclient COMPLETED | coroutine COMPLETED | 비고 |
|-----|---------------------|---------------------|------|
| 100 | 201 (100%) | 201 (100%) | 동일 |
| 200 | 400 (100%) | 401 (100%) | 동일 |
| 400 | 800 (100%) | 800 (100%) | 동일 (coroutine p95 49s로 다소 높음) |
| 500 | 879 (88%) ⚠️ | **1000 (100%)** ✅ | coroutine 우위 |
| 600 | 604 (50%) ❌ | **1201 (100%)** ✅ | coroutine 압도적 우위 |
| 700 | 459 (33%) ❌ | 310 (22%) ❌ | 둘 다 한계 초과 |

**안전 처리 한계:**
- webclient: **400 RPM**
- coroutine(수정): **600 RPM**

---

## 결론

### coroutine > webclient (수정 후 기준)

커넥션 풀 버그를 수정하자 coroutine이 webclient 대비 **1.5배 높은 천장**을 보임.

RPM 500~600 구간에서 webclient는 PARTIAL_FAILURE로 무너지는 반면,  
coroutine은 `Dispatchers.IO` + `awaitAll()`로 안정적으로 처리.

RPM 400에서 coroutine p95(48s)가 webclient p95(33s)보다 높은 것은  
`Dispatchers.IO` 스케줄링 오버헤드로 추정. 낮은 RPM에서는 webclient가 더 일정한 지연.

### RPM 700 붕괴 패턴

- FAILED=0 (RejectedExecution 없음)
- PARTIAL=108 (일부 은행만 응답)
- e2e p95=107s (MAX_WAIT_MS=120s 임박)
- 추정 원인: mock server(docker 10 shard) 또는 MySQL 쓰기 처리량 한계

### 핵심 수정 내용

`ExternalBankApiService`: 은행별 개별 WebClient(50개 풀) → 단일 공유 WebClient  
`BankApiServiceRegistry`: `WebClient.Builder` 주입 → `@Qualifier("sharedBankWebClient")` WebClient 주입  
`WebClientConfig`: `sharedBankWebClient` bean 추가 (maxConnections=2000, pendingAcquireMaxCount=10000)
