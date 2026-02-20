#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_ID="${TIMESTAMP}_${MODE}_${PROFILE}"
EVIDENCE_DIR="${GATEWAY_DIR}/.sisyphus/evidence/perf/${RUN_ID}"

echo "=== Loan Limit Gateway Performance Test ==="
echo "Run ID: ${RUN_ID}"
echo "Mode: ${MODE}"
echo "Profile: ${PROFILE}"
echo "Evidence Dir: ${EVIDENCE_DIR}"
echo ""

mkdir -p "${EVIDENCE_DIR}"

collect_git_info() {
  local repo_dir=$1
  local output_file=$2
  
  if [ -d "${repo_dir}/.git" ]; then
    cd "${repo_dir}"
    cat > "${output_file}" << EOF
{
  "commit": "$(git rev-parse HEAD)",
  "branch": "$(git rev-parse --abbrev-ref HEAD)",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
  else
    echo '{"commit": "unknown", "branch": "unknown"}' > "${output_file}"
  fi
}

collect_config() {
  local output_file=$1
  
  cat > "${output_file}" << EOF
{
  "mode": "${MODE}",
  "profile": "${PROFILE}",
  "baseUrl": "${BASE_URL:-http://localhost:8080}",
  "maxWaitMs": ${MAX_WAIT_MS:-60000},
  "pollMinMs": ${POLL_MIN_MS:-100},
  "pollMaxMs": ${POLL_MAX_MS:-5000},
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
}

collect_host_info() {
  local output_file=$1
  
  cat > "${output_file}" << EOF
{
  "hostname": "$(hostname)",
  "cpu_cores": $(sysctl -n hw.ncpu 2>/dev/null || echo 'null'),
  "memory_bytes": $(sysctl -n hw.memsize 2>/dev/null || echo 'null'),
  "os": "$(uname -s)",
  "arch": "$(uname -m)"
}
EOF
}

create_manifest() {
  cat > "${EVIDENCE_DIR}/manifest.json" << EOF
{
  "runId": "${RUN_ID}",
  "mode": "${MODE}",
  "profile": "${PROFILE}",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "gateway": $(cat "${EVIDENCE_DIR}/gateway-git.json"),
  "mockServer": $(cat "${EVIDENCE_DIR}/mockserver-git.json"),
  "config": $(cat "${EVIDENCE_DIR}/config.json"),
  "host": $(cat "${EVIDENCE_DIR}/host.json"),
  "systemMetricsSummaryFile": "system-metrics-summary.json"
}
EOF
}

metric_value() {
  local metric_name=$1
  local metric_tag=$2
  local base="${BASE_URL:-http://localhost:8080}"

  if [ -n "$metric_tag" ]; then
    curl -sfG "${base}/actuator/metrics/${metric_name}" --data-urlencode "tag=${metric_tag}" 2>/dev/null | python3 - <<'PY'
import json, sys
try:
    data = json.load(sys.stdin)
    measurements = data.get("measurements") or []
    value = None
    for m in measurements:
        if m.get("statistic") == "VALUE":
            value = m.get("value")
            break
    if value is None and measurements:
        value = measurements[0].get("value")
    print("null" if value is None else value)
except Exception:
    print("null")
PY
  else
    curl -sf "${base}/actuator/metrics/${metric_name}" 2>/dev/null | python3 - <<'PY'
import json, sys
try:
    data = json.load(sys.stdin)
    measurements = data.get("measurements") or []
    value = None
    for m in measurements:
        if m.get("statistic") == "VALUE":
            value = m.get("value")
            break
    if value is None and measurements:
        value = measurements[0].get("value")
    print("null" if value is None else value)
except Exception:
    print("null")
PY
  fi
}

start_metric_sampler() {
  METRIC_FILE="${EVIDENCE_DIR}/system-metrics.ndjson"
  : > "${METRIC_FILE}"

  (
    while true; do
      ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      process_cpu=$(metric_value "process.cpu.usage" "")
      system_cpu=$(metric_value "system.cpu.usage" "")
      jvm_threads_live=$(metric_value "jvm.threads.live" "")
      jvm_threads_peak=$(metric_value "jvm.threads.peak" "")

      executor_active=$(metric_value "executor.active" "name:bankAsyncExecutor")
      [ "$executor_active" = "null" ] && executor_active=$(metric_value "executor.active" "")

      executor_pool_size=$(metric_value "executor.pool.size" "name:bankAsyncExecutor")
      [ "$executor_pool_size" = "null" ] && executor_pool_size=$(metric_value "executor.pool.size" "")

      executor_queued=$(metric_value "executor.queued" "name:bankAsyncExecutor")
      [ "$executor_queued" = "null" ] && executor_queued=$(metric_value "executor.queued" "")

      printf '{"ts":"%s","processCpu":%s,"systemCpu":%s,"jvmThreadsLive":%s,"jvmThreadsPeak":%s,"executorActive":%s,"executorPoolSize":%s,"executorQueued":%s}\n' \
        "$ts" "$process_cpu" "$system_cpu" "$jvm_threads_live" "$jvm_threads_peak" "$executor_active" "$executor_pool_size" "$executor_queued" >> "${METRIC_FILE}"

      sleep 1
    done
  ) &

  METRIC_SAMPLER_PID=$!
}

stop_metric_sampler() {
  if [ -n "${METRIC_SAMPLER_PID:-}" ]; then
    kill "${METRIC_SAMPLER_PID}" 2>/dev/null || true
    wait "${METRIC_SAMPLER_PID}" 2>/dev/null || true
  fi
}

summarize_metrics() {
  local metric_file="${EVIDENCE_DIR}/system-metrics.ndjson"
  local summary_file="${EVIDENCE_DIR}/system-metrics-summary.json"

  python3 - "$metric_file" "$summary_file" <<'PY'
import json, sys
from statistics import mean

metric_file, summary_file = sys.argv[1], sys.argv[2]
keys = [
    "processCpu", "systemCpu", "jvmThreadsLive", "jvmThreadsPeak",
    "executorActive", "executorPoolSize", "executorQueued"
]
values = {k: [] for k in keys}
samples = 0

try:
    with open(metric_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            samples += 1
            row = json.loads(line)
            for k in keys:
                v = row.get(k)
                if isinstance(v, (int, float)):
                    values[k].append(v)
except FileNotFoundError:
    pass

def summary(arr):
    if not arr:
        return {"avg": None, "max": None}
    return {"avg": mean(arr), "max": max(arr)}

out = {
    "samples": samples,
    "processCpu": summary(values["processCpu"]),
    "systemCpu": summary(values["systemCpu"]),
    "jvmThreadsLive": summary(values["jvmThreadsLive"]),
    "jvmThreadsPeak": summary(values["jvmThreadsPeak"]),
    "executorActive": summary(values["executorActive"]),
    "executorPoolSize": summary(values["executorPoolSize"]),
    "executorQueued": summary(values["executorQueued"]),
}

with open(summary_file, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2)
PY
}

echo "Collecting run metadata..."
collect_git_info "${GATEWAY_DIR}" "${EVIDENCE_DIR}/gateway-git.json"
collect_git_info "/Users/sihwa/IdeaProjects/loan-limit-mock-server" "${EVIDENCE_DIR}/mockserver-git.json"
collect_config "${EVIDENCE_DIR}/config.json"
collect_host_info "${EVIDENCE_DIR}/host.json"

echo "Running preflight checks..."
if ! curl -sf "${BASE_URL:-http://localhost:8080}/actuator/health" >/dev/null; then
  echo "Preflight failed: gateway is not reachable at ${BASE_URL:-http://localhost:8080}"
  echo "Start loan-limit-gateway first, then retry."
  exit 1
fi

echo "Running k6 test..."
cd "${SCRIPT_DIR}"
start_metric_sampler
k6 run --summary-export="${EVIDENCE_DIR}/summary.json" "${PROFILE}.js"
stop_metric_sampler
summarize_metrics

echo "Creating manifest..."
create_manifest

echo ""
echo "=== Run Complete ==="
echo "Evidence saved to: ${EVIDENCE_DIR}"
echo "Summary: ${EVIDENCE_DIR}/summary.json"
echo "Manifest: ${EVIDENCE_DIR}/manifest.json"
