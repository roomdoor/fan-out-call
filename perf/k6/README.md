# 측정 스크립트

fan-out 모드별 처리 한계를 재는 k6 스위트다. AWS 3호스트에서 돈다 —
인프라는 [`infra/`](../../infra), 설계 배경은 [`DECISIONS.md`](DECISIONS.md).

## 구조

```
perf/k6/
├── smoke.sh           배포 직후 점검. 모드별 1건씩, 부하 없음
├── bench.sh           측정 오케스트레이터. C(k6)에서 돌며 A를 SSM으로 제어
├── config/*.env       설정 하나 = pool 하나
├── parse.mjs          결과 -> 마크다운 표
├── load.js            측정 시나리오 (constant-arrival-rate)
├── smoke.js           점검 시나리오 (모드당 1건)
├── lib/               remote.sh(SSM·DB), 설정, 게이트웨이 폴링, 지표
└── results/<config>/  회차별 summary.json + manifest.json
```

## 실행

C 호스트에서:

```bash
cd /opt/fan-out-call/perf/k6
./smoke.sh                       # apply 직후 먼저. 2~3분
./bench.sh config/v15-pool512.env
node parse.mjs results/v15-pool512
```

설정을 새로 만들었으면 호스트에서 먼저 당겨야 한다. C 는 부팅 때 한 번만
클론하므로, 그 뒤에 추가된 파일은 `config not found` 로 끝난다.

```bash
git -C /opt/fan-out-call pull origin main
```

`BASE_URL`, `GATEWAY_INSTANCE_ID`, `AWS_REGION` 은 Terraform이
`/etc/profile.d/bench.sh` 에 심어둔다.

## 스모크

`./smoke.sh` — 부하를 걸지 않는다. 모드마다 트랜잭션 1건씩 넣고 은행 50곳이
호출되고 결과가 저장되는지만 본다. 게이트웨이는 한 번만 띄운다.

통과 기준은 모드마다 셋이다 — `status` 가 `COMPLETED`, 저장된 은행콜이 50,
성공이 1건 이상. 하나라도 어긋나면 `exit 1` 이다.

제출은 `smoke.js` 를 k6 로 돌린다. 실측이 쓰는 `lib/` 를 그대로 타므로 k6
설치, 시나리오 파싱, 게이트웨이 폴링까지 여기서 걸린다. `lib/remote.sh` 도
`bench.sh` 와 같은 것을 쓰므로 SSM 권한과 DB 접근도 같이 확인된다.

## 무엇을 세나

숫자는 게이트웨이 DB에서 센다. k6 지표로는 실효 처리율을 못 잰다 — 풀에서
거부된 트랜잭션도 빠른 `202` 를 받아 k6에는 성공으로 보인다.

**은행 호출** (`bank_call_result` 한 행 = 호출 하나)

| 버킷 | 조건 | 뜻 |
| --- | --- | --- |
| 성공 | `success = 1` | 한도를 받아왔다 |
| 거부 | `response_code = 'REJECTED'` | 풀 포화. **천장 신호** |
| 제출 실패 | `response_code = 'SUBMIT_ERROR'` | 설정·코드 문제 |
| 예외 | `response_code = 'EXCEPTION'` | 타임아웃·연결 실패 |
| 나머지 | 위 셋이 아닌 실패 | mock이 준 에러(`E503` 등) |

성공은 `response_code` 가 아니라 `success` 로 본다. `response_code` 에는
게이트웨이 값과 mock이 준 값(`S000`, `E503`)이 섞인다.

거부는 `async-threadpool` 에서만 나온다. coroutine·webclient 의 은행콜
실패는 예외 열로 드러난다.

**트랜잭션 상태** (`loan_limit_batch_run`)

| 상태 | 뜻 |
| --- | --- |
| `COMPLETED` | 은행 50곳 전부 성공 |
| `PARTIAL_FAILURE` | 일부만 성공 |
| `FAILED`, `fail_reason` 없음 | 다 호출했는데 성공 0. **부하 신호** |
| `FAILED`, `fail_reason` = `FINALIZE_FAILED:` | 집계 단계에서 막힘. **DB 부하** |
| `FAILED`, 그 밖의 `fail_reason` | fan-out 이 예외로 중단. **코드·설정 문제** |
| `IN_PROGRESS` | 진행 중 |

여섯 칸의 합이 전체 run 수와 다르면 그 회차를 무효로 표시한다.

## 드레인

k6는 `DURATION` 에서 멈추지만 트랜잭션 e2e 하한이 30초다(은행 50곳 중
2곳이 30초를 잔다). v15 에서 거부가 없던 회차들의 실측 최솟값은 30.0~31.4초인데,
31.4 쪽은 폴링 눈금이지 지연이 아니다 — 아래 커스텀 지표의 `e2e_completion_time` 설명 참고.
거부가 섞이면 그 호출이 즉시 끝나 훨씬 짧아진다 — 거부가 난 pool 스윕 네
회차는 16.4초까지 내려갔다. 막바지에 넣은 건들이 끝날 때까지 기다린 뒤에
센다. 짧게 기다리면 처리량이 낮게 나오고, 부하가 셀수록 많이 빠져 **천장이
실제보다 낮아 보인다.**

`status='IN_PROGRESS'` 가 0이면 끝이다. `MAX_WAIT_MS + 60초` 안에 0이 안 되면
그 회차는 무효다.

## 게이트웨이 로그

회차마다 컨테이너를 지우기 전에 A 의 `/var/log/bench/<회차>.log` 로 뺀다.
`docker rm -f` 는 로그도 같이 지우므로 그 뒤에는 볼 수 없다. 표에 `FAILED(오류)`
나 예외 은행콜이 뜨면 여기를 본다.

SSM 명령이 실패하면 원격 stderr 가 그대로 찍힌다 — 게이트웨이가 기동에
실패한 경우 `gateway-run.sh` 가 컨테이너 로그 마지막 50줄을 거기 쏟는다.

## 회차가 실패해도 그 회차만 버린다

기동 실패, k6 비정상 종료, DB 쿼리 실패, 드레인 상한, 집계 불일치 — 어느
쪽이든 `valid: false` 를 남기고 다음 회차로 간다. 한 회차도 못 건지면
`exit 1` 이다.

스크립트가 실행 중에 "천장이다"라고 판단해서 남은 회차를 건너뛰지 않는다.

## v15 에서 잰 것

10개 세대, 24회차. 결론은 루트 [`README.md`](../../README.md) 에 있고, 여기는
어느 설정이 무엇을 재는지만 적는다.

| 설정 | 무엇을 재나 | RPM |
| --- | --- | --- |
| `v15-pool512` ~ `v15-pool4096` | 스레드 풀 천장이 pool 에 비례하는가 | 30/60 · 60/120 · 120/240 · 240/480 |
| `v15-coroutine` · `v15-webclient` | 논블로킹 두 모드를 같은 부하에서 | 480/960/1200 |
| `v15-nonblocking-knee` | **지연**이 꺾이는 지점 좁히기 (거부는 끝까지 0) | 1400/1600 |
| `v15-nonblocking-1800` | 꺾인 뒤 | 1800 |
| `v15-nonblocking-1800-conn40k` | 커넥션이 원인인가 (아니었다) | 1800 |
| `v15-nonblocking-1800-io1024` | IO 워커가 원인인가 (아니었다) | 1800 |

뒤의 두 판은 **변수 하나만 바꾼 대조군**이다. 각각 바로 앞 판을 기준으로
삼는다 — `conn40k` 는 `v15-nonblocking-1800` 에서 커넥션만 20,000 → 40,000,
`io1024` 는 다시 `conn40k` 에서 `io.parallelism` 만 512 → 1024 다(커넥션은
40,000 그대로). 그래서 `io1024` 를 원본 1800 판과 직접 빼면 안 된다.

`v15-baseline.env` 는 쓰지 않았다. 처음 계획했던 RPM 범위(100~600)가 실측과
안 맞아 위 설정들로 대체됐다.

## pool 스윕은 하나씩

설정 하나에 pool 하나. 표를 보고 다음 pool 의 RPM 범위를 정한다.

```
pool 512 → 표 확인 → 실제 천장으로 다음 RPM 결정 → pool 1024 → ...
```

pool마다 천장 아래 한 점, 위 한 점이면 천장이 잡힌다.

v15 에서는 **각 pool 에서 막힌 RPM 을 다음 pool 의 아래 점으로 재사용**했다.
그러면 부하가 같고 pool 만 다른 지점이 생겨 직접 비교가 된다 — 실제로 60,
120, 240 세 지점에서 앞 pool 은 거부하고 뒤 pool 은 거부가 0 이었다.

## 설정 만들기

`config/` 에 `.env` 하나. 스크립트는 안 고친다.

```bash
MODE=async-threadpool        # 또는 MODES="coroutine webclient"
POOL=512                     # 빼면 애플리케이션 기본값. async-threadpool 만 읽는다
QUEUE=200
RPMS="30 60"
REPEATS=1                    # 천장 탐색은 1. 보고용 수치는 3 이상. 아래 참고
DURATION=4m                  # 90s, 4m, 1h 형태만. "1m30s" 는 거부된다
MAX_WAIT_MS=180000
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=512"
EXTRA_ARGS="--server.tomcat.threads.max=200"
```

값이 숫자가 아니거나 `DURATION` 형식이 틀리면 EC2 시간을 쓰기 전에 거부한다.
`sequential` 은 측정하지 않는다.

**`REPEATS`** — v15 는 전부 1 로 돌았다. 보려던 신호가 "거부가 났나 안 났나"
예/아니오였고, 회차 하나에 트랜잭션이 121~7,201건 들어가 mock 지연 흔들림은
그 안에서 평균이 잡힌다. 천장을 훑을 때는 1 로 충분하다. **보고서에 올릴 최종
수치는 반복해서 다시 재야 한다** — 회차 간 흩어짐은 회차 안의 건수가 아무리
많아도 안 보인다. `parse.mjs` 가 표 밑에 `n=1` 인 조건이 몇 개인지 적어준다
(어느 행인지는 표의 `n` 열을 봐야 한다).

**`DURATION`** — v15 는 전부 4분이다. 천장 근처에서는 큐가 초과분을 먼저 먹어
포화가 늦게 나타나고, **천장을 살짝만 넘긴 조건일수록 더 늦다** — 초과분이
적으면 큐가 천천히 찬다. pool 512 / 큐 200 / 60 RPM 이 큐를 채우는 데 약
77초라, 2분으로 줄이면 거부 구간이 43초뿐이다. 계산은
[`DECISIONS.md`](DECISIONS.md) 에 있다.

`POOL` 을 `EXTRA_ARGS` 로도 주면 같은 옵션이 두 번 들어가 기동이 실패한다.

`results/<config>/` 를 재사용하면 지난 실행 결과가 같이 파싱된다. 같은 이름을
다시 쓸 거면 먼저 지운다.

## manifest.json

회차마다 조건을 결과 옆에 남긴다 — 이미지 다이제스트, JVM 플래그, Spring
인자, duration, k6 버전. 태그가 아니라 다이제스트라 `latest` 가 가리키는
대상이 바뀌어도 어느 빌드였는지 남는다.

## 환경변수 (시나리오)

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 게이트웨이 주소 |
| `MODE` | (필수) | 모드별 submit 엔드포인트 |
| `LOAD_RPM` | 20 | `load.js` 도착률 |
| `DURATION` | 2m | 회차 길이 |
| `MAX_WAIT_MS` | 60000 | 완료 대기 상한 |
| `POLL_MAX_MS` | 5000 | 폴링 간격 상한 |

## 커스텀 지표

- `e2e_completion_time` — submit 이 **202 를 돌려준 뒤부터**, 폴링이 종료 상태를 확인할 때까지.
  아래 넷을 감안해서 읽을 것. 자세한 건 저장소 루트 README 의
  "회차마다 게이트웨이를 재기동한다" 절에 있다.
  - **접수 시간이 빠진다.** `startTime` 을 submit 이 202 를 돌려준 뒤에 잡는다
  - **다음 폴 시각까지 올림된다**(간격 100ms 에서 1.5배씩, 5,000ms 상한).
    눈금은 고정이 아니라 **누적된 폴 왕복만큼 위로 밀린다** — 실측 +26ms ~ +1,635ms.
    31초 근처의 간격이 5초라 그 안의 차이는 안 보인다
  - **회차 끝에서 잘린 iteration 은 안 들어간다.** 1800 RPM 회차에서 DB 가 센 것의
    84.4~96.1% 만 남았고, 빠지는 건 제일 느린 것들이다
  - **타임아웃 난 트랜잭션은 `MAX_WAIT_MS` 그 값으로 기록된다**(더해지는 게 아니다).
    실제 소요가 아니다.
    v15 24회차에서는 한 번도 안 났지만(`timeout_waiting_rate` 전부 0),
    v1~v14 에는 0 이 아닌 회차가 20개 있다(0.06~1.00) — 그 회차들의 지연
    지표에는 상수가 섞여 있다. 기본값은 `lib/common.js` 가 60,000 이고 `bench.sh` 가
    180,000 으로 덮는다
- `polls_per_transaction` — 이름과 달리 **총 폴링 횟수**(Counter)다.
  회당 값을 보려면 `iterations` 가 아니라 **기록된 표본 수**로 나눌 것
  (`timeout_waiting_rate` 의 passes+fails). submit 에서 죽은 iteration 은
  `iterations` 에 세어지지만 이 둘에는 안 들어가서, 분모를 잘못 잡으면
  값이 작아진다 — io1024 회차가 12.31 대신 12.01 로 보인다.
  그 값이 정수에 가까우면 **모든 표본이 같은 폴에서 끝났을 수 있다**는 신호이고,
  확인하려면 e2e 의 min·max 가 그 폴과 다음 폴 사이에 있는지 봐야 한다
  (14회와 16회가 반반이어도 평균은 15가 된다)
- `timeout_waiting_rate` — 대기 중 타임아웃 비율
