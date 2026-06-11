# v14: Virtual Thread 실험 — blocking 코드 그대로 스레드 천장 제거

> 측정일: 2026-06-11 · mock: realistic (정상 7~13s × 48행 + slow 30s × 2행) · 단일 머신 (게이트웨이 + mock 10샤드 + MySQL + k6 동거)
>
> **질문**: v13에서 확정한 async-threadpool 천장(185 RPM)은 macOS OS 스레드 한계(2,048)가 원인이었다.
> JDK 25 virtual thread로 executor만 교체하면 (blocking 코드 수정 없이) 천장이 사라지는가?

---

## 1. 결론

| 항목 | 값 |
| --- | --- |
| **스레드 천장** | **제거 확인** — OS 스레드 378개로 200 RPM 처리 (platform이면 1,800개 필요) |
| **100% 처리 천장** | **300 RPM** (580건 완료, FAILED 0, timeout 0 — 단 p95 58.8s로 열화) |
| **클린 천장 (p95 ~40s)** | **200 RPM** (401건 100%, p95 42.2s) |
| **새 병목** | 스레드 → ① WebClient 커넥션 풀(2000=222 RPM 상당) → ② mock/호스트 용량 순으로 이동 |
| platform(v13) 대비 | 185 → 300 RPM (100% 기준), **코드 변경은 executor 한 줄** |

## 2. 구현 (변경 최소화)

`AsyncExecutionConfig.bankAsyncExecutor()`에 플래그 분기 추가 — `--app.async-thread-pool.virtual=true`면
`Executors.newThreadPerTaskExecutor(가상 스레드 팩토리)` 사용. 기존 `@Async` 경로·blocking 호출(`runBlocking` + `block()`)은 **무수정**.
pool/queue 설정은 virtual 모드에서 무의미(태스크당 가상 스레드 1개, 큐 없음).

함께 추가된 설정 property:
- `app.web-client-fan-out.max-connections` (기본 2000) — 공유 WebClient 커넥션 풀, 하드코딩에서 분리
- `app.web-client-fan-out.pending-acquire-max-count` (기본 10000)

## 3. 측정 결과

| conn | RPM | iters | COMPLETED | timeout | checks | e2e p95 | 은행콜 실패 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2000 | 200 | 394 | 401 | 0% | 100% | **42.2s** | 0 |
| 2000 | 300 | 507 | 511 | 0% | 100% | 66.3s | 360 (premature close, slow샤드 집중) |
| 5000 | 300 | 573 | 580 | 0% | 100% | 58.8s | 178 |
| 5000 | 400 | 405 | 580 | 0% | 100% | 86.7s ⚠️ | 3,136 |
| 5000 | 600 | 347 | 614 | **5.8%** | 98.0% | 112.5s ❌ | 3,471 (**10샤드 균등 분포**) |

부하 중 OS 스레드 실측: **378개** (200 RPM, platform 모드라면 ~1,900개 필요한 부하).
가상 스레드가 블로킹 대기 시 캐리어에서 unmount되는 효과가 그대로 측정됨. macOS `kern.num_taskthreads=2048` 무의미해짐.

## 4. 병목 이동의 연쇄 (이 프로젝트의 핵심 서사)

```
v6  : 은행별 WebClient 기본 풀(~16/은행)  ← pool 증설 효과를 가림 (v12에서 해소)
v13 : OS 스레드 한계 (pool 1700 = 185 RPM) ← virtual thread로 해소 (v14)
v14 : ① WebClient maxConnections 2000 (≈222 RPM 상당) — 5000으로 완화
      ② mock/호스트 용량 (300 RPM부터 열화, 400+에서 커넥션 에러 폭증)
```

- 300 RPM 열화의 분해: conn 2000→5000으로 p95 66.3→58.8s (커넥션 몫), 잔여 +27s는 mock/호스트 몫
- 600 RPM 실패는 10개 샤드에 **균등 분포** → slow 은행 샤드 단독 문제가 아니라 단일 머신 인프라 전체 포화
- 따라서 coroutine의 "600 RPM"(v12)과 VT의 "300 RPM"은 같은 인프라 한계 위의 수치 — 모드 간 정밀 비교는 mock 분리 배치 후에만 유효

## 5. 시사점

1. **"blocking 코드 + virtual thread"는 실전 선택지다** — 코드 한 줄로 platform 한계(185)를 넘어 300 RPM 100% 처리.
   기존 동기 코드베이스를 가진 팀이라면 coroutine 전환 없이 대부분의 이득을 회수.
2. **스레드를 없애면 커넥션 풀이 다음 천장** — 동시 커넥션 수요 = 9×RPM은 동일하므로
   `maxConnections`를 수요 이상으로 키워야 함 (이번에 property화).
3. **virtual 모드에선 pool/queue 튜닝이 사라진다** — v13에서 공들인 1700/200 같은 숫자가 무의미해지고,
   backpressure는 커넥션 풀과 (필요시) 세마포어로 옮겨가야 함. 현재 구현엔 인입 제한이 없으므로
   운영 투입 시 rate limiter 또는 `Semaphore(N)` 추가 권장.
4. Dispatchers.IO 한계(v13 발견)는 virtual 모드에도 그대로 적용 — `-Dkotlinx.coroutines.io.parallelism=512` 유지 필요
   (run당 IO 워커 1개가 join()에 묶이는 구조는 동일).

## 6. 재현

```bash
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=512" \
EXTRA_ARGS="--app.async-thread-pool.virtual=true --server.tomcat.threads.max=50 --app.web-client-fan-out.max-connections=5000" \
perf/k6/load_sweep.sh 200 300
```

## 7. 남은 과제 (v15 후보)

1. mock 함대를 별도 머신에 두고 VT vs coroutine vs webclient 정면 비교 (현재는 인프라 포화가 600 RPM 부근을 오염)
2. virtual 모드 인입 backpressure (세마포어/rate limit) 설계
3. OpenFeign 모드 추가 검증 — 예측: blocking 모델이라 천장 동일, 단 Apache HC5 기본 풀(maxTotal 25/route 5)이면 한 자릿수 RPM에서 막힘
