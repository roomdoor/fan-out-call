export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const MODE = __ENV.MODE;
export const MAX_WAIT_MS = parseInt(__ENV.MAX_WAIT_MS || '60000');
export const POLL_MIN_MS = parseInt(__ENV.POLL_MIN_MS || '100');
export const POLL_MAX_MS = parseInt(__ENV.POLL_MAX_MS || '5000');
export const RUN_ID = __ENV.RUN_ID || `k6-run-${Date.now()}`;

const modeEndpoints = {
  coroutine: '/api/v1/loan-limit/coroutine/queries',
  'async-threadpool': '/api/v1/loan-limit/async-threadpool/queries',
  webclient: '/api/v1/loan-limit/webclient/queries'
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
