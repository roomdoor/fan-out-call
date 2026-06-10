# v13: async-threadpool 최적 core/max/queue 탐색 — 최종 보고서

> 측정일: 2026-06-11 · mock: realistic (정상 7~13s × 48行 + slow 30s × 2行) · 게이트웨이 단일 파드 (macOS, JDK 25)
>
> **목표**: async-threadpool 모드에서 "가장 많은 트래픽을 안전하게 100% 처리"하는 core/max/queue 조합 확정

---

## 1. 최종 결론

| 항목 | 값 |
| --- | --- |
| **권장 설정** | `core=1700, max=1700, queue=200` + `-Dkotlinx.coroutines.io.parallelism=192` + Tomcat threads 50 |
| **실측 안전 천장** | **185 RPM** (4분 지속, 100% COMPLETED, FAILED 0, 거부 0) |
| **처리량 한계** | ~195 RPM (100% 완료되나 p95 42s, 여유 0%) — 200 RPM부터 큐 지연 누적 |
| **권장 운영 SLA (마진 30%)** | **분당 130건/파드** |
| 기존(v6) 결론 대비 | 천장 60 → 185 RPM, **3.1배** |

elastic(c=850/m=1700/q=200)도 동일 성능 — 운영상 단순한 fixed(c=max)를 권장.

## 2. 천장 공식: RPM ≈ pool ÷ 9 (Little's Law)

요청 1건 = 은행 50곳 fan-out, 호출 1건 = 풀 스레드 1개를 응답까지 블로킹 점유.
요청당 스레드 점유 총량 = 48×10s + 2×30s = **540 thread-sec** → 정상상태 스레드 수요 = (RPM/60)×540 = **9×RPM**.

| pool | 이론 천장 (pool÷9) | 실측 |
| --- | --- | --- |
| 512 | ~57 | 60 PASS / 80 FAIL ✓ |
| 1024 | ~114 | 100 PASS / 120 FAIL ✓ |
| 1700 | ~189 | 185 PASS(4m) / 200 열화 ✓ |

**pool 크기가 곧 천장이다.** 단, 아래 두 개의 숨은 한계를 먼저 풀어야 공식대로 스케일된다.

## 3. 무효화된 기존 결론 (v6)

v6 매트릭스의 "pool 512→1024 개선 0%"는 **공유 WebClient 도입(efb08eb, v12) 이전** 측정.
당시 은행별 개별 WebClient의 기본 커넥션 풀(~코어수×2)이 병목이라 pool 효과가 가려졌다.
v13에서 같은 1024 설정이 RPM 100을 깨끗하게 통과(v6: timeout 발생)하며 무효 확정.

> 교훈: **빌드 jar 날짜가 마지막 커밋보다 오래됐는지 항상 확인할 것.** v13 시작 시점의 jar(5/31)에는 v12 수정(6/2)이 미포함이었다.

## 4. 두 번째 숨은 천장: Dispatchers.IO 교착 (≈124 RPM)

pool=1792 @ 180 RPM 첫 시도에서 **완료 0건 / 에러 0건**의 전면 교착 발생. 원인:

1. `LoanLimitQueryOrchestrator.backgroundScope`가 `Dispatchers.IO`(기본 **64 워커**)에서 run 실행
2. `AsyncThreadPoolBankFanOutExecutor`가 `CompletableFuture.allOf().join()`으로 IO 워커를 run 종료까지(~31s) **하드 블로킹**
3. 결과 저장 `persistResultWithRetry`가 `withContext(Dispatchers.IO)` 필요
4. 동시 run ≥ 64 (= 64÷31s ≈ **124 RPM**)이면 IO 워커 전원이 join()에 묶임 → 저장 불가 → join() 영원히 미반환 → **데드락**

증상 대조: 1024@120의 timeout 35%는 동시 run 62 ≈ 64 경계와 정확히 일치.

**측정용 해법(적용)**: `-Dkotlinx.coroutines.io.parallelism=192` (코드 무수정)
**구조적 교정안(권장)**: executor의 `join()` → `await()` (suspending 대기로 IO 워커 비점유). 적용 시 JVM 플래그 불필요.

## 5. 스레드 예산 (macOS kern.num_taskthreads = 2,048)

실측 오버헤드: 총 1,143 = pool 1,024 + **119** (Tomcat 20 사용 시).

```
pool 1700 + IO워커 ~100(동시 run 수만큼 join 블로킹) + Tomcat 50(캡) + JVM 내부 ~70 ≈ 1,920 < 2,048
```

- pool=1792 + Tomcat 기본(200 증식 가능)은 한계 초과 위험 → **pool 1700 + `--server.tomcat.threads.max=50`**이 이 머신의 안전 상한
- 폴링 부하(~50 req/s)에 Tomcat 50은 충분

## 6. queue: 천장을 바꾸지 못한다 — 작게 가져가라

| 구성 | RPM 185 결과 |
| --- | --- |
| q=2048 (4m) | 100% 완료, p95 39.5s / max 43.8s (큐 체류 지터 — 라운드 간 분산 가능성 있음) |
| **q=200 (4m)** | 100% 완료, **p95 32.1s / max 32.4s** (이론 하한 31s에 밀착), 거부 0 |
| elastic c=850/m=1700/q=200 (2m) | 동일 (p95 32.1s) |

- 천장 아래에서 큐는 거의 빈다 → 큰 큐는 무의미
- 천장 초과 시: 큰 큐(2048)는 다 받아주다 **전원 timeout**(p95 90s), 작은 큐(200)는 **즉시 거부(fail-fast)** → 받은 건은 100% SLA 내 완료
- elastic은 큐가 가득 차야 core 초과 증설이 일어나므로 **elastic을 쓰려면 작은 큐가 전제**. 성능 동일하니 fixed 권장

## 7. 전체 측정 데이터

| 구성 | RPM | iters | timeout | checks | e2e p95 |
| --- | --- | --- | --- | --- | --- |
| pool512-q2048 | 60 | 114 | 0.0% | 100% | 37.0s |
| pool512-q2048 | 80 | 78 | 21.8% | 93.7% | 92.1s |
| pool512-q2048 | 100 | 98 | 78.6% | 75.1% | 92.0s |
| pool1024-q2048 | 80 | 158 | 0.0% | 100% | 32.1s |
| pool1024-q2048 | 100 | 198 | 0.0% | 100% | 32.2s |
| pool1024-q2048 | 120 | 117 | 35.0% | 89.6% | 92.2s |
| pool1792-q2048 (IO=64) | 180 | — | 100% | 67.3% | **교착, 완료 0건** (무효) |
| pool1700-io192-q2048 | 180 | 355 | 0.0% | 100% | 35.2s |
| pool1700-io192-q2048 | 190 | 375 | 0.0% | 100% | 33.6s |
| pool1700-io192-q2048 | 200 | 377 | 0.0% | 100% | 38.1s ↑열화 |
| pool1700-io192-q2048 (4m) | 185 | 734 | 0.0% | 100% | 39.5s |
| pool1700-io192-q2048 (4m) | 195 | 741 | 0.0% | 100% | 42.0s |
| **pool1700-io192-q200** | **185** | 365 | 0.0% | 100% | **32.1s** |
| **pool1700-io192-q200 (4m)** | **185** | **735** | **0.0%** | **100%** | **32.1s (max 32.4s)** |
| elastic-c850-m1700-q200 | 185 | 365 | 0.0% | 100% | 32.1s |

## 8. 재현 방법

```bash
# 최종 권장 구성으로 4분 검증
CORE_POOL=1700 MAX_POOL=1700 QUEUE=200 DURATION=4m \
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=192" \
EXTRA_ARGS="--server.tomcat.threads.max=50" \
perf/k6/load_sweep.sh 185
```

`load_sweep.sh`에 v13에서 추가된 환경변수:
- `JAVA_OPTS` — jar 앞 JVM 플래그 (`-D...`)
- `EXTRA_ARGS` — jar 뒤 Spring 인자 (`--server.tomcat.threads.max=50` 등)

## 9. 남은 과제

1. **`join()` → `await()` 코드 교정** 후 IO 플래그 없이 재검증 (구조적 해결)
2. e2e p95에는 k6 폴링 주기(POLL_MAX_MS=5s) 양자화가 포함됨 — 게이트웨이 내부 큐 대기를 직접 보려면 executor 메트릭(actuator) 노출 필요
3. 이 천장은 macOS 스레드 한계 기준 — Linux 파드에서는 pool을 더 키울 수 있으나, 스레드당 메모리(~1MB stack)와 컨텍스트 스위칭 비용 때문에 **동일 하드웨어라면 coroutine 모드(600 RPM, v12)가 구조적으로 우위**
