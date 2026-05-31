#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
LOG_DIR="${SCRIPT_DIR}/logs"

RPMS_DEFAULT=(20 30 40 50 60 70 80 90 100)
if [ $# -gt 0 ]; then
  RPMS=("$@")
else
  RPMS=("${RPMS_DEFAULT[@]}")
fi

POOL="${POOL:-512}"
QUEUE="${QUEUE:-2048}"
CORE_POOL="${CORE_POOL:-${POOL}}"
MAX_POOL="${MAX_POOL:-${POOL}}"
DURATION="${DURATION:-2m}"
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
BASE_URL="http://localhost:${GATEWAY_PORT}"
MODE="${MODE:-async-threadpool}"
MAX_WAIT_MS="${MAX_WAIT_MS:-90000}"
POLL_MAX_MS="${POLL_MAX_MS:-5000}"

mkdir -p "${RESULTS_DIR}" "${LOG_DIR}"

GATEWAY_JAR="$(ls -1 ${GATEWAY_DIR}/build/libs/*.jar 2>/dev/null | grep -v plain | head -1 || true)"
if [ -z "${GATEWAY_JAR}" ]; then
  echo "ERROR: Gateway jar not found in ${GATEWAY_DIR}/build/libs/." >&2
  exit 1
fi

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

if lsof -ti tcp:${GATEWAY_PORT} >/dev/null 2>&1; then
  echo "ERROR: Port ${GATEWAY_PORT} already in use." >&2
  exit 1
fi

run_one() {
  local rpm="$1"
  local tag="rpm_${rpm}"
  local log_file="${LOG_DIR}/gateway_${tag}.log"
  local summary_file="${RESULTS_DIR}/${tag}.json"

  echo ""
  echo "========================================================"
  echo "==  Load: ${rpm} RPM (core=${CORE_POOL}, max=${MAX_POOL}, queue=${QUEUE}, duration=${DURATION})"
  echo "========================================================"

  : > "${log_file}"

  java -jar "${GATEWAY_JAR}" \
    --app.async-thread-pool.core-pool-size=${CORE_POOL} \
    --app.async-thread-pool.max-pool-size=${MAX_POOL} \
    --app.async-thread-pool.queue-capacity=${QUEUE} \
    --app.web-client-fan-out.routing-mode=sharded \
    > "${log_file}" 2>&1 &
  local pid=$!
  echo "Gateway PID: ${pid}"

  local ready=false
  for i in {1..120}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "ERROR: Gateway died. Tail:" >&2
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
    echo "ERROR: Gateway never reported UP." >&2
    tail -50 "${log_file}" >&2
    kill_gateway "${pid}"
    return 1
  fi

  sleep 3

  echo "Running k6 load.js (LOAD_RPM=${rpm}, DURATION=${DURATION})..."
  if ! ( cd "${SCRIPT_DIR}" && MODE="${MODE}" BASE_URL="${BASE_URL}" \
          LOAD_RPM="${rpm}" DURATION="${DURATION}" \
          MAX_WAIT_MS="${MAX_WAIT_MS}" POLL_MAX_MS="${POLL_MAX_MS}" \
          k6 run --summary-export="${summary_file}" load.js ); then
    echo "WARN: k6 non-zero exit. Summary still saved."
  fi

  if [ ! -s "${summary_file}" ]; then
    echo "ERROR: Summary file empty: ${summary_file}" >&2
    kill_gateway "${pid}"
    return 1
  fi

  echo "Stopping gateway PID ${pid}..."
  kill_gateway "${pid}"
  echo "Done ${tag}."
}

echo "Sweep RPMs: ${RPMS[*]}"
echo "Core: ${CORE_POOL}, Max: ${MAX_POOL}, Queue: ${QUEUE}, Duration: ${DURATION}"
echo "Mode: ${MODE}, Base URL: ${BASE_URL}"
echo ""

for rpm in "${RPMS[@]}"; do
  run_one "${rpm}" || { echo "Sweep aborted at rpm=${rpm}"; exit 1; }
done

echo ""
echo "All loads complete. Results in: ${RESULTS_DIR}"
ls -la "${RESULTS_DIR}"
