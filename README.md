# Loan Limit Gateway — 50개 외부 금융사 fan-out 호출 성능 측정

Spring Boot 4 + Kotlin 기반의 대출 한도 조회 게이트웨이.
한 트랜잭션이 50개 외부 금융사 API를 병렬 호출(fan-out)하고 결과를 종합·저장한다.

**목적**: 동일한 비즈니스 로직을 **4가지 동시성 모델**로 구현하고, 같은 부하에서 **무엇이 처리 한계를 정하는지** 실측한다.

mock 서버: [`roomdoor/fan-out-api-mock-server`](https://github.com/roomdoor/fan-out-api-mock-server) (Ktor, 10 샤드)

측정 환경: AWS 3호스트 — 게이트웨이+MySQL(c7i.2xlarge), mock(m7i.xlarge), k6(c7i.2xlarge). 같은 AZ.

---

## 요약

**블로킹 모델의 천장은 스레드 수가 정한다. 논블로킹은 그 제약이 없다.**

| 모드 | 스레드 | 480 RPM 결과 | 지연이 유지되는 한계 |
| --- | --- | --- | --- |
| **async-threadpool** | 풀 **4,096** | 은행콜 3,409건 거부, 처리율 338.8/분 | 480에서 이미 포화 |
| **coroutine** | IO 디스패처 **512** | 거부 0, 처리율 480.3/분 | **1,600 RPM** |
| **webclient** | 이벤트 루프 + 저장만 IO **512** | 거부 0, 처리율 480.3/분 | **1,600 RPM** |
| sequential | 1 | — | anti-pattern 시연용 |

**스레드를 9분의 1만 쓰고 부하를 전부 받아냈다.** async-threadpool 쪽은 풀 4,096에 더해 IO 워커 512를 함께 받았다(4,608 대 512).

숫자보다 중요한 건 **대기 중에 스레드를 붙잡느냐**다. async-threadpool 은 `AsyncBankCallWorker` 의 `runBlocking` 이 응답이 올 때까지 풀 스레드를 점유한다. coroutine 도 은행 호출을 `async(Dispatchers.IO)` 로 띄우지만 `awaitSingle()` 에서 suspend 하므로, 기다리는 동안 워커를 풀에 돌려준다. 그래서 512로 4,608보다 많이 처리한다.

거부한 쪽의 게이트웨이 CPU가 16%였다 — 자원이 없어서 거부한 게 아니다.

논블로킹 두 모드는 1,800 RPM에서도 7,200건을 **전부 처리했다.** 거부가 아니라 지연이 늘었을 뿐이다(31.4초 → 56.3 / 44.6초). 위 표의 1,600은 "이론 하한 지연을 유지하는" 한계다.

> mock 고정 지연(정상 48개 7~13초, slow 2개 30초, 성공률 100%) 기반의 상대 비교다. 실제 금융사 성능이 아니다.

---

## 1. 스레드 풀 천장은 `pool ÷ 9`

요청 1건이 스레드를 얼마나 쓰는지 세면 나온다.

```
48개 은행 × 10초  = 480 스레드초
 2개 은행 × 30초  =  60 스레드초
────────────────────────────────
                    540 스레드초 = 9 스레드분
```

블로킹 호출은 응답을 기다리는 내내 스레드를 붙잡는다. 그래서 풀 N개가 1분에 공급하는 N 스레드분을 9로 나눈 값이 한계다.

**512에서 4096까지 확인했다.**

| pool | 계산 천장 | 깨끗한 점 | 막힌 점 | 거부된 은행콜 |
| --- | --- | --- | --- | --- |
| 512 | 57 | 30 ✅ | 60 | 317 |
| 1024 | 114 | 60 ✅ | 120 | 740 |
| 2048 | 228 | 120 ✅ | 240 | 1,641 |
| 4096 | 455 | 240 ✅ | 480 | 3,409 |

**각 풀에서 막힌 RPM이 다음 풀에서는 정확히 0이다.** 세 번 연속 그랬다. 같은 60 RPM이 pool 512에서는 317건 거부, pool 1024에서는 0건이다.

천장 값 자체(57/114/228/455)는 계산이고 재지 않았다. 잰 것은 위의 구간이다.

원본: [`perf/k6/results/v15-pool512`](perf/k6/results/v15-pool512) 외 3개.

---

## 2. 논블로킹은 다른 자리에서 꺾인다

IO 워커를 512로 고정하고 RPM만 올리며 e2e p95를 봤다. 이론 하한은 31초(slow 은행 30초 + 오버헤드).

| RPM | coroutine | webclient |
| --- | --- | --- |
| 480 | 31,396ms | 31,392ms |
| 960 | 31,409ms | 31,401ms |
| 1200 | 31,425ms | 31,418ms |
| 1400 | 31,533ms | 31,471ms |
| 1600 | 32,044ms | 31,632ms |
| **1800** | **56,344ms** | **44,550ms** |

**1600까지 이론 하한에 붙어 있다가 1800에서 꺾인다.** 전 구간 거부 0이다.

> **이 표는 지연이 아니라 폴링 눈금을 읽은 값에 가깝다.** `e2e` 는 k6 가 폴링으로 확인한 시각이고 간격이 5초에서 멈춘다 — 1600 까지는 모든 트랜잭션이 같은(15번째) 폴에서 끝나므로 31,396 과 31,392 의 차이는 왕복 시간 차이지 처리 시간 차이가 아니다. 1800 행은 눈금 자체가 무너진 구간이다. 방법론의 [회차마다 게이트웨이를 재기동한다](#회차마다-게이트웨이를-재기동한다--워밍업-영향은-안-쟀다) 참고.

**포화를 표현하는 방식이 다르다.**

| | 포화 신호 | 받은 요청은 |
| --- | --- | --- |
| async-threadpool | 즉시 거부 (큐 200칸이 차면) | 31~36초에 끝낸다 |
| 논블로킹 | 지연 증가 | 다 받지만 다 늦어진다 |

논블로킹에도 대기열은 있다(`pendingAcquireMaxCount` 10,000 — 이것도 샤드마다라 실질 100,000, 60초 타임아웃). 다만 이번 부하에서는 커넥션 여유가 커서 한 번도 걸리지 않았고, 그래서 거절 없이 지연만 늘었다.

어느 쪽이 나은지는 요구사항이 정한다 — "늦어도 다 처리"면 논블로킹, "빠르거나 거절"이면 큐 있는 쪽이다.

**두 논블로킹 구현은 1600까지 구분되지 않는다.** 1200에서 7ms 차이다. 1800에서 처음 갈린다(webclient가 21% 빠름).

원본: [`v15-coroutine`](perf/k6/results/v15-coroutine) · [`v15-webclient`](perf/k6/results/v15-webclient) · [`v15-nonblocking-knee`](perf/k6/results/v15-nonblocking-knee)

---

## 3. 왜 스레드가 9배 많은데 지는가

**스레드가 노는 게 아니라, 막혀 있으면서 자리를 차지한다.**

480 RPM이면 은행 호출 4,320개가 동시에 떠 있다. 블로킹은 그걸 감당하려면 스레드가 4,320개 필요하다 — **스레드가 "일하는 단위"가 아니라 "떠 있는 요청의 자리표"** 가 된다.

CPU가 증거다.

| | 게이트웨이 CPU |
| --- | --- |
| async-threadpool, 480 RPM에서 3,409건 거부 | 약 16% |
| coroutine, 1,200 RPM 무흠집 | 31% |

CPU가 한참 남는데 거부했다.

> CPU는 CloudWatch(`AWS/EC2 CPUUtilization`)를 측정 시각으로 조회한 값이다. **기본 모니터링이라 5분 단위**인데 회차는 4분이라, 한 데이터포인트에 유휴 구간이 섞인다. 실제 부하 중 CPU는 이 값보다 높다 — **자릿수 비교용이지 정확한 수치가 아니다.** `bench.sh` 도 `parse.mjs` 도 CPU를 기록하지 않으므로 `results/` 에는 없다.

---

## 미해결

**1800 RPM에서 지연이 뛰는 원인을 못 찾았다.**

| 후보 | 검증 |
| --- | --- |
| 커넥션 풀 | 20,000 → 40,000, 지연 1.0% 차이. 애초에 근처도 안 갔다(위 참고) |
| IO 워커 | 512 → 1,024, webclient는 오히려 16% 악화 |
| CPU | k6 5.8% / 게이트웨이 38.4% / mock 20.1% (CloudWatch) |
| **DB 저장** | 초당 1,500건, 게이트웨이와 같은 호스트. **미검증** |
| **은행 호출 타임아웃** | `per-call-timeout-ms: 50000`. 1800에서 e2e 최대가 69초까지 갔고 `conn40k` 의 coroutine 회차에 예외 4건이 났다(24회차 중 예외가 난 유일한 회차). **미검증** |

> **이 표의 검증란을 그대로 믿기 어려운 이유가 둘 있다.**
>
> **측정 해상도.** `e2e` 는 k6 가 폴링으로 확인한 시각이고, 대기 간격이 5초에서 멈춘다. 이 표의 1.0%·16% 와 아래 2.4% 는 전부 **1800 RPM 회차**(p95 44~56초)에서 나온 값인데, 하필 그 구간은 폴 왕복 자체가 평균 1.17초라 간격이 `5초 + 왕복` 으로 벌어지고 부하에 따라 변한다. **5초 격자보다 나쁘다.**
>
> **콜드 JVM.** `bench.sh` 가 회차마다 게이트웨이를 재기동해 **모든 회차가 갓 뜬 JVM 이다.** 워밍업 단계는 없다.
>
> 둘 다 후보를 "지웠다" 는 판단을 약하게 만든다. 방법론의 [회차마다 게이트웨이를 재기동한다](#회차마다-게이트웨이를-재기동한다--워밍업-영향은-안-쟀다) 참고.

DB 쪽을 의심하는 이유는 이렇다. **webclient 는 IO 워커를 결과 저장에만 쓴다** (`mono(Dispatchers.IO) { onEachResult }`). 그래서 워커를 두 배로 올리면 MySQL로 가는 동시 저장이 그대로 두 배가 되고, 실제로 16.1% 느려졌다.

**coroutine 은 같은 변경에서 2.4% 빨라졌다**(55,766ms → 54,419ms). 이쪽은 은행 호출까지 `async(Dispatchers.IO)` 로 띄우므로, 워커가 늘면 저장뿐 아니라 호출 쪽 여유도 같이 늘어 상쇄됐을 수 있다.

설명은 되지만 확인된 건 아니다. n=1 이고, 저장 부하를 따로 떼어 재보지 않았다.

**논블로킹 천장도 못 찾았다.** 1800에서 꺾이지만 거부가 없어 "천장"의 정의가 필요하다. SLA를 정하면(예: e2e p95 45초) 그 지점이 천장이 된다.

**전부 n=1이다.** 커밋된 24회차 모두 `repeat: 1` 이다. pool512는 실제로 두 번 쟀고 결론(30 깨끗 / 60 거부)이 같았지만, 1차 측정 파일이 인스턴스 교체 때 사라져 **레포에서는 확인할 수 없다.**

---

## 방법론

### 숫자를 DB에서 센다

k6 지표로는 실효 처리율을 못 잰다 — 풀에서 거부된 트랜잭션도 빠른 `202` 를 받아 k6에는 성공으로 보인다.

그래서 게이트웨이가 남긴 DB 행을 센다. 은행 호출은 다섯 갈래, 트랜잭션 상태는 여섯 갈래로 나누고, **상태별 합이 전체 run 수와 같은지** 회차마다 검사한다. 어느 칸에도 안 잡히는 상태가 생기면 그 회차를 무효로 표시한다.

### 드레인

k6는 `DURATION` 에서 멈추지만 트랜잭션 하나의 이론 하한이 31초다(거부가 섞이면 그 호출이 즉시 끝나 더 짧아진다). 막바지에 넣은 건들이 끝날 때까지 기다린 뒤에 센다. 짧게 기다리면 처리량이 낮게 나오고, **부하가 셀수록 많이 빠져 천장이 실제보다 낮아 보인다.**

`status='IN_PROGRESS'` 가 0이면 끝이다. 추측하지 않는다.

### 회차마다 게이트웨이를 재기동한다 — 워밍업 영향은 안 쟀다

`bench.sh` 는 회차마다 게이트웨이를 내렸다 올린다. 그러면 **매 회차가 갓 뜬 JVM 을 잰다.** JVM 은 처음에 코드를 해석하다가 자주 도는 부분만 기계어로 컴파일하고, 클래스도 처음 쓸 때 로딩한다.

**한 모드 안에서는 기동 인자가 바뀌지 않는다.** `POOL`·`QUEUE` 는 설정에서 한 번 읽고 `pool_args` 도 모드마다 한 번 계산해 rpm·rep 루프 **밖**에 둔다. 모드 선택은 게이트웨이가 아니라 k6 로 간다(엔드포인트를 고른다). 그래서 **한 모드의 RPM 단계들은 전부 같은 명령줄로 다시 뜬다** — 인자가 필요해서가 아니라 루프 구조가 그렇다. 하네스에 워밍업 단계도 없다.

(모드가 바뀔 때는 달라진다. `pool_args_for()` 가 `async-threadpool` 에만 인자를 붙이므로, `MODES` 에 그 모드와 다른 모드를 같이 넣으면 명령줄이 갈린다. 지금 커밋된 설정 중에 그런 건 없다.)

(`async-threadpool` 은 인자와 무관하게 재기동해야 한다. `core-pool-size = max-pool-size` 라 4,096 스레드가 회차 뒤에도 살아 있어, 안 내리면 다음 회차가 이미 만들어진 풀로 시작한다.)

**이 측정이 워밍업에 오염됐는지는 모른다.** 처음에는 아니라고 적었는데, 근거로 든 것들이 반증이 안 된다. 그리고 **왜 반증이 안 되는지는 폴링 루프에 있다.**

`e2e_completion_time` 은 서버가 잰 값이 아니라 **k6 가 폴링으로 확인한 시각**이다. 대기 간격이 100ms 에서 1.5배씩 늘고 5,000ms 에서 멈춘다. 누적하면 이렇게 된다.

```
... 11,333 → 16,333 → 21,333 → 26,333 → 31,333 → 36,333ms
```

**31초 근처의 측정 해상도가 5초다.** 실제로 26.4초에 끝난 트랜잭션도, 31.3초에 끝난 것도 똑같이 31.33초로 기록된다.

**1600 아래에서는 눈금이 오히려 워밍업을 묶어준다.** 폴 수가 트랜잭션당 정확히 15.000 회다 — coroutine 480(28,635/1,909)·960(57,270/3,818)·1200(71,595/4,773)·1400(83,520/5,568), webclient 1400·1600(95,445/6,363) 전부. **모든 트랜잭션이 예외 없이 15번째 폴에서 끝났다**는 뜻이고, 그러면 각각은 `(26,333, 31,333]` 안에서 끝난 것이다.

여기에 물리 바닥이 겹친다. 느린 은행이 30초를 자므로 실제 완료는 `(30,000, 31,333]` — **폭이 1.3초지 5초가 아니다.** 그리고 어느 트랜잭션 하나라도 1.4초쯤 더 걸렸으면 16번째 폴로 넘어가 폴 수가 15.0 을 넘었을 것이다. 안 넘었다.

**그래서 이 구간의 콜드 비용은 트랜잭션당 1.3초 아래로 묶인다.** 31초 대비 4% 다. 원래 "여기서는 워밍업이 문제가 안 된다" 던 결론이 **낮은 rate 에서는 대체로 맞았다** — 다만 이유가 내가 적었던 것("하한에 붙어 있어서")이 아니라 이거다.

**새기 시작하는 지점이 보인다.** coroutine 1600 은 15.0003 회(95,432/6,362)이고 e2e 최댓값이 38,188ms 다 — 몇 건이 이미 16번째 폴로 넘어갔다.

**1800 에서는 눈금이 아예 무너진다.** 요청 왕복이 초 단위로 늘어(회차별 985~1,968ms) 폴 수가 **13.76회**(84,911/6,172)로 정수가 아니게 된다. e2e 가 min 30,028 · med 38,594 · max 69,422 로 흩어지고 어느 눈금에도 안 걸린다.

**그러니 1800 구간의 해상도는 5초보다 나쁘다.** 간격이 `5초 + 요청 왕복` 이라 6~7초이고 회차마다 다르다. 이 구간이 하필 무릎과 그 원인 후보를 지운 A/B 들이 사는 곳이다.

> 위의 왕복값은 `http_req_duration` 이라 **제출 POST 와 폴 GET 이 섞여 있다.** 폴만 따로 세는 지표가 없어서 그대로 썼다 — 간격 추정의 상한으로 읽을 것.

**덤으로 e2e 는 접수 시간을 아예 안 센다.** `pollUntilTerminal` 이 `startTime` 을 submit 이 202 를 돌려준 **뒤에** 잡는다. 1800 에서는 그 submit 자체가 느려서, 빠진 양이 부하에 따라 달라진다. 같은 회차의 e2e 최솟값이 30,028ms 로 "이론 하한 31초" 보다 아래인 이유가 그것이다.

**버티는 결론과 안 버티는 결론이 갈린다.**

| | 워밍업에 영향받나 |
| --- | --- |
| `pool ÷ 9` 의 **경계** (각 풀에서 막힌 RPM 이 다음 풀에선 0) | **거의 아니다.** 풀 크기 대 점유 시간의 산수고, 점유 시간은 mock 의 sleep 이 정한다. 폴링 해상도와도 무관하다 |
| 그 **거부 건수** (317/740/1641/3409) | **조금 받는다.** 거부는 200칸 큐가 넘칠 때 나는데, 콜드 JVM 은 4분 회차 초반에 큐를 더 느리게 빼므로 건수가 밀린다. 경계는 버텨도 숫자 자체는 아니다 |
| 1800 RPM 무릎, 그리고 그 원인 후보를 지운 A/B | **모른다.** 전부 n=1 콜드 회차이고, 그 구간은 해상도가 6~7초다 |

두 번째 줄이 아프다. 커넥션 풀(1.0% 차이)과 IO 워커(16% 악화 / 2.4% 개선)로 후보를 지운 판단이 **전부 같은 오염 구간 안에서 나왔다.** 지웠다고 보기 어렵다.

**고칠 자리가 둘이다.**

**해상도** — `POLL_MAX_MS` 를 낮춘다. 5초 눈금으로는 워밍업이든 뭐든 초 단위 차이를 못 본다. 다만 폴 자체가 게이트웨이 부하라 무작정 줄이면 측정이 대상을 밀기 시작한다. 그리고 `startTime` 을 submit **앞**으로 옮기면 접수 시간이 e2e 에 들어온다.

**워밍업** — 논블로킹 모드는 RPM 단계들을 **같은 JVM 위에서** 연속으로 돌릴 수 있다. 인자가 같아서 재기동이 인자 때문에 필요하진 않다. 다만 공짜는 아니다 — `bench.sh` 가 회차마다 `docker logs` 를 통째로 떠서 `/var/log/bench/<run_id>.log` 에 남기므로, JVM 을 이어 쓰면 뒤 회차 로그에 앞 회차가 전부 섞인다. 방법론의 "표에서 이상이 보이면 A 의 로그를 본다" 가 회차 단위로 안 먹는다.

**단 `async-threadpool` 은 안 된다.** 위에 적은 대로 4,096 스레드가 회차 뒤에도 살아 있어서, 안 내리면 다음 회차가 이미 만들어진 풀로 시작한다. 그러면 `pool ÷ 9` 결과가 무효가 된다 — 그건 이 문서가 워밍업에 영향받지 않는다고 말하는 바로 그 결론이다. 그쪽은 재기동을 유지하고 버리는 부하를 앞에 흘려보내야 한다. 자매 저장소 [`check-in-event`](https://github.com/roomdoor/check-in-event) 가 그 방식을 쓴다.

### 회차가 실패해도 그 회차만 버린다

기동 실패, k6 비정상 종료, DB 쿼리 실패, 드레인 상한, 집계 불일치 — 어느 쪽이든 그 회차에 `valid: false` 를 남기고 다음으로 간다. 스크립트가 실행 중에 "천장이다"라고 판단해서 남은 회차를 건너뛰지 않는다. 그 판단은 사람이 표를 보고 한다.

### 조건 추적

회차마다 manifest에 이미지 **다이제스트**, JVM 플래그, Spring 인자, duration, k6 버전을 남긴다. 태그가 아니라 다이제스트라 `latest` 가 가리키는 대상이 바뀌어도 어느 빌드였는지 남는다.

설계 배경은 [`perf/k6/DECISIONS.md`](perf/k6/DECISIONS.md).

### 자동화

| 경로 | 용도 |
| --- | --- |
| `perf/k6/smoke.sh` | 배포 직후 점검. 모드당 1건, 부하 없음 |
| `perf/k6/bench.sh` | 측정 오케스트레이터. k6 호스트에서 돌며 게이트웨이를 SSM으로 제어 |
| `perf/k6/config/*.env` | 측정 세대 하나 = 파일 하나 |
| `perf/k6/parse.mjs` | 결과 + manifest → 마크다운 표 |
| `perf/k6/fetch-results.sh` | 결과를 S3 경유로 로컬 회수 |
| `infra/` | 측정용 AWS 3호스트 Terraform |

---

## 운영 참고

```yaml
app:
  banks:
    parallelism: 50
    per-call-timeout-ms: 50000
  web-client-fan-out:
    routing-mode: sharded      # 10 샤드에 분산
    max-connections: 20000     # 기본값은 2000. v15 측정은 인자로 덮어썼다
                               # (pool 스윕 네 판은 8000). 샤드(원격 주소)
                               # 마다 적용된다 — 아래 참고
```

**모드 선택**

- **coroutine / webclient** — 이 부하 범위에서 차이가 없다. 코드 스타일로 고르면 된다
- **async-threadpool** — 쓰려면 `pool ≥ 목표 RPM × 9` 를 확보해야 한다. 480 RPM이면 4,320개다

**`max-connections` 는 전체가 아니라 원격 주소마다다.** Reactor Netty의 `ConnectionProvider` 가 그렇게 동작하고, mock이 10샤드로 갈려 있어 풀이 10개 생긴다. `pendingAcquireMaxCount` 도 마찬가지다.

**샤드당 수요는 균등하지 않다.** 샤딩이 `(bankNumber-1) % 10` 이라 30초짜리 느린 은행 2개가 한두 샤드에 몰린다. 가장 무거운 샤드가 `4×10 + 30 = 70` 커넥션초를 쓰므로 `RPM × 1.17` 이고, 둘이 같은 샤드면 `RPM × 1.5` 다. 20,000이면 약 13,000~17,000 RPM까지 여유가 있다.

이번 측정에서 커넥션은 한 번도 제약이 아니었다. 20,000 → 40,000으로 올려도 지연이 1.0%밖에 안 변한 이유다.

**커넥션 풀은 스레드보다 싸다.** 스레드 1개가 1MB 스택을 쓰는 반면 커넥션은 수십 KB다. 논블로킹은 비싼 자원(스레드)을 싼 자원(커넥션)으로 바꾸는 셈이다.

---

## 아키텍처

### 패키지 구조

```
loanlimitbatchrun/      submit 공통 오케스트레이션 + polling
bankcallresult/         은행 결과 저장 + retry
fanout/                 4가지 fan-out 실행 전략
  coroutine/            CoroutineBankFanOutExecutor
  asyncpool/            AsyncThreadPoolBankFanOutExecutor + Worker
  webclient/            WebClientBankFanOutExecutor
  sequential/           SequentialSingleThreadBankFanOutExecutor (bad-case)
bank/                   BankApiService 인터페이스 + ExternalBankApiService 구현
config/                 AppProperties, AsyncExecutionConfig, WebClientConfig
logging/                MDC 키와 전파 (네 모드 공통)
```

- submit API는 모드별 `*LoanLimitQueryController`로 분리되고 내부에서 `LoanLimitQueryOrchestrator`로 수렴
- polling은 `LoanLimitBatchRunController` 단일 엔드포인트
- 각 executor는 `BankFanOutExecutor` 를 구현, `BankFanOutExecutorRegistry` 가 모드별 매핑
- 은행 호출은 네 모드가 **하나의 WebClient 풀을 공유**한다 (`WebClientConfig.sharedBankWebClient`)

### API

```
POST /api/v1/loan-limit/coroutine/queries
POST /api/v1/loan-limit/async-threadpool/queries
POST /api/v1/loan-limit/webclient/queries
POST /api/v1/loan-limit/sequential/queries

GET  /api/v1/loan-limit/queries/request/{requestId}   # polling (모드 공통)
GET  /api/v1/loan-limit/queries/number/{transactionNo}
```

요청:
```json
{
  "borrowerId": "USER-1001",
  "annualIncome": 70000000,
  "requestedAmount": 30000000
}
```

submit은 즉시 `202 Accepted` 와 `transactionNo`, `requestId` 를 반환한다. fan-out은 백그라운드에서 진행되고 polling으로 확인한다.

### 실패 격리

은행 하나의 실패가 나머지를 막지 않는다. 막는 자리가 종류별로 다르다.

- **저장 실패** — `LoanLimitQueryOrchestrator` 가 `onEachResult` 를 감싼다. 정의되는 곳이 한 곳뿐이라 네 모드가 같은 정책을 쓴다
- **제출 실패** — 모드마다 구조가 달라 각 executor가 맡는다
- **풀 거부** — async-threadpool만 해당. `REJECTED` 행으로 기록

`status='FAILED'` 는 세 가지로 갈린다 — 은행을 다 호출했는데 성공이 0(부하 신호), 집계 단계에서 막힘(DB 부하), fan-out이 예외로 중단(코드 문제). `fail_reason` 컬럼이 그 구분자다.

---

## 실행

측정 인프라는 [`infra/README.md`](infra/README.md), 측정 스크립트는 [`perf/k6/README.md`](perf/k6/README.md).

### AWS에서 측정

```bash
# 로컬 (저장소 루트)
terraform -chdir=infra init && terraform -chdir=infra apply
terraform -chdir=infra output next_steps

# k6 호스트 안에서 (세션 접속 명령은 output connect 에 있다)
sudo -i
cd /opt/fan-out-call/perf/k6
./smoke.sh                            # 배포 직후 점검, 2~3분
./bench.sh config/v15-pool512.env

# 다시 로컬에서
./perf/k6/fetch-results.sh            # 결과 회수. destroy 전에 반드시
terraform -chdir=infra destroy
```

인스턴스는 쓸 때만 켠다. 측정 사이에는 `stop` 으로 내려두면 결과가 디스크에 남고 EBS 요금만 든다.

> **`apply` 를 다시 돌릴 때 `-var` 값을 바꾸지 말 것.** 세 인스턴스 모두 `user_data_replace_on_change = true` 라, `repo_ref` 가 바뀌면 인스턴스가 **교체되고 디스크의 측정 결과가 같이 사라진다.** v15 때 이걸로 pool512 결과를 날려 다시 쟀다.
>
> cloud-init 은 인스턴스당 한 번만 돈다 — `stop`/`start` 로는 재부트스트랩이 안 된다. 새 설정·스크립트는 호스트에서 `git -C /opt/fan-out-call pull` 로 받는다.

### 로컬에서 기동

```bash
git clone git@github.com:roomdoor/fan-out-api-mock-server.git
export MOCK_SERVER_DIR=$PWD/fan-out-api-mock-server
(cd "$MOCK_SERVER_DIR" && ./gradlew installDist)

docker compose up -d mysql

(cd "$MOCK_SERVER_DIR/perf" && \
  set -a && source realistic.env && set +a && \
  docker compose up -d --build)

./gradlew bootJar
java -jar build/libs/loan-limit-gateway-*.jar
```

로컬은 동작 확인용이다. **성능 측정에는 쓰지 않는다** — 아래 참조.

### 필요한 도구

JDK 25, Docker, k6, Node.js, Terraform, AWS CLI.

---

## Tech Stack

**Backend**: Kotlin · Spring Boot 4 · Spring WebFlux · Kotlin Coroutines · JPA + Flyway · MySQL 8.4
**Async**: ThreadPoolTaskExecutor · Reactor Netty · `Dispatchers.IO`
**Mock**: Ktor (별도 저장소)
**측정**: k6 · Terraform · AWS (EC2 · SSM · S3)
**Build**: Gradle (Kotlin DSL) · JDK 25 toolchain

---

## 결과 디렉터리

`perf/k6/results/v15-*` — 이 문서의 모든 수치. AWS 3호스트 실측 24회차.

`perf/k6/results/v1~v14` — 게이트웨이·mock·MySQL·k6를 한 대에 올려 측정했다. 어느 쪽이 병목인지 구분되지 않아 결과를 쓰지 않는다. 기록으로만 남겨둔다.
