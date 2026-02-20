import { Trend, Counter, Rate } from 'k6/metrics';

export const e2eCompletionTime = new Trend('e2e_completion_time');
export const pollsPerTransaction = new Counter('polls_per_transaction');
export const timeoutWaitingRate = new Rate('timeout_waiting_rate');
export const httpReqFailed = new Rate('http_req_failed');
