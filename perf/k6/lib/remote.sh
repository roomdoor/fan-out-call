# A 호스트(게이트웨이 + MySQL)를 SSM으로 조종하는 헬퍼. bench.sh 와 smoke.sh 가 쓴다.
#
# 부르기 전에 있어야 하는 것:
#   GATEWAY_INSTANCE_ID, AWS_REGION
#   MYSQL_CONTAINER, MYSQL_DATABASE
#   JAVA_OPTS, EXTRA_ARGS        (gateway_start 만)
#
# 전부 0/1 을 돌려주고, 부르는 쪽이 판단한다.

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
        # 원격 stderr 를 그대로 보여준다. gateway-run.sh 는 컨테이너가 죽으면
        # docker logs --tail 50 을 여기에 쏟는데, 안 찍으면 왜 죽었는지 알 수 없다.
        echo "SSM ${status}:" >&2
        # 순서 주의: >&2 를 먼저 둬야 stdout 이 진짜 stderr 로 간다.
        # 2>/dev/null 을 앞에 쓰면 fd2 가 이미 /dev/null 이라 출력이 통째로 사라진다.
        aws ssm get-command-invocation \
          --region "${AWS_REGION}" --command-id "${cmd_id}" \
          --instance-id "${GATEWAY_INSTANCE_ID}" \
          --query 'StandardErrorContent' --output text >&2 2>/dev/null || true
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

# 컨테이너를 지우면 로그도 같이 사라진다(json-file 드라이버). 지우기 전에
# A 의 /var/log/bench/ 로 뺀다 — 표에서 이상이 보이면 여기를 본다.
save_gateway_log() {
  local name
  name="$(printf '%s' "$1" | tr '/' '_')"
  ssm_run "mkdir -p /var/log/bench && docker logs gateway > /var/log/bench/${name}.log 2>&1 || true" >/dev/null
}

gateway_stop() { ssm_run "docker rm -f gateway >/dev/null 2>&1 || true" >/dev/null; }

gateway_start() {
  ssm_run "JAVA_TOOL_OPTIONS='${JAVA_OPTS}' /usr/local/bin/gateway-run.sh $1 ${EXTRA_ARGS}" >/dev/null
}

# 게이트웨이 재시작은 MySQL 을 건드리지 않으므로 안 비우면 지난 실행 행까지
# 세진다. 외래키 때문에 체크를 끄고 TRUNCATE 한다.
db_reset() {
  db_query "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE bank_call_result; TRUNCATE TABLE loan_limit_batch_run; SET FOREIGN_KEY_CHECKS=1;" >/dev/null
}

resolve_digest() {
  ssm_run "docker inspect --format '{{index .RepoDigests 0}}' \$(docker inspect --format '{{.Config.Image}}' gateway 2>/dev/null || echo none) 2>/dev/null || echo unknown" \
    | tr -d '\r\n'
}
