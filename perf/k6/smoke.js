// 모드마다 트랜잭션 1건씩. 부하를 걸지 않는다.
//
//   MODES="coroutine webclient" k6 run smoke.js
//
// load.js 와 같은 lib/ 를 타므로, 여기서 통과하면 실측이 쓰는 경로가
// 뚫려 있다는 뜻이다. 판정은 smoke.sh 가 DB 를 보고 한다.
import { check } from 'k6';
import { buildLoanLimitRequest, validateMode, BASE_URL, MAX_WAIT_MS } from './lib/common.js';
import { submit, pollUntilTerminal } from './lib/gateway.js';

const MODES = (__ENV.MODES || 'coroutine async-threadpool webclient').trim().split(/\s+/);

export const options = {
  scenarios: {
    // per-vu-iterations 라야 VU 하나가 정확히 한 번 돈다. vus/iterations 만
    // 쓰면 shared-iterations 가 되어, 한 VU 가 빨리 끝나면 남의 몫까지 가져간다
    // (모드 하나가 두 번 돌고 다른 하나는 안 돈다).
    one_per_mode: {
      executor: 'per-vu-iterations',
      vus: MODES.length,
      iterations: 1,
      maxDuration: `${Math.ceil(MAX_WAIT_MS / 1000) + 60}s`,
    },
  },
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
