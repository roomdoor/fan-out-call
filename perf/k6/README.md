# 측정 스크립트

fan-out 모드별 처리 한계를 재는 k6 스위트다. AWS 3호스트에서 돈다 —
인프라 구성은 [`infra/`](../../infra) 참조.

## 구조

```
perf/k6/
├── bench.sh           오케스트레이터. C(k6)에서 돌며 A(게이트웨이)를 SSM으로 제어
├── config/*.env       측정 세대 하나 = 파일 하나
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
./bench.sh config/v15-baseline.env
node parse.mjs results/v15-baseline
```

`BASE_URL`, `GATEWAY_INSTANCE_ID`, `AWS_REGION` 은 Terraform이
`/etc/profile.d/bench.sh` 에 심어둔다. 따로 넣을 필요 없다.

## 세대 추가

`config/` 에 `.env` 하나 만들면 된다. 스크립트는 안 고친다.

```bash
MODE=coroutine              # coroutine | async-threadpool | webclient | sequential
RPMS="100 200 400 600"
POOLS=default               # 또는 "512:200 1024:200" (pool:queue)
REPEATS=3
DURATION=4m
MAX_WAIT_MS=180000
JAVA_OPTS="-Dkotlinx.coroutines.io.parallelism=512"
EXTRA_ARGS="--server.tomcat.threads.max=200"
```

`POOLS` 는 `async-threadpool` 모드에서만 의미가 있다. 다른 모드는
`default` 로 두면 pool 인자를 넘기지 않는다.

## 왜 manifest.json인가

회차마다 조건 전체를 결과 옆에 남긴다 — 이미지 **다이제스트**, JVM 플래그,
Spring 인자, mock 프로파일, duration.

이 프로젝트가 결과를 두 번 통째로 버린 이유가 조건 추적 실패였다.
v6은 공유 WebClient 수정 전 측정인 줄 몰랐고, v13은 시작 시점의 jar가
최신 커밋보다 오래된 것을 도중에 알아챘다. 태그가 아니라 다이제스트를
기록하면 `latest` 가 가리키는 대상이 바뀌어도 어느 빌드였는지가 남는다.

`parse.mjs` 는 한 표 안에 이미지나 JVM 플래그가 섞이면 경고를 낸다.
pool 512/1024 회차가 `io.parallelism` 기본값 차이로 교란됐던 것이
그 경고가 잡으려는 상황이다.

## 실효 처리율은 k6 지표로 못 센다

풀에서 거부된 트랜잭션도 빠른 `202` 를 받으므로 k6에는 성공으로 보인다.
그래서 게이트웨이 로그를 직접 센다.

| 로그 패턴 | 의미 |
| --- | --- |
| `Background fan-out completed ... status=COMPLETED` | 50/50 전부 성공 |
| `Background fan-out completed ... status=PARTIAL_FAILURE` | 일부 은행만 성공 |
| `Background fan-out completed ... status=FAILED` | fan-out은 끝났으나 성공한 은행이 0 (전 은행 거부 등) |
| `Run marked as FAILED` | fan-out 자체가 예외로 중단됨 |
| `Bank call submission rejected bankCode=` | 풀이 그 은행 호출을 거부 |
| `Result persistence failed bankCode=` | 그 은행 결과를 DB에 저장 실패 |

앞의 세 `status=` 행이 서로 배타적이고 합이 run 수와 같다. `Run marked as FAILED`는
그 앞 단계에서 터진 경우라 별도로 센다.

**거부와 저장 실패는 은행 단위로 집계한다.** 실패가 한 은행에 갇히도록
고쳐서(`LoanLimitQueryOrchestrator`, `AsyncThreadPoolBankFanOutExecutor`)
은행 하나가 막혀도 나머지는 계속 호출되고 기록된다.

그래서 **v1~v14의 FAILED 수치와 직접 비교할 수 없다.** 그때는 은행 하나만
거부돼도 run 전체가 FAILED였고 보고서들이 그것을 "fail-fast"로 해석했다.
지금은 통과한 은행이 있으면 `PARTIAL_FAILURE` 가 되고, 막힌 은행 수가
`REJECTED` 행으로 남는다. 몇 개가 막혔는지 알 수 있어 더 정확하지만
기준이 달라졌다. v15가 새 기준선인 이유 중 하나다.

SSM stdout이 24,000자에서 잘리므로 로그 원본은 가져오지 않는다. A에서
세고 숫자만 받는다. 원본은 A의 `/var/log/bench/` 에 남는다.

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
