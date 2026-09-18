#!/bin/bash
# 측정 결과를 C 호스트에서 로컬로 가져온다. 로컬(맥)에서 돌린다.
#
#   ./perf/k6/fetch-results.sh
#
# C 가 S3 로 올리고, 여기서 내려받는다. 일회용 인스턴스에 GitHub 권한을
# 두지 않으려고 한 단계를 거친다.
#
# destroy 하면 C 와 버킷이 같이 사라진다. 반드시 destroy 전에 돌릴 것.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INFRA_DIR="${INFRA_DIR:-${REPO_ROOT}/infra}"
DEST="${REPO_ROOT}/perf/k6/results"
REMOTE_RESULTS=/opt/fan-out-call/perf/k6/results

need() { command -v "$1" >/dev/null || { echo "$1 가 필요하다" >&2; exit 1; }; }
need aws
need terraform

# 값은 terraform 출력에서 가져온다. 손으로 넣으면 인스턴스를 다시 만들었을 때
# 옛 ID 로 조회해서 조용히 빈 결과를 받는다.
cd "${INFRA_DIR}"
REGION="$(terraform output -raw region 2>/dev/null || echo ap-northeast-2)"
BUCKET="$(terraform output -raw results_bucket)"
K6_ID="$(terraform output -json instance_ids | jq -r .k6)"

[ -n "${BUCKET}" ] || { echo "results_bucket 출력이 비어 있다. apply 했는지 확인할 것" >&2; exit 1; }
[ -n "${K6_ID}" ] || { echo "k6 인스턴스 ID 를 못 읽었다" >&2; exit 1; }

echo "bucket   : ${BUCKET}"
echo "k6 host  : ${K6_ID}"
echo "dest     : ${DEST}"
echo ""

# 1) C -> S3
echo "uploading from the k6 host..."
cmd_id="$(aws ssm send-command --region "${REGION}" --instance-ids "${K6_ID}" \
  --document-name AWS-RunShellScript \
  --parameters "commands=[\"aws s3 sync ${REMOTE_RESULTS}/ s3://${BUCKET}/results/ --only-show-errors && echo UPLOAD_OK\"],executionTimeout=[\"1800\"]" \
  --query 'Command.CommandId' --output text)"

status=Pending
waited=0
while [ "${waited}" -lt 600 ]; do
  status="$(aws ssm get-command-invocation --region "${REGION}" \
    --command-id "${cmd_id}" --instance-id "${K6_ID}" \
    --query 'Status' --output text 2>/dev/null)" || status=Pending
  case "${status}" in
    Success|Failed|Cancelled|TimedOut) break ;;
  esac
  sleep 10
  waited=$(( waited + 10 ))
done

if [ "${status}" != "Success" ]; then
  echo "업로드 실패 (${status}):" >&2
  aws ssm get-command-invocation --region "${REGION}" --command-id "${cmd_id}" \
    --instance-id "${K6_ID}" --query 'StandardErrorContent' --output text >&2 || true
  exit 1
fi

# 2) S3 -> 로컬
echo "downloading..."
mkdir -p "${DEST}"
aws s3 sync "s3://${BUCKET}/results/" "${DEST}/" --only-show-errors

echo ""
echo "받은 것:"
find "${DEST}" -name '*.manifest.json' -newermt '-1 day' 2>/dev/null | sed "s|${DEST}/||" | sort || true
echo ""
echo "회차 수: $(find "${DEST}" -name '*.manifest.json' | wc -l | tr -d ' ')"
echo "이제 커밋하면 된다. 그 다음 terraform destroy."
