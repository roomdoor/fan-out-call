import { submit, pollUntilTerminal } from './lib/gateway.js';
import { e2eCompletionTime, pollsPerTransaction, timeoutWaitingRate } from './lib/metrics.js';
import { validateMode, logConfig, MODE, MAX_WAIT_MS, buildLoanLimitRequest } from './lib/common.js';
import { check } from 'k6';

const LOAD_RPM = parseInt(__ENV.LOAD_RPM || '20');
const DURATION = __ENV.DURATION || '2m';

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-arrival-rate',
      rate: LOAD_RPM,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.ceil(LOAD_RPM * 2)),
      maxVUs: Math.max(200, LOAD_RPM * 10),
    },
  },
};

export function setup() {
  validateMode(MODE);
  logConfig();
  console.log(`LOAD_RPM: ${LOAD_RPM}`);
  console.log(`DURATION: ${DURATION}`);
}

export default function() {
  const request = buildLoanLimitRequest(MODE);
  const submitResponse = submit(MODE, request);

  check(submitResponse, {
    'submit returns transactionNo': (r) => r.transactionNo !== undefined && r.transactionNo !== null
  });

  const pollResult = pollUntilTerminal(submitResponse.transactionNo, request.borrowerId, MAX_WAIT_MS);

  pollsPerTransaction.add(pollResult.pollCount);
  e2eCompletionTime.add(pollResult.duration);
  timeoutWaitingRate.add(pollResult.timedOut);

  check(pollResult, {
    'transaction completed without timeout': (r) => !r.timedOut,
    'terminal status received': (r) => r.response !== null
  });

  if (!pollResult.timedOut) {
    const body = JSON.parse(pollResult.response.body);
    check(body, {
      'completed count equals requested count': (b) => b.completedCount === b.requestedBankCount
    });
  }
}

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data, null, 2),
  };
}
