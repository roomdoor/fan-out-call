# 측정 스크립트

fan-out 모드별 처리 한계를 재는 k6 스위트다. AWS 3호스트에서 돈다 —
인프라는 [`infra/`](../../infra), 설계 배경은 [`DECISIONS.md`](DECISIONS.md).

## 구조

```
perf/k6/
├── bench.sh           오케스트레이터. C(k6)에서 돌며 A(게이트웨이)를 SSM으로 제어
├── config/*.env       설정 하나 = pool 하나
├── parse.mjs          결과 -> 마크다운 표
├── load.js            주력 시나리오 (constant-arrival-rate)
├── smoke.js           배포 직후 확인용 1회 실행
├── lib/               설정, 게이트웨이 폴링, 커스텀 지표
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
`/etc/profile.d/bench.sh` 에 심어둔다.

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

k6는 `DURATION` 에서 멈추지만 트랜잭션 e2e 하한이 31초다(은행 50곳 중 2곳이
30초). 막바지에 넣은 건들이 끝날 때까지 기다린 뒤에 센다. 짧게 기다리면
처리량이 낮게 나오고, 부하가 셀수록 많이 빠져 **천장이 실제보다 낮아 보인다.**

`status='IN_PROGRESS'` 가 0이면 끝이다. `MAX_WAIT_MS + 60초` 안에 0이 안 되면
그 회차는 무효다.

## 회차가 실패해도 그 회차만 버린다

기동 실패, k6 비정상 종료, DB 쿼리 실패, 드레인 상한, 집계 불일치 — 어느
쪽이든 `valid: false` 를 남기고 다음 회차로 간다. 한 회차도 못 건지면
`exit 1` 이다.

스크립트가 실행 중에 "천장이다"라고 판단해서 남은 회차를 건너뛰지 않는다.

## pool 스윕은 하나씩

설정 하나에 pool 하나. 표를 보고 다음 pool 의 RPM 범위를 정한다.

```
pool 512 → 표 확인 → 실제 천장으로 다음 RPM 결정 → pool 1024 → ...
```

pool마다 천장 아래 한 점, 위 한 점이면 천장이 잡힌다.

## 설정 만들기

`config/` 에 `.env` 하나. 스크립트는 안 고친다.

```bash
MODE=async-threadpool        # 또는 MODES="coroutine webclient"
POOL=512                     # 빼면 애플리케이션 기본값. async-threadpool 만 읽는다
QUEUE=200
RPMS="30 60"
REPEATS=3
DURATION=4m                  # 90s, 4m, 1h 형태만. "1m30s" 는 거부된다
MAX_WAIT_MS=180000
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=512"
EXTRA_ARGS="--server.tomcat.threads.max=200"
```

값이 숫자가 아니거나 `DURATION` 형식이 틀리면 EC2 시간을 쓰기 전에 거부한다.
`sequential` 은 측정하지 않는다.

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

- `e2e_completion_time` — submit부터 종료 상태까지
- `polls_per_transaction` — 트랜잭션당 폴링 횟수
- `timeout_waiting_rate` — 대기 중 타임아웃 비율
