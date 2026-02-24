import { submit, pollUntilTerminal } from './lib/gateway.js';
import { e2eCompletionTime, pollsPerTransaction, timeoutWaitingRate } from './lib/metrics.js';
import { validateMode, logConfig, MODE, MAX_WAIT_MS, buildLoanLimitRequest } from './lib/common.js';
import { check } from 'k6';

export const options = {
  scenarios: {
    stress_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: 100,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '45s', target: 30 },
        { duration: '45s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.10'],
    timeout_waiting_rate: ['rate<0.20'],
  },
};

export function setup() {
  validateMode(MODE);
  logConfig();
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
