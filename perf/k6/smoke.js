// 모드마다 트랜잭션 1건씩. 부하를 걸지 않는다.
//
//   MODES="coroutine webclient" k6 run smoke.js
//
// load.js 와 같은 lib/ 를 타므로, 여기서 통과하면 실측이 쓰는 경로가
// 뚫려 있다는 뜻이다. 판정은 smoke.sh 가 DB 를 보고 한다.
import { check } from 'k6';
import { buildLoanLimitRequest, validateMode, BASE_URL } from './lib/common.js';
import { submit, pollUntilTerminal } from './lib/gateway.js';

const MODES = (__ENV.MODES || 'coroutine async-threadpool webclient').trim().split(/\s+/);

export const options = {
  // 모드 수만큼 VU 를 띄워 동시에 한 건씩. 순차로 하면 모드마다 45초씩 쌓인다.
  vus: MODES.length,
  iterations: MODES.length,
};

export function setup() {
  MODES.forEach(validateMode);
  console.log(`BASE_URL: ${BASE_URL}`);
  console.log(`modes: ${MODES.join(', ')}`);
}

export default function () {
  const mode = MODES[(__VU - 1) % MODES.length];
  const request = buildLoanLimitRequest(mode);
  request.borrowerId = `SMOKE-${mode}`;

  const submitted = submit(mode, request);
  const result = pollUntilTerminal(submitted.transactionNo, request.borrowerId);

  check(result, {
    [`${mode}: 종료 상태에 도달`]: (r) => r.timedOut === false,
  });

  console.log(`${mode}: transactionNo=${submitted.transactionNo} timedOut=${result.timedOut} polls=${result.pollCount}`);
}
