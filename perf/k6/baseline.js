import { submit, pollUntilTerminal } from './lib/gateway.js';
import { e2eCompletionTime, pollsPerTransaction, timeoutWaitingRate } from './lib/metrics.js';
import { validateMode, logConfig, MODE, MAX_WAIT_MS, buildLoanLimitRequest } from './lib/common.js';
import { check } from 'k6';

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: 3,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 10,
      maxVUs: 30,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    timeout_waiting_rate: ['rate<0.01'],
    e2e_completion_time: ['p(95)<30000', 'p(99)<45000'],
  },
};

export function setup() {
  validateMode(MODE);
  logConfig();
}

export default function() {
  const submitResponse = submit(MODE, buildLoanLimitRequest(MODE));
  
  check(submitResponse, {
    'submit returns transactionNo': (r) => r.transactionNo !== undefined && r.transactionNo !== null
  });
  
  const pollResult = pollUntilTerminal(submitResponse.transactionNo, MAX_WAIT_MS);
  
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
