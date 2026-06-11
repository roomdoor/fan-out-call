# Fan-out 부하 테스트 HANDOFF

이 문서는 다른 머신에서 작업을 이어받기 위한 컨텍스트 요약입니다. Claude Code 세션 자체는 머신 간 이동 불가하므로 이 문서로 인계.

---

## 1. 프로젝트 개요

**목적**: Spring Boot 4 + Kotlin 게이트웨이가 50개 외부 금융사 API를 fan-out 호출하는 구조의 단일 파드 처리 한계와 최적 ThreadPoolExecutor 설정 정량 측정.

**두 저장소 (이미 푸시 완료)**

| 역할 | GitHub | 로컬 경로 (기존 머신) |
| --- | --- | --- |
| 게이트웨이 (테스트 대상) | `git@github.com:roomdoor/fan-out-call.git` | `~/workspace/fan-out-k6-test/fan-out-call` |
| 가짜 금융사 mock | `git@github.com:roomdoor/fan-out-api-mock-server.git` | `~/workspace/fan-out-api-mock-server` |

---

## 2. 새 머신 셋업 (한 번)

### 사전 설치
- **JDK 25** (게이트웨이용, Spring Boot 4 + JDK 25 toolchain)
- **JDK 17** (mock 서버용 — Gradle toolchain auto-install도 가능)
- **Docker Desktop**
- **k6** (`brew install k6` on macOS)
- **Node.js** (파서 스크립트용)

### 클론
```bash
cd ~/workspace   # 또는 원하는 위치
git clone git@github.com:roomdoor/fan-out-call.git
git clone git@github.com:roomdoor/fan-out-api-mock-server.git
```

### 환경변수 (필수)
```bash
export MOCK_SERVER_DIR=~/workspace/fan-out-api-mock-server
```
쉘 rc에 박아두면 편함. 안 박으면 `harness.sh` 등에서 에러.

---

## 3. 인프라 기동 순서

### 3.1 MySQL (게이트웨이 docker-compose)
```bash
cd ~/workspace/fan-out-call
docker compose up -d mysql
# 헬스: docker exec loan-limit-gateway-mysql mysqladmin ping -uroot -proot
```

### 3.2 Mock fleet (10 샤드, 포트 18000~18009)
```bash
cd "$MOCK_SERVER_DIR"
./gradlew installDist             # 첫 번째 실행시만 필요 (Dockerfile이 build/install 참조)

cd "$MOCK_SERVER_DIR/perf"
set -a && source realistic.env && set +a   # 또는 baseline.env
docker compose up -d --build

# 헬스 체크 (10개 포트)
for p in 18000 18001 18002 18003 18004 18005 18006 18007 18008 18009; do
  curl -sf http://localhost:$p/health >/dev/null && echo "$p OK" || echo "$p FAIL"
done
```

### 3.3 게이트웨이 빌드 (한 번)
```bash
cd ~/workspace/fan-out-call
./gradlew bootJar
# 산출: build/libs/loan-limit-gateway-*.jar (Spring Boot 4 첫 빌드는 1~2분)
```

---

## 4. 테스트 실행 방법

모든 sweep은 게이트웨이를 매 회차 재기동 (clean state). MySQL과 mock fleet은 한 번 띄워두면 됨.

### 4.1 Pool × Queue sweep (sweep.sh)
```bash
cd ~/workspace/fan-out-call

# pool:queue 페어 (콜론 구분)
perf/k6/sweep.sh 64:256 128:512 256:1024 512:2048

# 단일 pool에 queue 환경변수
QUEUE=200 perf/k6/sweep.sh 64 128 256 512
```

### 4.2 RPM 부하 sweep (load_sweep.sh)
```bash
# 기본 (RPMS: 20 30 40 50 60 70 80 90 100, pool=512/q=2048)
perf/k6/load_sweep.sh

# 사용자 지정 RPMs
perf/k6/load_sweep.sh 40 60 80 100

# core/max/queue 지정
CORE_POOL=200 MAX_POOL=1000 QUEUE=200 perf/k6/load_sweep.sh 60 80

# 폴링 타임아웃 늘리기 (실재 지연 환경에서 e2e > 60s 가능)
MAX_WAIT_MS=120000 perf/k6/load_sweep.sh ...

# 각 회차 길이 변경
DURATION=5m perf/k6/load_sweep.sh ...
```

### 4.3 Matrix sweep (pool × RPM)
```bash
# 기본: configs=(512:2048 1024:2048 2048:2048), RPMs=(40 50 60 70 80 90 100)
perf/k6/matrix_sweep.sh

# 커스텀
CONFIGS="256:200 512:200" RPMS="40 60 80" perf/k6/matrix_sweep.sh
```

### 4.4 결과 파싱
```bash
node perf/k6/load_parse.mjs         # RPM sweep용 (rpm_*.json 읽음)
node perf/k6/matrix_parse.mjs        # matrix용 (matrix-pool*-q*/ 읽음)
node perf/k6/parse.mjs               # pool/queue sweep용 (pool_*.json 읽음)
```

---

## 5. 현재까지 한 일 / 핵심 수치

### Mock 환경
- **baseline.env**: 모든 은행 고정 500ms 응답 (v1, v2에서 사용)
- **realistic.env**: 정상 7~13s · slow 30s × 2개 (v3 이후 사용, 실서비스 가까움) ⭐ **현재 띄워둔 프로파일**

### 결과 디렉토리 (perf/k6/results/, 모두 git에 커밋됨)

| 디렉토리 | 내용 |
| --- | --- |
| `v1-pool-only/` | mock 500ms, pool 64/128/256/512, queue=200 |
| `v2-pool-queue-scaled/` | mock 500ms, pool×queue 4배 (320~2560 슬롯) |
| `v3-realistic-stress50rps/` | realistic mock, k6 stress (1~50 RPS) |
| `v4-pool512-q2048-load20-100/` | realistic mock, RPM 20~100 sweep, pool=512/q=2048 |
| `v5-c200-m500-q200-load20-100/` | asymmetric pool (core=200/max=500) |
| `v6-matrix-pool-512-1024-2048/` | 3 pool × 7 RPM matrix (MATRIX_REPORT.md 포함) |
| `v7-elastic-vs-fixed/` | queue=200 고정, c=512/m=1024 vs c=m=1024 비교 |
| `v8-hikari-10-vs-50/` | Hikari pool 10 vs 50 비교 (RPM 60/80) — **Hikari는 병목 아님** |
| `v9-webclient-rpm60-100/` | webclient(WebFlux) 모드 RPM 60/80/100 — **100 RPM까지 FAILED 0건** |
| `v10-webclient-ceiling/` | webclient 천장 탐색 RPM 120~700 — **안전 한계 400 RPM, 한계 초과 시 PARTIAL(graceful)** |
| `v11-coroutine-ceiling/` | coroutine 모드 RPM 100~700 — **결과 무효**: Reactor Netty 커넥션 풀 버그(pending queue 포화) |
| `v12-coroutine-fixed/` | coroutine 공유 WebClient 수정 후 재테스트 — **안전 한계 600 RPM**, webclient(400) 대비 1.5× 우위 |
| `v13-asyncpool-optimal/` | **async-threadpool 최적 core/max/queue 확정** — c=1700/m=1700/q=200 + IO=192, 안전 천장 **185 RPM** (기존 60의 3.1배). REPORT.md 필독 |
| `v14-virtual-threads/` | **virtual thread 실험** — executor 한 줄 교체로 스레드 천장 제거 (OS 스레드 378개로 200 RPM). 100% 처리 천장 **300 RPM**, 새 병목은 WebClient 커넥션 풀 → mock/호스트 용량. REPORT.md 참조 |

### 핵심 발견 (정량)

> ⚠️ 아래 표는 v6 시점(개별 WebClient + Dispatchers.IO 64 한계) 수치. **v13에서 갱신됨** — async-threadpool 천장 60 → **185 RPM** (c=1700/m=1700/q=200 + IO=192). 최신 수치는 `results/v13-asyncpool-optimal/REPORT.md` 참조.

| 항목 | 수치 (v6 당시) | v13 갱신 |
| --- | --- | --- |
| **단일 파드 안전 처리량** | 60 RPM | **185 RPM** (4분 지속 100% COMPLETED) |
| **천장 공식** | (모름 — pool 증설 무효과로 보임) | **RPM ≈ pool ÷ 9** (요청당 540 thread-sec) |
| **이론치 최소 e2e** | 30s (= slowest bank) | 동일 — q=200 구성 p95 32.1s |
| **권장 SLA (마진 30%)** | 분당 42건/파드 | **분당 130건/파드** |

### 핵심 발견 (정성)

1. **pool 크기 증가의 수확 체감점이 명확** — pool 512 → 1024로 2× 늘려도 처리량 0% 개선
2. **Elastic vs Fixed pool 동일** — c=512/m=1024 ≈ c=m=1024 (constant-arrival-rate 부하에서)
3. **pool=2048은 macOS 한계 초과** — `kern.num_taskthreads=2,048` 때문에 OOM 43건. ulimit 안 풀면 테스트 불가
4. **무너지는 두 가지 패턴**:
   - 큰 queue (2048): 받아주고 timeout (p95 90s에서 fail)
   - 작은 queue (200): 즉시 fail-fast (RejectedExecution)
5. **실서비스 구성 (c=200/m=1000/q=200)**도 슬롯 총량(1,200) 거의 같아 동일 천장(60 RPM) 예상

---

## 6. 완료된 검증 / 다음 단계

### v8: Hikari 가설 검증 ✅ 완료 (2026-06-01)

**결론: Hikari는 병목이 아니다.**

Hikari=10 vs Hikari=50 모두 RPM 60 → 116 COMPLETED / 0 FAILED, RPM 80 → 53 COMPLETED / 107 FAILED 로 **완전히 동일**.
실패 원인은 그대로 `RejectedExecutionException (pool=1024 full, queue=200 full)`.

`load_sweep.sh`에 `HIKARI_MAX_POOL` 환경변수 지원 추가됨. 기본값 10, 사용법:
```bash
CORE_POOL=512 MAX_POOL=1024 QUEUE=200 HIKARI_MAX_POOL=50 perf/k6/load_sweep.sh 60 80
```

### v9: webclient(WebFlux) 모드 ✅ 완료 (2026-06-01)

**결론: WebFlux가 async-threadpool 대비 압도적 우위.**

| RPM | async-threadpool | webclient |
|-----|-----------------|-----------|
| 60  | 116 COMPLETED / 0 FAILED | 120 COMPLETED / 0 FAILED |
| 80  | 53 COMPLETED / 107 FAILED | **161 COMPLETED / 0 FAILED** |
| 100 | 35 COMPLETED / 165 FAILED | **201 COMPLETED / 0 FAILED** |

Reactor non-blocking I/O로 thread 점유 없이 처리 → RPM 100까지 FAILED 0건. e2e p95도 31~32s로 단축.

### 다음 단계

1. **webclient 천장 탐색**: RPM 120, 150, 200
   ```bash
   CORE_POOL=512 MAX_POOL=1024 QUEUE=200 MODE=webclient \
     DURATION=2m MAX_WAIT_MS=120000 perf/k6/load_sweep.sh 120 150 200
   ```

2. **coroutine 모드 재테스트 (커넥션 풀 수정 후)**:
   - 현재 coroutine 모드는 은행 50개 각자 개별 WebClient 풀 사용 → 고RPM에서 `Pending acquire queue` 포화
   - `WebClientConfig`에서 커넥션 풀 한도 늘리거나(Option A), 공유 WebClient 주입(Option B) 수정 필요
   - ✅ v12 완료: 공유 WebClient 수정 후 coroutine 안전 한계 **600 RPM** 확인 (webclient 400 대비 1.5×)

### v13: async-threadpool 최적 core/max/queue ✅ 완료 (2026-06-11)

**결론: `core=1700, max=1700, queue=200` + `-Dkotlinx.coroutines.io.parallelism=192` + Tomcat 50 → 안전 천장 185 RPM** (상세: `results/v13-asyncpool-optimal/REPORT.md`)

핵심 발견 4가지:
1. **v6 "pool 늘려도 개선 0%" 결론은 무효** — v12 공유 WebClient 수정 이전 측정이었음. 수정 후엔 천장 = pool÷9 (Little's Law, 요청당 540 thread-sec)로 정직하게 스케일
2. **Dispatchers.IO(기본 64워커) 교착 발견** — orchestrator의 backgroundScope(IO) 위에서 executor가 `allOf().join()` 하드 블로킹 + 결과 저장도 IO 필요 → 동시 run ≥ 64(≈124 RPM)에서 완료 0건 전면 교착. `-Dkotlinx.coroutines.io.parallelism=192`로 해소. 구조적 교정은 join()→await()
3. **스레드 예산**: macOS 한계 2,048 = pool 1700 + IO ~100 + Tomcat 50(캡 필수) + JVM ~70. pool 1792는 위험
4. **queue는 천장을 못 바꿈** — 천장 아래선 큐가 거의 빔. q=200이 q=2048보다 p95 우수(32.1s vs 39.5s) + 과부하 시 fail-fast. elastic(c=850/m=1700/q=200)은 fixed와 동일 성능

`load_sweep.sh`에 `JAVA_OPTS`(jar 앞 JVM 플래그), `EXTRA_ARGS`(jar 뒤 Spring 인자) 지원 추가됨.

### v14: virtual thread 실험 ✅ 완료 (2026-06-11)

**결론: blocking 코드 그대로 executor만 virtual thread로 교체 → 스레드 천장 제거.** (상세: `results/v14-virtual-threads/REPORT.md`)

- `--app.async-thread-pool.virtual=true` 플래그 추가 (AsyncExecutionConfig 분기, 기존 경로 무수정)
- OS 스레드 378개로 200 RPM 처리 (platform이면 ~1,900개 필요) → macOS 2,048 한계 무의미
- 100% 처리 천장 **300 RPM** (p95 58.8s 열화), 클린 천장 200 RPM (p95 42.2s)
- 병목 이동: 스레드 → WebClient maxConnections(2000≈222 RPM 상당, property화하여 5000으로 완화) → mock/호스트 포화 (400+에서 커넥션 에러, 600에서 10샤드 균등 실패)
- `app.web-client-fan-out.max-connections` / `pending-acquire-max-count` property 추가
- 주의: virtual 모드에도 Dispatchers.IO 한계는 그대로 → `-Dkotlinx.coroutines.io.parallelism=512` 필요

### 다음 단계 (v15 후보)

1. **join() → await() 코드 교정** 후 IO 플래그 없이 재검증
2. mock 함대 별도 머신 분리 후 VT vs coroutine vs webclient 정면 비교 (현재 600 RPM 부근은 단일 머신 포화로 오염)
3. virtual 모드 인입 backpressure (세마포어/rate limit) 설계
4. OpenFeign 모드 추가 — 예측: blocking 모델이라 천장 동일, Apache HC5 기본 풀(25/5)이면 한 자릿수 RPM에서 막힘
5. executor 큐 깊이/활성 스레드 actuator 메트릭 노출, Linux 파드에서 pool > 1700 영역 확인

---

## 7. 자주 쓰는 명령 모음

```bash
# === 인프라 ===
docker compose up -d mysql                                    # gateway repo에서
(cd "$MOCK_SERVER_DIR/perf" && docker compose up -d --build)  # mock fleet (env 미리 source)
docker compose down                                            # 정리

# === 빌드 ===
./gradlew bootJar                              # gateway
(cd "$MOCK_SERVER_DIR" && ./gradlew installDist)  # mock

# === 헬스 체크 ===
curl -sf http://localhost:8080/actuator/health    # gateway
for p in 18000 18001 18005 18009; do curl -sf http://localhost:$p/health && echo OK; done

# === 로그 보기 ===
tail -f perf/k6/logs/gateway_rpm_60.log

# === 게이트웨이 강제 종료 ===
lsof -ti tcp:8080 | xargs kill

# === COMPLETED/FAILED 카운트 (특정 RPM 로그) ===
grep -c "status=COMPLETED" perf/k6/logs/gateway_rpm_80.log
grep -c "Run marked as FAILED" perf/k6/logs/gateway_rpm_80.log
```

---

## 8. 게이트웨이 properties 오버라이드 참조

CLI 인자로 변경 가능 (재빌드 불필요):

```
--app.async-thread-pool.core-pool-size=200
--app.async-thread-pool.max-pool-size=1000
--app.async-thread-pool.queue-capacity=200
--app.web-client-fan-out.routing-mode=sharded     # 또는 single
--app.banks.per-call-timeout-ms=50000
--app.banks.required-completion-ms=60000
--app.banks.parallelism=50
--spring.datasource.hikari.maximum-pool-size=10   # 기본값 10
```

---

## 9. k6 환경변수

```
MODE=async-threadpool                # coroutine | async-threadpool | webclient
BASE_URL=http://localhost:8080
LOAD_RPM=60                          # load.js 전용
DURATION=2m                          # load.js 전용
MAX_WAIT_MS=90000                    # 폴링 timeout
POLL_MAX_MS=5000
```

---

## 10. 새 세션 시작할 때 권장

1. 이 파일을 새 Claude 세션에 첫 메시지로 첨부하거나 `cat`으로 보여주기
2. "여기까지 했고, 다음은 Hikari 가설 검증이다. 옵션 A로 진행해줘" 같이 명확히 지시
3. `docker compose ps`로 인프라 떠있는지부터 확인 (새 머신은 다 내려가있을 것)
