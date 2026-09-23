# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

대출 한도 조회 게이트웨이(Spring Boot 4 + Kotlin, JDK 25). 트랜잭션 하나가 은행 50곳을 fan-out 호출한다. 같은 로직을 네 동시성 모델(`coroutine`, `async-threadpool`, `webclient`, `sequential`)로 구현했고, `sequential` 을 뺀 셋을 k6 로 비교한다(`bench.sh` 가 `sequential` 을 거부). mock 서버는 별도 저장소 `roomdoor/fan-out-api-mock-server`.

## 문서 위치

- 아키텍처·측정 결과·방법론: 루트 `README.md`
- AWS 인프라 절차: `infra/README.md` / 측정 스크립트·커스텀 지표: `perf/k6/README.md`, `perf/k6/DECISIONS.md`

## 명령

```bash
./gradlew build     # CI 와 같은 명령
./gradlew test --tests "com.example.loanlimit.fanout.SubmissionFailureIsolationTest"
```

테스트는 객체를 직접 조립하므로 MySQL·mock 없이 돈다. 로컬 기동은 동작 확인용이고 측정은 AWS 에서만 한다(README "실행").

## 함정

- **x86 전용.** CI 가 올리는 이미지는 amd64 단일이다. 멀티아치·Graviton 을 추가하지 말 것 — 에뮬레이션이 측정값을 오염시킨다. Mac 에서 로컬로 빌드한 이미지는 arm64 라 올리지 않는다.
- 스키마는 Flyway(`src/main/resources/db/migration`) + `ddl-auto: validate`. 스키마를 바꾸면 마이그레이션을 추가한다.
- 설정값은 `AppProperties` 기본값 < `application.yml` < 실행 인자 순으로 덮어쓴다. AWS 에서는 `infra/templates/gateway.sh.tftpl` 이 만드는 `gateway-run.sh` 가 라우팅·샤드 인자를 고정으로 넘기므로 그쪽이 최종값이다.
- 측정 설정의 `EXTRA_ARGS` 에 `gateway-run.sh` 나 `bench.sh`(`async-threadpool` 의 `POOL`)가 이미 넘기는 옵션을 또 넣으면 중복돼 기동에 실패한다.
- 처리량 판정은 k6 지표가 아니라 게이트웨이 DB 에서 센다. 거부된 트랜잭션도 k6 에는 `202` 로 보인다.
- `infra` 의 세 호스트는 `user_data_replace_on_change = true` 이고, k6 호스트의 user_data 가 게이트웨이·mock 의 주소를 품고 있다. 재 `apply` 때 어떤 `-var` 를 바꾸든 결과가 있는 k6 호스트까지 교체될 수 있으니 먼저 `perf/k6/fetch-results.sh` 로 회수하고 plan 을 확인한다. 저장소를 클론하는 건 k6 호스트(`/opt/fan-out-call`)뿐이라 `git pull` 로 갱신되는 것도 그 호스트의 스크립트뿐이다.
- **측정 수치를 이 파일이나 코드 주석에 옮기지 말 것.** 문서의 측정 주장은 여러 번 틀려 철회됐다(git log). 결론을 쓰기 전에 README "방법론" 과 `perf/k6/README.md` 의 단서를 먼저 확인한다. `v1~v14` 결과는 한 대에서 잰 것이라 쓰지 않는다.
