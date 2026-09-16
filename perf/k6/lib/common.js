export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const MODE = __ENV.MODE;
export const MAX_WAIT_MS = parseInt(__ENV.MAX_WAIT_MS || '60000');
export const POLL_MIN_MS = parseInt(__ENV.POLL_MIN_MS || '100');
export const POLL_MAX_MS = parseInt(__ENV.POLL_MAX_MS || '5000');
export const RUN_ID = __ENV.RUN_ID || `k6-run-${Date.now()}`;

const modeEndpoints = {
  coroutine: '/api/v1/loan-limit/coroutine/queries',
  'async-threadpool': '/api/v1/loan-limit/async-threadpool/queries',
  webclient: '/api/v1/loan-limit/webclient/queries',
  // bad-case 대조군. 컨트롤러가 실제로 있으므로 여기서 막지 않는다.
  // 빠져 있으면 MODE=sequential 설정이 매 회차 setup()에서 터지는데,
  // bench.sh가 k6의 비정상 종료를 넘기므로 sweep 전체가 summary 0건으로 끝난다.
  sequential: '/api/v1/loan-limit/sequential/queries'
};

export function getSubmitEndpoint(mode) {
  return modeEndpoints[mode];
}

export function validateMode(mode) {
  if (!mode || !modeEndpoints[mode]) {
    throw new Error(`Invalid MODE: ${mode}. Must be one of: ${Object.keys(modeEndpoints).join(', ')}`);
  }
}

export function logConfig() {
  console.log(`BASE_URL: ${BASE_URL}`);
  console.log(`MODE: ${MODE}`);
  console.log(`MAX_WAIT_MS: ${MAX_WAIT_MS}`);
  console.log(`RUN_ID: ${RUN_ID}`);
}

export function buildBorrowerId(mode) {
  const safeMode = String(mode || 'unknown').replace(/[^a-zA-Z0-9]/g, '-');
  return `USER-${safeMode}-VU${__VU}-IT${__ITER}`;
}

export function buildLoanLimitRequest(mode) {
  return {
    borrowerId: buildBorrowerId(mode),
    annualIncome: 70000000,
    requestedAmount: 30000000,
  };
}
