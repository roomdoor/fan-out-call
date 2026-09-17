# 측정 스크립트

fan-out 모드별 처리 한계를 재는 k6 스위트다. AWS 3호스트에서 돈다 —
인프라 구성은 [`infra/`](../../infra) 참조.

## 구조

```
perf/k6/
├── bench.sh           오케스트레이터. C(k6)에서 돌며 A(게이트웨이)를 SSM으로 제어
├── config/*.env       설정 하나 = pool 하나
├── parse.mjs          결과 -> 마크다운 표
├── load.js            주력 시나리오 (constant-arrival-rate)
├── smoke.js           배포 직후 확인용 1회 실행
├── lib/
│   ├── common.js      설정, 모드별 엔드포인트
│   ├── gateway.js     submit + 완료까지 폴링 (트랜잭션 1건의 생애)
│   └── metrics.js     커스텀 지표 4종
└── results/<config>/  회차별 summary.json + manifest.json
```

## 실행

C 호스트에서:

```bash
cd /opt/fan-out-call/perf/k6
./bench.sh config/smoke.env && node parse.mjs results/smoke   # apply 직후 먼저
./bench.sh config/v15-pool512.env
node parse.mjs results/v15-pool512
```

`BASE_URL`, `GATEWAY_INSTANCE_ID`, `AWS_REGION` 은 Terraform이
`/etc/profile.d/bench.sh` 에 심어둔다. 따로 넣을 필요 없다.

## 숫자는 DB에서 센다

k6 지표로는 실효 처리율을 못 잰다. 풀에서 거부된 트랜잭션도 빠른 `202` 를
받으므로 k6에는 성공으로 보인다.

그래서 게이트웨이가 남긴 DB 행을 센다. 예전에는 로그 문자열을 셌는데,
완료 여부를 "로그 개수가 안 변한다"로 추측해야 했고 그 추측이 SSM 장애와
구분되지 않았다.

**은행 호출은 다섯 갈래로 나눈다.** `bank_call_result` 한 행이 은행 호출 하나다.

| 버킷 | 조건 | 뜻 |
| --- | --- | --- |
| 성공 | `success = 1` | 한도를 받아왔다 |
| 거부 | `response_code = 'REJECTED'` | 풀 포화. **천장 신호** |
| 제출 실패 | `response_code = 'SUBMIT_ERROR'` | 설정·코드 문제. 부하와 무관 |
| 예외 | `response_code = 'EXCEPTION'` | 타임아웃·연결 실패 |
| 나머지 | 위 셋이 아닌 실패 | mock이 준 에러(`E503` 등) |

`response_code` 는 게이트웨이 값(`REJECTED` 등)과 mock이 준 값(`S000`,
`E503`)이 섞인 컬럼이라 성공 판정에 쓰면 안 된다. 성공은 게이트웨이가
직접 계산하는 `success` 컬럼으로 본다.

**다섯 버킷의 합이 전체 행 수와 같아야 한다.** `bench.sh` 가 회차마다
확인하고, 다르면 그 회차를 무효로 표시한다. 예전에 어느 카운터에도 안
잡히는 상태가 생겨 run이 통째로 집계에서 빠진 적이 있는데, 합을 맞추면
그런 누락이 불가능하다.

**트랜잭션 상태는 네 갈래다.**

| 상태 | 뜻 |
| --- | --- |
| `COMPLETED` | 은행 50곳 전부 성공 |
| `PARTIAL_FAILURE` | 일부만 성공 |
| `FAILED` + `fail_reason` 없음 | 다 호출했는데 성공 0. **부하 신호** |
| `FAILED` + `fail_reason` 있음 | fan-out 자체가 예외로 중단. **코드·설정 문제** |

`fail_reason` 이 없으면 뒤의 둘이 구분되지 않아 버그가 천장처럼 보인다.

## 드레인

k6는 `DURATION` 에서 멈추지만 트랜잭션 하나의 e2e 하한이 31초다
(은행 50곳 중 2곳이 30초). 막바지에 넣은 건들이 아직 돌고 있으므로
끝날 때까지 기다린 뒤에 센다.

기다림이 짧으면 안 끝난 게 빠져서 처리량이 낮게 나오고, 부하가 셀수록
많이 빠지므로 **천장이 실제보다 낮아 보인다.**

판정은 추측이 아니다 — `status='IN_PROGRESS'` 가 0이면 끝이다.
`MAX_WAIT_MS + 60초` 안에 0이 안 되면 그 회차는 무효로 남긴다.

## 회차가 실패해도 그 회차만 버린다

기동 실패, k6 비정상 종료, DB 쿼리 실패, 드레인 상한 초과, 버킷 합
불일치 — 어느 쪽이든 결과는 같다. 그 회차 manifest에 `valid: false` 를
남기고 다음 회차로 간다. `parse.mjs` 는 무효 회차를 표에서 빼고 몇 건인지
따로 적는다.

스크립트가 실행 중에 "천장이다"라고 판단해서 남은 회차를 건너뛰지 않는다.
그 판단은 사람이 표를 보고 한다.

## pool 스윕은 하나씩 끊어서

설정 하나에 pool 하나만 넣는다. 4종을 한 번에 던지지 않는다.

```
pool 512 → 표 확인 → 실제 천장으로 다음 pool 의 RPM 결정 → pool 1024 → ...
```

`RPM ≈ pool / 9` 는 계산값이다. 512에서 실제로 40이 나오면 공식이 틀린
것이고, 4종을 한꺼번에 돌렸으면 60회차를 통째로 버린다. 첫 판이 눈금을
맞춰준다.

pool마다 천장 아래 한 점, 위 한 점이면 천장이 잡힌다. 반복 3회 기준으로
pool 하나에 6회차, 약 45분이다.

## 설정 만들기

`config/` 에 `.env` 하나. 스크립트는 안 고친다.

```bash
MODE=async-threadpool        # 또는 MODES="coroutine webclient" (여러 모드)
POOL=512                     # 빼면 애플리케이션 기본값
QUEUE=200
RPMS="30 60"
REPEATS=3
DURATION=4m                  # 90s, 4m, 1h 형태만. "1m30s" 는 거부된다
MAX_WAIT_MS=180000
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=512"
EXTRA_ARGS="--server.tomcat.threads.max=200"
```

값이 숫자가 아니거나 `DURATION` 형식이 틀리면 **EC2 시간을 쓰기 전에**
거부한다. `sequential` 은 bad-case 시연용이라 측정하지 않는다 —
트랜잭션 하나가 9분이라 회차 안에 끝나지 않는다.

`POOL` 을 `EXTRA_ARGS` 로도 줄 수 있지만 그러면 같은 옵션이 두 번 들어가
Spring이 값을 합치고 기동이 실패한다. 둘 중 하나만 쓴다.

## 결과 디렉터리를 재사용할 때

`results/<config>/` 에 지난 실행 결과가 남아 있으면 `parse.mjs` 가 같이
읽는다. 같은 설정 이름을 다시 쓸 거면 먼저 지운다.

## 왜 manifest.json인가

회차마다 조건 전체를 결과 옆에 남긴다 — 이미지 **다이제스트**, JVM 플래그,
Spring 인자, duration, k6 버전.

이 프로젝트가 결과를 두 번 통째로 버린 이유가 조건 추적 실패였다.
v6은 공유 WebClient 수정 전 측정인 줄 몰랐고, v13은 시작 시점의 jar가
최신 커밋보다 오래된 것을 도중에 알아챘다. 태그가 아니라 다이제스트를
기록하면 `latest` 가 가리키는 대상이 바뀌어도 어느 빌드였는지가 남는다.

## 환경변수 (시나리오)

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 게이트웨이 주소 |
| `MODE` | (필수) | 모드별 submit 엔드포인트를 고른다 |
| `LOAD_RPM` | 20 | `load.js` 도착률 |
| `DURATION` | 2m | 회차 길이 |
| `MAX_WAIT_MS` | 60000 | 완료 대기 상한 |
| `POLL_MAX_MS` | 5000 | 폴링 간격 상한 |

## 커스텀 지표

- `e2e_completion_time` — submit부터 종료 상태까지
- `polls_per_transaction` — 트랜잭션당 폴링 횟수
- `timeout_waiting_rate` — 대기 중 타임아웃 비율
