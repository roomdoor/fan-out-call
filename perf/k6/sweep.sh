#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
LOG_DIR="${SCRIPT_DIR}/logs"

# Accepts either bare pool sizes (queue from QUEUE_CAPACITY env, default 200)
# or "pool:queue" pairs (per-run queue), e.g. ./sweep.sh 64:256 128:512 256:1024 512:2048
PAIRS_DEFAULT=(64:200 128:200 256:200 512:200)
if [ $# -gt 0 ]; then
  PAIRS=("$@")
else
  PAIRS=("${PAIRS_DEFAULT[@]}")
fi

DEFAULT_QUEUE_CAPACITY="${QUEUE_CAPACITY:-200}"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
BASE_URL="http://localhost:${GATEWAY_PORT}"
MODE="${MODE:-async-threadpool}"
PROFILE="${PROFILE:-stress}"

mkdir -p "${RESULTS_DIR}" "${LOG_DIR}"

GATEWAY_JAR="$(ls -1 ${GATEWAY_DIR}/build/libs/*.jar 2>/dev/null | grep -v plain | head -1 || true)"
if [ -z "${GATEWAY_JAR}" ]; then
  echo "ERROR: Gateway jar not found in ${GATEWAY_DIR}/build/libs/. Run ./gradlew bootJar first." >&2
  exit 1
fi
echo "Gateway jar: ${GATEWAY_JAR}"

kill_gateway() {
  local pid="$1"
  if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
    for i in {1..30}; do
      kill -0 "${pid}" 2>/dev/null || return 0
      sleep 1
    done
    kill -9 "${pid}" 2>/dev/null || true
  fi
}

# Make sure nothing already binds 8080.
if lsof -ti tcp:${GATEWAY_PORT} >/dev/null 2>&1; then
  echo "ERROR: Port ${GATEWAY_PORT} already in use. Free it before running sweep." >&2
  lsof -i tcp:${GATEWAY_PORT}
  exit 1
fi

run_one() {
  local pool="$1"
  local queue="$2"
  local tag="pool_${pool}_q${queue}"
  local log_file="${LOG_DIR}/gateway_${tag}.log"
  local summary_file="${RESULTS_DIR}/${tag}.json"

  echo ""
  echo "========================================================"
  echo "==  Run: ${tag}  (core=${pool}, max=${pool}, queue=${queue})"
  echo "========================================================"

  : > "${log_file}"

  java -jar "${GATEWAY_JAR}" \
    --app.async-thread-pool.core-pool-size=${pool} \
    --app.async-thread-pool.max-pool-size=${pool} \
    --app.async-thread-pool.queue-capacity=${queue} \
    --app.web-client-fan-out.routing-mode=sharded \
    > "${log_file}" 2>&1 &
  local pid=$!
  echo "Gateway PID: ${pid}, log: ${log_file}"

  local ready=false
  for i in {1..120}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "ERROR: Gateway process died during startup. Last 50 lines:" >&2
      tail -50 "${log_file}" >&2
      return 1
    fi
    status=$(curl -sf "${BASE_URL}/actuator/health" 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)
    if [ "${status}" = "UP" ]; then
      echo "Gateway healthy after ${i}s"
      ready=true
      break
    fi
    sleep 1
  done

  if [ "${ready}" != true ]; then
    echo "ERROR: Gateway never reported UP. Last 50 lines:" >&2
    tail -50 "${log_file}" >&2
    kill_gateway "${pid}"
    return 1
  fi

  # Quick warm-up so JIT/connection pools settle.
  sleep 3

  echo "Running k6 ${PROFILE} (MODE=${MODE}, MAX_WAIT_MS=${MAX_WAIT_MS:-60000})..."
  if ! ( cd "${SCRIPT_DIR}" && MODE="${MODE}" BASE_URL="${BASE_URL}" \
          MAX_WAIT_MS="${MAX_WAIT_MS:-60000}" POLL_MAX_MS="${POLL_MAX_MS:-5000}" \
          k6 run --summary-export="${summary_file}" "${PROFILE}.js" ); then
    echo "WARN: k6 reported non-zero exit (thresholds may have failed). Summary still saved."
  fi

  if [ ! -s "${summary_file}" ]; then
    echo "ERROR: Summary file empty: ${summary_file}" >&2
    kill_gateway "${pid}"
    return 1
  fi

  echo "Stopping gateway PID ${pid}..."
  kill_gateway "${pid}"
  echo "Done ${tag}. Summary: ${summary_file}"
}

echo "Sweep pairs: ${PAIRS[*]}"
echo "Mode: ${MODE}"
echo "Profile: ${PROFILE}"
echo "Base URL: ${BASE_URL}"
echo ""

for pair in "${PAIRS[@]}"; do
  if [[ "${pair}" == *:* ]]; then
    pool="${pair%%:*}"
    queue="${pair##*:}"
  else
    pool="${pair}"
    queue="${DEFAULT_QUEUE_CAPACITY}"
  fi
  run_one "${pool}" "${queue}" || { echo "Sweep aborted at pair=${pair}"; exit 1; }
done

echo ""
echo "All runs complete. Results in: ${RESULTS_DIR}"
ls -la "${RESULTS_DIR}"
