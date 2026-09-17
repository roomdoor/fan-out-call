#!/bin/bash
# 측정 오케스트레이터. C 호스트(k6)에서 돌며 게이트웨이(A)를 SSM으로 조종한다.
#
#   ./bench.sh config/v15-pool512.env
#   node parse.mjs results/v15-pool512
#
# 설정 하나 = pool 하나. 숫자는 게이트웨이 DB에서 센다.
# 배경은 DECISIONS.md 참조.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONFIG="${1:-}"
if [ -z "${CONFIG}" ]; then
  echo "usage: $0 <config.env>" >&2
  exit 1
fi
[ -f "${CONFIG}" ] || { echo "config not found: ${CONFIG}" >&2; exit 1; }

# shellcheck disable=SC1090
source "${CONFIG}"
CONFIG_NAME="$(basename "${CONFIG}" .env)"

# Terraform이 /etc/profile.d/bench.sh 에 심어둔다.
: "${GATEWAY_INSTANCE_ID:?GATEWAY_INSTANCE_ID not set (source /etc/profile.d/bench.sh)}"
: "${AWS_REGION:?AWS_REGION not set}"
: "${BASE_URL:?BASE_URL not set}"

MODES="${MODES:-${MODE:-coroutine}}"
RPMS="${RPMS:?RPMS not set in config}"
REPEATS="${REPEATS:-3}"
DURATION="${DURATION:-4m}"
POOL="${POOL:-}"
QUEUE="${QUEUE:-200}"
MAX_WAIT_MS="${MAX_WAIT_MS:-180000}"
POLL_MAX_MS="${POLL_MAX_MS:-5000}"
DRAIN_POLL_SECONDS="${DRAIN_POLL_SECONDS:-10}"
JAVA_OPTS="${JAVA_OPTS:-}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_DATABASE="${MYSQL_DATABASE:-loan_limit_gateway}"

# ---------------------------------------------------------------------------
# 설정 검증 — EC2 시간을 쓰기 전에 전부 본다.
# ---------------------------------------------------------------------------

config_error() { echo "config error: $*" >&2; exit 1; }

require_int() {
  case "$2" in
    ''|*[!0-9]*) config_error "$1 must be a whole number, got '$2'" ;;
  esac
}

for value in ${RPMS}; do require_int "RPMS" "${value}"; done
require_int "REPEATS" "${REPEATS}"
require_int "MAX_WAIT_MS" "${MAX_WAIT_MS}"
require_int "POLL_MAX_MS" "${POLL_MAX_MS}"
require_int "DRAIN_POLL_SECONDS" "${DRAIN_POLL_SECONDS}"
# 0이면 드레인 루프가 쉬지 않고 SSM을 때린다.
[ "${DRAIN_POLL_SECONDS}" -ge 1 ] || config_error "DRAIN_POLL_SECONDS must be at least 1"
require_int "QUEUE" "${QUEUE}"
[ -n "${POOL}" ] && require_int "POOL" "${POOL}"

# "1m30s" 는 안 받는다. 초로 정확히 바꿀 수 있는 형태만 통과시킨다.
case "${DURATION}" in
  *[0-9]s) DURATION_UNIT=1 ;;
  *[0-9]m) DURATION_UNIT=60 ;;
  *[0-9]h) DURATION_UNIT=3600 ;;
  *) config_error "DURATION must look like 90s, 4m or 1h, got '${DURATION}'" ;;
esac
DURATION_VALUE="${DURATION%[smh]}"
case "${DURATION_VALUE}" in
  ''|*[!0-9]*) config_error "DURATION must look like 90s, 4m or 1h, got '${DURATION}'" ;;
esac
DURATION_SECONDS=$(( DURATION_VALUE * DURATION_UNIT ))

# sequential 은 측정하지 않는다(트랜잭션 하나가 9분이라 회차 안에 안 끝난다).
# 아래 측정 루프와 같은 방식으로 쪼개야 여러 줄로 쓴 MODES 도 걸린다.
for mode in ${MODES}; do
  [ "${mode}" = "sequential" ] && config_error "sequential is not measured (see README.md)"
done

# pool 설정은 async-threadpool 모드만 읽는다. 다른 모드에 붙이면 manifest 에
# 그 모드가 갖지 않은 조건이 기록된다.
pool_label_for() {
  if [ -n "${POOL}" ] && [ "$1" = "async-threadpool" ]; then
    echo "pool${POOL}-q${QUEUE}"
  else
    echo "default"
  fi
}

pool_args_for() {
  if [ -n "${POOL}" ] && [ "$1" = "async-threadpool" ]; then
    echo "--app.async-thread-pool.core-pool-size=${POOL} --app.async-thread-pool.max-pool-size=${POOL} --app.async-thread-pool.queue-capacity=${QUEUE}"
  fi
}

K6_VERSION="$(k6 version 2>/dev/null | head -1)"
K6_VERSION="${K6_VERSION:-unknown}"

RESULTS_ROOT="${SCRIPT_DIR}/results/${CONFIG_NAME}"
mkdir -p "${RESULTS_ROOT}"

# 드레인 상한. 트랜잭션 하나의 e2e 하한이 31초라 여유를 둔다.
DRAIN_CAP_SECONDS=$(( MAX_WAIT_MS / 1000 + 60 ))

# ---------------------------------------------------------------------------
# 원격 호출 — 전부 0/1 을 돌려주고, 부르는 쪽이 판단한다.
# ---------------------------------------------------------------------------

ssm_run() {
  local script="$1"
  local params cmd_id status

  params="$(jq -n --arg s "${script}" '{commands: [$s], executionTimeout: ["3600"]}')" || return 1

  cmd_id="$(aws ssm send-command \
    --region "${AWS_REGION}" \
    --instance-ids "${GATEWAY_INSTANCE_ID}" \
    --document-name AWS-RunShellScript \
    --parameters "${params}" \
    --query 'Command.CommandId' --output text 2>/dev/null)" || return 1
  [ -n "${cmd_id}" ] && [ "${cmd_id}" != "None" ] || return 1

  local waited=0
  while [ "${waited}" -lt 600 ]; do
    status="$(aws ssm get-command-invocation \
      --region "${AWS_REGION}" --command-id "${cmd_id}" \
      --instance-id "${GATEWAY_INSTANCE_ID}" \
      --query 'Status' --output text 2>/dev/null)" || status=Pending
    case "${status}" in
      Success) break ;;
      Failed|Cancelled|TimedOut)
        echo "SSM ${status}" >&2
        return 1
        ;;
    esac
    sleep 2
    waited=$(( waited + 2 ))
  done
  [ "${status}" = "Success" ] || { echo "SSM did not finish (last: ${status})" >&2; return 1; }

  aws ssm get-command-invocation \
    --region "${AWS_REGION}" --command-id "${cmd_id}" \
    --instance-id "${GATEWAY_INSTANCE_ID}" \
    --query 'StandardOutputContent' --output text 2>/dev/null
}

# 비밀번호는 컨테이너 env 안에만 있다. SQL 은 base64 로 넘긴다 — 그냥 끼우면
# 'COMPLETED' 의 작은따옴표가 sh -c '...' 를 끊는다.
db_query() {
  local encoded
  encoded="$(printf '%s' "$1" | base64 | tr -d '\n')" || return 1
  ssm_run "echo ${encoded} | base64 -d | docker exec -i ${MYSQL_CONTAINER} sh -c 'MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" exec mysql -N -B -uroot ${MYSQL_DATABASE}'" \
    | tr -d '\r'
}

gateway_stop() { ssm_run "docker rm -f gateway >/dev/null 2>&1 || true" >/dev/null; }

gateway_start() {
  ssm_run "JAVA_TOOL_OPTIONS='${JAVA_OPTS}' /usr/local/bin/gateway-run.sh $1 ${EXTRA_ARGS}" >/dev/null
}

# 회차 사이에 DB 를 비운다. 게이트웨이 재시작은 MySQL 을 건드리지 않으므로
# 안 비우면 지난 회차 행까지 세진다. 외래키 때문에 체크를 끄고 TRUNCATE 한다.
db_reset() {
  db_query "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE bank_call_result; TRUNCATE TABLE loan_limit_batch_run; SET FOREIGN_KEY_CHECKS=1;" >/dev/null
}

resolve_digest() {
  ssm_run "docker inspect --format '{{index .RepoDigests 0}}' \$(docker inspect --format '{{.Config.Image}}' gateway 2>/dev/null || echo none) 2>/dev/null || echo unknown" \
    | tr -d '\r\n'
}

# ---------------------------------------------------------------------------
# 회차
# ---------------------------------------------------------------------------

# 진행 중인 트랜잭션이 0이 될 때까지 기다린다.
# "아직 안 끝났다"와 "못 읽었다"를 구분한다 — 합치면 SSM 장애가 포화로 보인다.
DRAIN_UNREADABLE=false
drain_wait() {
  local started="${SECONDS}" elapsed=0 remaining last_good="" consecutive_failures=0

  DRAIN_UNREADABLE=false
  while :; do
    remaining="$(db_query "SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='IN_PROGRESS';")" || remaining=""
    case "${remaining}" in
      ''|*[!0-9]*) consecutive_failures=$(( consecutive_failures + 1 )) ;;
      0)
        echo "drained after $(( SECONDS - started ))s"
        return 0
        ;;
      *) consecutive_failures=0; last_good="${remaining}" ;;
    esac

    # SSM 왕복에도 시간이 걸리므로 sleep 횟수가 아니라 벽시계로 센다.
    elapsed=$(( SECONDS - started ))
    [ "${elapsed}" -lt "${DRAIN_CAP_SECONDS}" ] || break
    sleep "${DRAIN_POLL_SECONDS}"
  done

  # 기준은 "상한에 닿는 순간 읽고 있었나"다. 누적 성공 횟수로 보면 첫 폴링만
  # 성공해도 읽을 수 있었던 것이 된다.
  if [ "${consecutive_failures}" -gt 0 ]; then
    DRAIN_UNREADABLE=true
    echo "drain could not read the database for the last ${consecutive_failures} polls (last known in progress: ${last_good:-unknown})" >&2
  else
    echo "drain hit the ${DRAIN_CAP_SECONDS}s cap (still in progress: ${remaining})" >&2
  fi
  return 1
}

COUNT_SQL="SELECT
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='COMPLETED'),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='PARTIAL_FAILURE'),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='FAILED' AND fail_reason IS NULL),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='FAILED' AND fail_reason IS NOT NULL AND fail_reason NOT LIKE 'FINALIZE_FAILED:%'),
 -- 집계 단계에서 터진 run. fan-out 은 끝났으므로 코드 문제가 아니라
 -- 부하 증상이다. 섞으면 포화가 코드 문제로 보고된다.
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='FAILED' AND fail_reason LIKE 'FINALIZE_FAILED:%'),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='IN_PROGRESS'),
 -- 상태별 합과 비교할 전체 run 수. 이 비교가 실제로 깨질 수 있는 검사다 -
 -- RunStatus 에 값이 하나 늘면 그 run 들이 어느 칸에도 안 잡힌다.
 (SELECT COUNT(*) FROM loan_limit_batch_run),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=1),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='REJECTED'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='SUBMIT_ERROR'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='EXCEPTION'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code NOT IN ('REJECTED','SUBMIT_ERROR','EXCEPTION')),
 (SELECT COUNT(*) FROM bank_call_result),
 -- 예외로 끊긴 run 은 안 부른 은행의 행이 없는 게 정상이다. 빼지 않으면
 -- 저장 실패로도 세어져 경고가 두 번 뜬다.
 (SELECT COUNT(*) FROM loan_limit_batch_run r WHERE r.fail_reason IS NULL AND (SELECT COUNT(*) FROM bank_call_result b WHERE b.run_id=r.id) < r.requested_bank_count);"

# 숫자 14개를 받아 JSON 으로 만든다.
#
# 합 검사는 run 상태에만 건다. RunStatus 에 값이 늘면 그 run 들이 어느 칸에도
# 안 잡히고 사라지는데, 이 비교가 그걸 잡는다. 은행 호출 쪽은 마지막 버킷이
# 캐치올이라 합이 항상 맞아서 검사할 게 없다.
collect_counts() {
  local row
  row="$(db_query "${COUNT_SQL}")" || return 1

  local fields
  # shellcheck disable=SC2086
  set -- ${row}
  fields=$#
  [ "${fields}" -eq 14 ] || { echo "expected 14 counts, got ${fields}: ${row}" >&2; return 1; }

  local n
  for n in "$@"; do
    case "${n}" in
      ''|*[!0-9]*) echo "non-numeric count in: ${row}" >&2; return 1 ;;
    esac
  done

  local status_sum=$(( $1 + $2 + $3 + $4 + $5 + $6 ))
  local balanced=true
  if [ "${status_sum}" -ne "$7" ]; then
    balanced=false
    echo "run status sum ${status_sum} != loan_limit_batch_run rows $7" >&2
  fi

  jq -n \
    --argjson completed "$1" --argjson partial "$2" \
    --argjson failed_saturated "$3" --argjson failed_error "$4" \
    --argjson failed_finalize "$5" --argjson in_progress "$6" \
    --argjson runs_total "$7" \
    --argjson calls_success "$8" --argjson calls_rejected "$9" \
    --argjson calls_submit_error "${10}" --argjson calls_exception "${11}" \
    --argjson calls_other "${12}" --argjson calls_total "${13}" \
    --argjson runs_missing_rows "${14}" \
    --argjson balanced "${balanced}" \
    '{completed:$completed, partial:$partial,
      failed_saturated:$failed_saturated, failed_error:$failed_error,
      failed_finalize:$failed_finalize, in_progress:$in_progress,
      runs_total:$runs_total,
      calls_success:$calls_success, calls_rejected:$calls_rejected,
      calls_submit_error:$calls_submit_error, calls_exception:$calls_exception,
      calls_other:$calls_other, calls_total:$calls_total,
      runs_missing_rows:$runs_missing_rows, balanced:$balanced}'
}

# ---------------------------------------------------------------------------
# 측정 루프
# ---------------------------------------------------------------------------

echo "config    : ${CONFIG_NAME}"
echo "modes     : ${MODES}"
echo "pool      : ${POOL:-(기본값)}${POOL:+ / queue ${QUEUE} — async-threadpool 회차에만 적용}"
echo "rpms      : ${RPMS}"
echo "repeats   : ${REPEATS}"
echo "duration  : ${DURATION} (${DURATION_SECONDS}s)"
echo "results   : ${RESULTS_ROOT}"
echo ""

trap 'gateway_stop || true' EXIT

invalid_rounds=0
total_rounds=0

for MODE in ${MODES}; do
pool_label="$(pool_label_for "${MODE}")"
pool_args="$(pool_args_for "${MODE}")"

for rpm in ${RPMS}; do
for rep in $(seq 1 "${REPEATS}"); do
  run_id="${MODE}/${pool_label}/rpm${rpm}/rep${rep}"
  out_dir="${RESULTS_ROOT}/${MODE}/${pool_label}/rpm${rpm}"
  mkdir -p "${out_dir}"

  echo "=============================================================="
  echo "  ${CONFIG_NAME} / ${run_id}"
  echo "=============================================================="

  # 이 회차가 쓸 만한 숫자를 냈는지 여기 하나로 판단한다.
  # 어느 단계가 실패하든 이 회차만 버리고 다음으로 간다.
  round_ok=true
  drain_capped=false
  counts="null"

  gateway_stop || true

  # 순서를 바꾸지 말 것. apply 직후 DB 에는 스키마가 없고, Flyway 는 게이트웨이
  # 기동 때 돈다. 비우기가 앞에 오면 첫 회차가 없는 테이블에서 막힌다.
  if ! gateway_start "${pool_args}"; then
    echo "gateway failed to start, skipping ${run_id}" >&2
    round_ok=false
  fi

  # 기동 직후, k6 전에 비운다. 지난 회차 행이 남아 있으면 같이 세진다.
  if [ "${round_ok}" = true ] && ! db_reset; then
    echo "could not clear tables, skipping ${run_id}" >&2
    round_ok=false
  fi

  digest="unknown"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  if [ "${round_ok}" = true ]; then
    digest="$(resolve_digest)" || digest="unknown"

    summary_file="${out_dir}/rep${rep}.summary.json"
    k6_status=0
    ( cd "${SCRIPT_DIR}" && \
      MODE="${MODE}" \
      LOAD_RPM="${rpm}" \
      DURATION="${DURATION}" \
      BASE_URL="${BASE_URL}" \
      MAX_WAIT_MS="${MAX_WAIT_MS}" \
      POLL_MAX_MS="${POLL_MAX_MS}" \
      RUN_ID="${CONFIG_NAME}-${MODE}-${pool_label}-rpm${rpm}-rep${rep}" \
      k6 run --summary-export="${summary_file}" load.js ) || k6_status=$?

    # 99 = threshold 위반. 부하는 다 걸린 것이라 회차를 버리지 않는다
    # (threshold 는 천장에서 깨지므로 제일 중요한 회차가 사라진다).
    if [ "${k6_status}" -eq 99 ]; then
      echo "k6 threshold breached for ${run_id} (load was applied; keeping the round)" >&2
    elif [ "${k6_status}" -ne 0 ]; then
      # k6 가 죽어도 DB 는 0 을 돌려준다. 정상 0 과 구분되지 않으므로 무효 처리.
      echo "k6 exited ${k6_status} for ${run_id}" >&2
      round_ok=false
    fi
  fi

  # k6 는 DURATION 에서 멈추지만 트랜잭션 e2e 하한이 31초다. 막바지에 넣은
  # 것들이 아직 돌고 있으므로 기다린 뒤에 센다.
  drained=false
  drain_unreadable=false
  if [ "${round_ok}" = true ]; then
    if drain_wait; then
      drained=true
    else
      drain_capped=true
      drain_unreadable="${DRAIN_UNREADABLE}"
    fi
  fi

  # 상한에 걸려도 숫자는 가져온다 — 천장 근처 회차의 거부 수치가 거기 있다.
  # 회차는 무효로 둔다(안 끝난 트랜잭션을 두고 센 값이라 낮다).
  if [ "${round_ok}" = true ]; then
    if ! counts="$(collect_counts)"; then
      counts="null"
      round_ok=false
    elif [ "$(echo "${counts}" | jq -r '.balanced')" != "true" ]; then
      round_ok=false
    elif [ "${drained}" != true ]; then
      round_ok=false
    fi
  fi

  gateway_stop || true

  total_rounds=$(( total_rounds + 1 ))
  [ "${round_ok}" = true ] || invalid_rounds=$(( invalid_rounds + 1 ))

  jq -n \
    --arg config "${CONFIG_NAME}" --arg run_id "${run_id}" \
    --arg mode "${MODE}" --arg pool "${pool_label}" \
    --argjson rpm "${rpm}" --argjson rep "${rep}" \
    --arg duration "${DURATION}" \
    --argjson duration_seconds "${DURATION_SECONDS}" \
    --arg java_opts "${JAVA_OPTS}" \
    --arg spring_args "${pool_args} ${EXTRA_ARGS}" \
    --arg image "${digest}" --arg k6_version "${K6_VERSION}" \
    --arg started_at "${started_at}" \
    --argjson valid "${round_ok}" \
    --argjson drain_capped "${drain_capped}" \
    --argjson drain_unreadable "${drain_unreadable}" \
    --argjson counts "${counts}" \
    '{config:$config, run_id:$run_id, mode:$mode, pool:$pool, rpm:$rpm,
      repeat:$rep, duration:$duration, duration_seconds:$duration_seconds,
      java_opts:$java_opts, spring_args:$spring_args, gateway_image:$image,
      k6_version:$k6_version, started_at:$started_at,
      valid:$valid, drain_capped:$drain_capped,
      drain_unreadable:$drain_unreadable, counts:$counts}' \
    > "${out_dir}/rep${rep}.manifest.json"

  echo "counts: ${counts}"
  echo ""
done
done
done

echo "done. parse with:"
echo "  node ${SCRIPT_DIR}/parse.mjs ${RESULTS_ROOT}"
if [ "${invalid_rounds}" -gt 0 ]; then
  echo ""
  echo "${invalid_rounds}/${total_rounds} round(s) produced no usable numbers; marked valid=false" >&2
fi

# 한 회차도 못 건졌으면 실패로 끝낸다. `bench.sh && parse.mjs` 가 빈 표를
# 찍고 넘어가지 않게.
if [ "${invalid_rounds}" -eq "${total_rounds}" ]; then
  echo "no usable rounds — check the gateway and the database on the A host" >&2
  exit 1
fi
