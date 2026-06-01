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

### 핵심 발견 (정량)

| 항목 | 수치 |
| --- | --- |
| **단일 파드 안전 처리량** | **60 RPM** (113건 시도 → 112 COMPLETED, 0 FAILED, e2e p95 31.6s) |
| **80 RPM에서** | 161건 받지만 **53 COMPLETED / 108 FAILED** (RejectedExecutionException, fail-fast) |
| **100 RPM에서** | 35 COMPLETED / 165 FAILED (82% 거부) |
| **이론치 최소 e2e** | 30s (= slowest bank), 측정 p95와 일치 → 게이트웨이 오버헤드 무시 가능 |
| **권장 SLA (마진 30%)** | 분당 **42건/파드** |

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

### 다음 가설: coroutine / webclient 모드 비교

`async-threadpool` 모드는 bank call 1개 = thread 1개 점유. RPM 80 × 50 banks = 4,000개 thread 슬롯 필요 → pool+queue=1,224으로 턱없이 부족.

- **coroutine 모드**: Kotlin 코루틴 + Semaphore로 thread를 점유하지 않고 I/O 대기
- **webclient 모드**: Reactor non-blocking I/O

```bash
# coroutine 모드 테스트
CORE_POOL=512 MAX_POOL=1024 QUEUE=200 MODE=coroutine \
  perf/k6/load_sweep.sh 60 80 100

# webclient 모드 테스트
CORE_POOL=512 MAX_POOL=1024 QUEUE=200 MODE=webclient \
  perf/k6/load_sweep.sh 60 80 100
```

**기대**: coroutine/webclient 모드에서 RPM 80 이상에서도 COMPLETED 증가 확인 시 → 모드별 성능 천장 비교 가능

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
