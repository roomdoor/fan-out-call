import { validateMode, logConfig, MODE, buildLoanLimitRequest } from './lib/common.js';
import { submit, pollUntilTerminal } from './lib/gateway.js';
import { e2eCompletionTime, pollsPerTransaction, timeoutWaitingRate } from './lib/metrics.js';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    timeout_waiting_rate: ['rate<0.01']
  }
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
  
  const pollResult = pollUntilTerminal(submitResponse.transactionNo);
  
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
