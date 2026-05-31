#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

# Pool:queue configs to test (core=max=pool)
CONFIGS_DEFAULT=("512:2048" "1024:2048" "2048:2048")
RPMS_DEFAULT=(40 50 60 70 80 90 100)

if [ -n "${CONFIGS:-}" ]; then
  read -r -a CONFIGS <<< "${CONFIGS}"
else
  CONFIGS=("${CONFIGS_DEFAULT[@]}")
fi
if [ -n "${RPMS:-}" ]; then
  read -r -a RPMS <<< "${RPMS}"
else
  RPMS=("${RPMS_DEFAULT[@]}")
fi

echo "Matrix sweep:"
echo "  Configs: ${CONFIGS[*]}"
echo "  RPMs: ${RPMS[*]}"
echo "  Total runs: $(( ${#CONFIGS[@]} * ${#RPMS[@]} ))"
echo ""

for pair in "${CONFIGS[@]}"; do
  pool="${pair%%:*}"
  queue="${pair##*:}"
  subdir="${RESULTS_DIR}/matrix-pool${pool}-q${queue}"

  echo "========================================================"
  echo "Config: core=max=${pool}, queue=${queue}"
  echo "Output: ${subdir}"
  echo "========================================================"

  mkdir -p "${subdir}"

  CORE_POOL="${pool}" MAX_POOL="${pool}" QUEUE="${queue}" \
    "${SCRIPT_DIR}/load_sweep.sh" "${RPMS[@]}" || {
      echo "Sweep failed for config ${pair}"
      exit 1
    }

  # Move per-run results into subdir
  mv "${RESULTS_DIR}/rpm_"*.json "${subdir}/" 2>/dev/null || true
  echo ""
  echo "Config ${pair} done. Results in ${subdir}"
  echo ""
done

echo ""
echo "All matrix configs complete."
ls -la "${RESULTS_DIR}"
