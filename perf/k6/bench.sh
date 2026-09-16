#!/bin/bash
# 측정 오케스트레이터. C 호스트(k6)에서 돈다.
#
# 게이트웨이(A 호스트)는 SSM으로 제어한다. k6는 이 기계에서 직접 돌린다.
# 측정 대상 호스트에는 게이트웨이와 MySQL 외에 아무것도 올리지 않는다.
#
#   ./bench.sh config/v15-baseline.env
#
# 회차마다 manifest.json을 남긴다. 어떤 이미지 다이제스트로, 어떤 인자로,
# 어떤 mock 프로파일에서 잰 숫자인지가 결과 옆에 붙어 있어야
# v6이나 v13 같은 사고(조건을 모른 채 측정)가 반복되지 않는다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONFIG="${1:-}"
if [ -z "${CONFIG}" ]; then
  echo "usage: $0 <config.env>" >&2
  echo "example: $0 config/v15-baseline.env" >&2
  exit 1
fi
[ -f "${CONFIG}" ] || { echo "config not found: ${CONFIG}" >&2; exit 1; }

# shellcheck disable=SC1090
source "${CONFIG}"
CONFIG_NAME="$(basename "${CONFIG}" .env)"

# Terraform이 /etc/profile.d/bench.sh 에 심어둔 값들
: "${GATEWAY_INSTANCE_ID:?GATEWAY_INSTANCE_ID not set (source /etc/profile.d/bench.sh)}"
: "${AWS_REGION:?AWS_REGION not set}"
: "${BASE_URL:?BASE_URL not set}"

MODE="${MODE:-coroutine}"
RPMS="${RPMS:?RPMS not set in config}"
POOLS="${POOLS:-default}"
REPEATS="${REPEATS:-1}"
DURATION="${DURATION:-2m}"
MAX_WAIT_MS="${MAX_WAIT_MS:-120000}"
POLL_MAX_MS="${POLL_MAX_MS:-5000}"
# 이론 하한이 31초다. 로그 카운트 전에 남은 트랜잭션이 끝날 시간을 준다.
DRAIN_SECONDS="${DRAIN_SECONDS:-45}"
JAVA_OPTS="${JAVA_OPTS:-}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

RESULTS_ROOT="${SCRIPT_DIR}/results/${CONFIG_NAME}"
mkdir -p "${RESULTS_ROOT}"

# ---------------------------------------------------------------------------
# SSM 헬퍼
#
# stdout은 API가 24,000자에서 잘라낸다. 그래서 게이트웨이 로그 원본을
# 이쪽으로 끌어오지 않는다. A에서 세고 숫자만 받는다.
# ---------------------------------------------------------------------------

ssm_run() {
  local instance="$1" script="$2"
  local params cmd_id status

  params="$(jq -n --arg s "${script}" '{commands: [$s], executionTimeout: ["3600"]}')"

  cmd_id="$(aws ssm send-command \
    --region "${AWS_REGION}" \
    --instance-ids "${instance}" \
    --document-name AWS-RunShellScript \
    --parameters "${params}" \
    --query 'Command.CommandId' --output text)"

  local done=false
  for _ in $(seq 1 300); do
    status="$(aws ssm get-command-invocation \
      --region "${AWS_REGION}" \
      --command-id "${cmd_id}" \
      --instance-id "${instance}" \
      --query 'Status' --output text 2>/dev/null || echo Pending)"
    case "${status}" in
      Success) done=true; break ;;
      Failed|Cancelled|TimedOut)
        echo "SSM command ${status}:" >&2
        aws ssm get-command-invocation --region "${AWS_REGION}" \
          --command-id "${cmd_id}" --instance-id "${instance}" \
          --query 'StandardErrorContent' --output text >&2
        return 1
        ;;
    esac
    sleep 2
  done

  # 폴링이 끝났는데 Success가 아니면 실패로 처리한다. 그냥 빠져나가면
  # 부분 출력이 정상 결과처럼 반환되고, 뜨지도 않은 게이트웨이에
  # k6를 돌려서 그 숫자가 유효한 회차로 기록된다.
  if [ "${done}" != true ]; then
    echo "SSM command did not finish in time (last status: ${status})" >&2
    return 1
  fi

  aws ssm get-command-invocation \
    --region "${AWS_REGION}" \
    --command-id "${cmd_id}" \
    --instance-id "${instance}" \
    --query 'StandardOutputContent' --output text
}

# 이미지를 태그가 아니라 다이제스트로 기록한다.
# latest가 가리키는 대상이 바뀌어도 어느 빌드로 쟀는지가 남는다.
resolve_digest() {
  ssm_run "${GATEWAY_INSTANCE_ID}" \
    "docker inspect --format '{{index .RepoDigests 0}}' \$(docker inspect --format '{{.Config.Image}}' gateway 2>/dev/null || echo none) 2>/dev/null || echo unknown" \
    | tr -d '\r\n'
}

start_gateway() {
  local spring_args="$1"
  ssm_run "${GATEWAY_INSTANCE_ID}" \
    "JAVA_TOOL_OPTIONS='${JAVA_OPTS}' /usr/local/bin/gateway-run.sh ${spring_args} ${EXTRA_ARGS}" >/dev/null
}

stop_gateway() {
  ssm_run "${GATEWAY_INSTANCE_ID}" "docker rm -f gateway >/dev/null 2>&1 || true" >/dev/null
}

# 실효 처리율은 k6 지표로 셀 수 없다. 풀에서 거부된 트랜잭션도
# 빠른 202를 받아 k6에는 성공으로 보이기 때문이다. 게이트웨이 로그를 센다.
# 로그 원본은 A의 /var/log/bench/ 에 남겨두고 여기서는 숫자만 받는다.
collect_counts() {
  local run_id="$1"
  ssm_run "${GATEWAY_INSTANCE_ID}" "$(cat <<SCRIPT
mkdir -p /var/log/bench
docker logs gateway > /var/log/bench/$(echo "${run_id}" | tr '/' '_').log 2>&1
L=/var/log/bench/$(echo "${run_id}" | tr '/' '_').log
printf '{"completed":%s,"partial":%s,"failed":%s,"run_errors":%s,"rejected_calls":%s,"persist_failures":%s}' \
  "\$(grep -c 'Background fan-out completed.*status=COMPLETED' \$L || true)" \
  "\$(grep -c 'Background fan-out completed.*status=PARTIAL' \$L || true)" \
  "\$(grep -c 'Background fan-out completed.*status=FAILED' \$L || true)" \
  "\$(grep -c 'Run marked as FAILED' \$L || true)" \
  "\$(grep -c 'Bank call submission rejected' \$L || true)" \
  "\$(grep -c 'Result persistence failed bankCode' \$L || true)"
SCRIPT
)"
}

# ---------------------------------------------------------------------------
# 측정 루프
# ---------------------------------------------------------------------------

echo "config      : ${CONFIG_NAME}"
echo "mode        : ${MODE}"
echo "pools       : ${POOLS}"
echo "rpms        : ${RPMS}"
echo "repeats     : ${REPEATS}"
echo "duration    : ${DURATION}"
echo "results     : ${RESULTS_ROOT}"
echo ""

trap 'stop_gateway || true' EXIT

for pool in ${POOLS}; do
  if [ "${pool}" = "default" ]; then
    pool_label="default"
    pool_args=""
  else
    # pool 또는 pool:queue 형태를 받는다
    core="${pool%%:*}"
    queue="${pool#*:}"
    [ "${queue}" = "${pool}" ] && queue=200
    pool_label="pool${core}-q${queue}"
    pool_args="--app.async-thread-pool.core-pool-size=${core} --app.async-thread-pool.max-pool-size=${core} --app.async-thread-pool.queue-capacity=${queue}"
  fi

  for rpm in ${RPMS}; do
    for rep in $(seq 1 "${REPEATS}"); do
      run_id="${pool_label}/rpm${rpm}/rep${rep}"
      out_dir="${RESULTS_ROOT}/${pool_label}/rpm${rpm}"
      mkdir -p "${out_dir}"

      echo "=============================================================="
      echo "  ${CONFIG_NAME} / ${run_id}"
      echo "=============================================================="

      # 회차마다 재기동한다. clean state 보장과 JVM 워밍업 변동 통제.
      stop_gateway
      if ! start_gateway "${pool_args}"; then
        echo "gateway failed to start, skipping ${run_id}" >&2
        continue
      fi

      digest="$(resolve_digest)"
      started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

      summary_file="${out_dir}/rep${rep}.summary.json"
      ( cd "${SCRIPT_DIR}" && \
        MODE="${MODE}" \
        LOAD_RPM="${rpm}" \
        DURATION="${DURATION}" \
        BASE_URL="${BASE_URL}" \
        MAX_WAIT_MS="${MAX_WAIT_MS}" \
        POLL_MAX_MS="${POLL_MAX_MS}" \
        RUN_ID="${CONFIG_NAME}-${pool_label}-rpm${rpm}-rep${rep}" \
        k6 run --summary-export="${summary_file}" load.js ) || \
        echo "k6 exited non-zero for ${run_id} (continuing)" >&2

      # k6는 DURATION + gracefulStop에서 멈추지만 트랜잭션 e2e가 최소 31초다.
      # 막바지에 submit된 건들은 아직 COMPLETED 로그를 남기지 않았다.
      # 바로 세면 처리율이 체계적으로 낮게 나온다.
      echo "draining in-flight transactions (${DRAIN_SECONDS}s)..."
      sleep "${DRAIN_SECONDS}"

      # SSM 일시 장애로 sweep 전체를 잃지 않는다. k6 실패를 넘기는 것과 같은 기준.
      counts="$(collect_counts "${run_id}")" || counts=""
      # --output text 는 빈 출력을 문자열 None으로 준다. jq --argjson이 죽는다.
      case "${counts}" in
        ''|None|null) counts="null" ;;
      esac
      if ! echo "${counts}" | jq -e . >/dev/null 2>&1; then
        echo "count collection returned unusable output for ${run_id}" >&2
        counts="null"
      fi

      stop_gateway || true

      # 이 회차를 재현하는 데 필요한 모든 것을 결과 옆에 남긴다.
      jq -n \
        --arg config "${CONFIG_NAME}" \
        --arg run_id "${run_id}" \
        --arg mode "${MODE}" \
        --arg pool "${pool_label}" \
        --argjson rpm "${rpm}" \
        --argjson rep "${rep}" \
        --arg duration "${DURATION}" \
        --arg java_opts "${JAVA_OPTS}" \
        --arg spring_args "${pool_args} ${EXTRA_ARGS}" \
        --arg image "${digest}" \
        --arg started_at "${started_at}" \
        --argjson counts "${counts}" \
        '{config:$config, run_id:$run_id, mode:$mode, pool:$pool, rpm:$rpm, repeat:$rep,
          duration:$duration, java_opts:$java_opts, spring_args:$spring_args,
          gateway_image:$image, started_at:$started_at, gateway_counts:$counts}' \
        > "${out_dir}/rep${rep}.manifest.json"

      echo "counts: ${counts}"
      echo ""
    done
  done
done

echo "done. parse with:"
echo "  node ${SCRIPT_DIR}/parse.mjs ${RESULTS_ROOT}"
