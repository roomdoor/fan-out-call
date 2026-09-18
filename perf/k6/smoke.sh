#!/bin/bash
# 배포 직후 파이프라인 점검. 부하를 걸지 않는다.
#
#   ./smoke.sh
#
# 모드마다 트랜잭션 1건씩 넣고, 은행 50곳이 호출되고 결과가 DB에 저장되는지
# 본다. 게이트웨이는 한 번만 띄운다. 2~3분.
#
# 제출은 k6(smoke.js)로 한다. 실측이 쓰는 lib/ 와 같은 경로를 타므로 k6 설치,
# 시나리오 파싱, 게이트웨이 폴링까지 여기서 걸린다. curl 로 하면 그게 다 빠진다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${GATEWAY_INSTANCE_ID:?GATEWAY_INSTANCE_ID not set (source /etc/profile.d/bench.sh)}"
: "${AWS_REGION:?AWS_REGION not set}"
: "${BASE_URL:?BASE_URL not set}"

MODES="${MODES:-coroutine async-threadpool webclient}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_DATABASE="${MYSQL_DATABASE:-loan_limit_gateway}"
JAVA_OPTS="${JAVA_OPTS:-}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

# 은행 50곳 중 2곳이 30~45초짜리다. 한 건이 끝나는 데 그만큼 걸린다.
WAIT_SECONDS="${WAIT_SECONDS:-120}"
BANK_COUNT="${BANK_COUNT:-50}"

# shellcheck source=lib/remote.sh
source "${SCRIPT_DIR}/lib/remote.sh"

echo "base url : ${BASE_URL}"
echo "modes    : ${MODES}"
echo ""

# 실패하고 끝나는 경우가 많으므로 로그를 반드시 남긴다.
trap 'save_gateway_log smoke || true; gateway_stop || true' EXIT

gateway_stop || true

echo "starting gateway..."
if ! gateway_start ""; then
  echo "FAIL: 게이트웨이가 뜨지 않는다" >&2
  exit 1
fi

# 게이트웨이가 떠야 Flyway 가 스키마를 만든다. 비우기는 그 뒤에.
if ! db_reset; then
  echo "FAIL: DB 에 접근할 수 없다 (컨테이너 이름, 스키마 확인)" >&2
  exit 1
fi

expected_runs=0
for mode in ${MODES}; do expected_runs=$(( expected_runs + 1 )); done

# 모드 수만큼 VU 를 띄워 동시에 한 건씩 넣고 종료까지 기다린다.
echo "running k6 (smoke.js)..."
if ! ( cd "${SCRIPT_DIR}" && \
  MODES="${MODES}" \
  BASE_URL="${BASE_URL}" \
  MAX_WAIT_MS="$(( WAIT_SECONDS * 1000 ))" \
  k6 run smoke.js )
then
  echo "FAIL: k6 가 비정상 종료했다" >&2
  exit 1
fi

# k6 가 끝나도 마지막 저장이 남아 있을 수 있다.
waited=0
remaining=""
while [ "${waited}" -lt 60 ]; do
  remaining="$(db_query "SELECT COUNT(*) FROM loan_limit_batch_run WHERE status='IN_PROGRESS';")" || remaining=""
  [ "${remaining}" = "0" ] && break
  sleep 5
  waited=$(( waited + 5 ))
done

if [ "${remaining}" != "0" ]; then
  echo "FAIL: 아직 끝나지 않은 run 이 있다 (${remaining:-읽을 수 없음})" >&2
  exit 1
fi

echo ""
echo "mode                | status          | 저장된 은행콜 | 성공"
echo "--------------------+-----------------+---------------+------"

report="$(db_query "SELECT r.borrower_id, r.status, (SELECT COUNT(*) FROM bank_call_result b WHERE b.run_id=r.id), r.success_count FROM loan_limit_batch_run r ORDER BY r.id;")" || report=""
if [ -z "${report}" ]; then
  echo "FAIL: 결과를 읽을 수 없다" >&2
  exit 1
fi

failures=0
rows=0
while IFS=$'\t' read -r borrower status calls success; do
  [ -n "${borrower}" ] || continue
  rows=$(( rows + 1 ))
  printf '%-19s | %-15s | %13s | %s\n' "${borrower#SMOKE-}" "${status}" "${calls}" "${success}"
  [ "${status}" = "COMPLETED" ] || failures=$(( failures + 1 ))
  [ "${calls}" = "${BANK_COUNT}" ] || failures=$(( failures + 1 ))
  [ "${success}" -gt 0 ] || failures=$(( failures + 1 ))
done <<< "${report}"

echo ""
if [ "${rows}" -ne "${expected_runs}" ]; then
  echo "FAIL: run ${expected_runs}건을 넣었는데 ${rows}건만 남았다" >&2
  exit 1
fi
if [ "${failures}" -gt 0 ]; then
  echo "FAIL: ${failures}개 항목이 기대와 다르다" >&2
  echo "      status 는 COMPLETED, 저장된 은행콜은 ${BANK_COUNT}, 성공은 1 이상이어야 한다" >&2
  exit 1
fi

echo "PASS — 모드 ${rows}개 모두 은행 ${BANK_COUNT}곳을 호출하고 결과를 저장했다."
