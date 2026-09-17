#!/bin/bash
# 측정 오케스트레이터. C 호스트(k6)에서 돈다.
#
#   ./bench.sh config/v15-pool512.env
#   node parse.mjs results/v15-pool512
#
# 게이트웨이(A 호스트)는 SSM으로 조종한다. 설정 하나 = pool 하나다.
# pool 여러 개를 한 번에 돌리지 않는다 — 앞 pool의 천장을 보고 다음 pool의
# RPM 범위를 정하는 편이, 스크립트가 실행 중에 천장을 판정하는 것보다 낫다.
#
# 숫자는 게이트웨이 로그가 아니라 DB에서 센다. 로그 문자열을 세면 완료
# 여부를 "개수가 안 변한다"로 추측해야 하는데, 그 추측이 SSM 장애와
# 구분되지 않는다. DB는 status='IN_PROGRESS' 가 0이면 끝난 것이다.
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
#
# 예전에는 검증이 흩어져 있어서 DURATION 오타 하나가 첫 회차를 다 돌린 뒤
# 산술 오류로 터졌다. 20분 버리고 알게 되는 종류다.
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
require_int "QUEUE" "${QUEUE}"
[ -n "${POOL}" ] && require_int "POOL" "${POOL}"

# k6는 "1m30s" 도 받지만 여기서는 안 받는다. 기대 트랜잭션 수를 세야 하므로
# 초로 정확히 바꿀 수 있는 형태만 통과시킨다.
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

# sequential은 bad-case 시연용이라 측정하지 않는다. 트랜잭션 하나가 9분
# (48x10s + 2x30s)이라 회차 안에 끝나지 않는다. 아래 루프와 같은 방식으로
# 쪼개서 본다 — 공백만 보면 여러 줄로 쓴 MODES가 빠져나간다.
for mode in ${MODES}; do
  [ "${mode}" = "sequential" ] && config_error "sequential is not measured (see README.md)"
done

if [ -n "${POOL}" ]; then
  POOL_LABEL="pool${POOL}-q${QUEUE}"
  POOL_ARGS="--app.async-thread-pool.core-pool-size=${POOL}"
  POOL_ARGS="${POOL_ARGS} --app.async-thread-pool.max-pool-size=${POOL}"
  POOL_ARGS="${POOL_ARGS} --app.async-thread-pool.queue-capacity=${QUEUE}"
else
  POOL_LABEL="default"
  POOL_ARGS=""
fi

K6_VERSION="$(k6 version 2>/dev/null | head -1)"
K6_VERSION="${K6_VERSION:-unknown}"

RESULTS_ROOT="${SCRIPT_DIR}/results/${CONFIG_NAME}"
mkdir -p "${RESULTS_ROOT}"

# 드레인 상한. 트랜잭션 하나의 e2e 하한이 31초라 여유를 둔다.
DRAIN_CAP_SECONDS=$(( MAX_WAIT_MS / 1000 + 60 ))

# ---------------------------------------------------------------------------
# 원격 호출
#
# 전부 0/1 을 돌려주고, 부르는 쪽이 판단한다. 여기서 스크립트를 죽이지
# 않는다 — SSM 한 번 삐끗했다고 몇 시간짜리 측정을 잃을 이유가 없다.
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

# 비밀번호는 컨테이너 env 안에만 있다. 여기로도 SSM 명령문으로도 안 나온다.
#
# SQL은 base64로 넘긴다. 그냥 끼워 넣으면 'COMPLETED' 같은 작은따옴표가
# sh -c '...' 를 중간에 끊는다. base64 출력에는 따옴표도 공백도 없다.
db_query() {
  local encoded
  encoded="$(printf '%s' "$1" | base64 | tr -d '\n')" || return 1
  ssm_run "echo ${encoded} | base64 -d | docker exec -i ${MYSQL_CONTAINER} sh -c 'MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" exec mysql -N -B -uroot ${MYSQL_DATABASE}'" \
    | tr -d '\r'
}

gateway_stop() { ssm_run "docker rm -f gateway >/dev/null 2>&1 || true" >/dev/null; }

gateway_start() {
  ssm_run "JAVA_TOOL_OPTIONS='${JAVA_OPTS}' /usr/local/bin/gateway-run.sh ${POOL_ARGS} ${EXTRA_ARGS}" >/dev/null
}

# 회차 사이에 DB를 비운다. 게이트웨이 재시작은 MySQL을 건드리지 않고
# ddl-auto도 validate라 스키마가 다시 만들어지지 않는다. 안 비우면 회차2가
# 회차1의 행까지 센다. 외래키 때문에 TRUNCATE 전에 체크를 끈다.
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

# 진행 중인 트랜잭션이 0이 될 때까지 기다린다. 추측이 아니라 사실이다.
drain_wait() {
  local waited=0 remaining
  while [ "${waited}" -lt "${DRAIN_CAP_SECONDS}" ]; do
    remaining="$(db_query "SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='IN_PROGRESS';")" || remaining=""
    case "${remaining}" in
      ''|*[!0-9]*) ;;                       # 못 읽었으면 다음 폴링에서 다시
      0) echo "drained after ${waited}s"; return 0 ;;
      *) : ;;
    esac
    sleep "${DRAIN_POLL_SECONDS}"
    waited=$(( waited + DRAIN_POLL_SECONDS ))
  done
  echo "drain hit the ${DRAIN_CAP_SECONDS}s cap (still in progress: ${remaining:-unknown})" >&2
  return 1
}

COUNT_SQL="SELECT
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='COMPLETED'),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='PARTIAL_FAILURE'),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='FAILED' AND fail_reason IS NULL),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='FAILED' AND fail_reason IS NOT NULL),
 (SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='IN_PROGRESS'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=1),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='REJECTED'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='SUBMIT_ERROR'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code='EXCEPTION'),
 (SELECT COUNT(*) FROM bank_call_result WHERE success=0 AND response_code NOT IN ('REJECTED','SUBMIT_ERROR','EXCEPTION')),
 (SELECT COUNT(*) FROM bank_call_result),
 (SELECT COUNT(*) FROM loan_limit_batch_run r WHERE (SELECT COUNT(*) FROM bank_call_result b WHERE b.run_id=r.id) < r.requested_bank_count);"

# 숫자 12개를 받아 JSON으로 만든다. 다섯 버킷의 합이 전체 행 수와 같은지
# 여기서 확인한다 — 예전에 어느 카운터에도 안 잡히는 상태가 생겨서 run이
# 통째로 집계에서 빠진 적이 있다. 합이 맞으면 그런 누락이 불가능하다.
collect_counts() {
  local row
  row="$(db_query "${COUNT_SQL}")" || return 1

  local fields
  # shellcheck disable=SC2086
  set -- ${row}
  fields=$#
  [ "${fields}" -eq 12 ] || { echo "expected 12 counts, got ${fields}: ${row}" >&2; return 1; }

  local n
  for n in "$@"; do
    case "${n}" in
      ''|*[!0-9]*) echo "non-numeric count in: ${row}" >&2; return 1 ;;
    esac
  done

  local bucket_sum=$(( $6 + $7 + $8 + $9 + ${10} ))
  local balanced=true
  if [ "${bucket_sum}" -ne "${11}" ]; then
    balanced=false
    echo "bucket sum ${bucket_sum} != bank_call_result rows ${11}" >&2
  fi

  jq -n \
    --argjson completed "$1" --argjson partial "$2" \
    --argjson failed_saturated "$3" --argjson failed_error "$4" \
    --argjson in_progress "$5" \
    --argjson calls_success "$6" --argjson calls_rejected "$7" \
    --argjson calls_submit_error "$8" --argjson calls_exception "$9" \
    --argjson calls_other "${10}" --argjson calls_total "${11}" \
    --argjson runs_missing_rows "${12}" \
    --argjson balanced "${balanced}" \
    '{completed:$completed, partial:$partial,
      failed_saturated:$failed_saturated, failed_error:$failed_error,
      in_progress:$in_progress,
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
echo "pool      : ${POOL_LABEL}"
echo "rpms      : ${RPMS}"
echo "repeats   : ${REPEATS}"
echo "duration  : ${DURATION} (${DURATION_SECONDS}s)"
echo "results   : ${RESULTS_ROOT}"
echo ""

trap 'gateway_stop || true' EXIT

invalid_rounds=0

for MODE in ${MODES}; do
for rpm in ${RPMS}; do
for rep in $(seq 1 "${REPEATS}"); do
  run_id="${MODE}/${POOL_LABEL}/rpm${rpm}/rep${rep}"
  out_dir="${RESULTS_ROOT}/${MODE}/${POOL_LABEL}/rpm${rpm}"
  mkdir -p "${out_dir}"

  echo "=============================================================="
  echo "  ${CONFIG_NAME} / ${run_id}"
  echo "=============================================================="

  # 이 회차가 쓸 만한 숫자를 냈는지 여기 하나로 판단한다. 어느 단계가
  # 실패하든 결과는 같다 — 이 회차만 버리고 다음으로 간다.
  round_ok=true
  drain_capped=false
  counts="null"

  gateway_stop || true

  if ! db_reset; then
    echo "could not clear tables, skipping ${run_id}" >&2
    round_ok=false
  fi

  if [ "${round_ok}" = true ] && ! gateway_start; then
    echo "gateway failed to start, skipping ${run_id}" >&2
    round_ok=false
  fi

  digest="unknown"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  if [ "${round_ok}" = true ]; then
    digest="$(resolve_digest)" || digest="unknown"

    summary_file="${out_dir}/rep${rep}.summary.json"
    if ! ( cd "${SCRIPT_DIR}" && \
      MODE="${MODE}" \
      LOAD_RPM="${rpm}" \
      DURATION="${DURATION}" \
      BASE_URL="${BASE_URL}" \
      MAX_WAIT_MS="${MAX_WAIT_MS}" \
      POLL_MAX_MS="${POLL_MAX_MS}" \
      RUN_ID="${CONFIG_NAME}-${MODE}-${POOL_LABEL}-rpm${rpm}-rep${rep}" \
      k6 run --summary-export="${summary_file}" load.js )
    then
      # k6가 죽으면 DB는 0을 돌려준다. 정상적으로 0인 것과 구분이 안 되므로
      # 여기서 무효로 표시해야 한다.
      echo "k6 exited non-zero for ${run_id}" >&2
      round_ok=false
    fi
  fi

  # k6는 DURATION에서 멈추지만 트랜잭션 e2e 하한이 31초다. 막바지에 넣은
  # 것들이 아직 돌고 있으므로 끝날 때까지 기다린 뒤에 센다.
  if [ "${round_ok}" = true ] && ! drain_wait; then
    drain_capped=true
    round_ok=false
  fi

  if [ "${round_ok}" = true ]; then
    if ! counts="$(collect_counts)"; then
      counts="null"
      round_ok=false
    elif [ "$(echo "${counts}" | jq -r '.balanced')" != "true" ]; then
      round_ok=false
    fi
  fi

  gateway_stop || true

  [ "${round_ok}" = true ] || invalid_rounds=$(( invalid_rounds + 1 ))

  jq -n \
    --arg config "${CONFIG_NAME}" --arg run_id "${run_id}" \
    --arg mode "${MODE}" --arg pool "${POOL_LABEL}" \
    --argjson rpm "${rpm}" --argjson rep "${rep}" \
    --arg duration "${DURATION}" \
    --argjson duration_seconds "${DURATION_SECONDS}" \
    --arg java_opts "${JAVA_OPTS}" \
    --arg spring_args "${POOL_ARGS} ${EXTRA_ARGS}" \
    --arg image "${digest}" --arg k6_version "${K6_VERSION}" \
    --arg started_at "${started_at}" \
    --argjson valid "${round_ok}" \
    --argjson drain_capped "${drain_capped}" \
    --argjson counts "${counts}" \
    '{config:$config, run_id:$run_id, mode:$mode, pool:$pool, rpm:$rpm,
      repeat:$rep, duration:$duration, duration_seconds:$duration_seconds,
      java_opts:$java_opts, spring_args:$spring_args, gateway_image:$image,
      k6_version:$k6_version, started_at:$started_at,
      valid:$valid, drain_capped:$drain_capped, counts:$counts}' \
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
  echo "${invalid_rounds} round(s) produced no usable numbers; they are marked valid=false" >&2
fi
