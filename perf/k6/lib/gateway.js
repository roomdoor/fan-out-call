import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { BASE_URL, getSubmitEndpoint, POLL_MIN_MS, POLL_MAX_MS, MAX_WAIT_MS } from './common.js';

export function submit(mode, request) {
  const url = `${BASE_URL}${getSubmitEndpoint(mode)}`;
  const payload = JSON.stringify(request);
  
  const response = http.post(url, payload, {
    headers: { 'Content-Type': 'application/json' }
  });
  
  check(response, {
    'submit returns 202': (r) => r && r.status === 202
  });

  if (!response || response.status !== 202) {
    const status = response ? response.status : 'NO_RESPONSE';
    const body = response && response.body ? response.body : '';
    fail(`Submit failed: POST ${url} status=${status} body=${body}. Ensure gateway is running and BASE_URL is correct.`);
  }

  let parsed;
  try {
    parsed = JSON.parse(response.body);
  } catch (e) {
    fail(`Submit response parsing failed for ${url}: ${e}`);
  }

  if (!parsed || parsed.transactionNo === undefined || parsed.transactionNo === null) {
    fail(`Submit response missing transactionNo for ${url}. body=${response.body}`);
  }

  return parsed;
}

export function poll(transactionNo) {
  const url = `${BASE_URL}/api/v1/loan-limit/queries/number/${transactionNo}`;
  return http.get(url);
}

export function isTerminal(response) {
  try {
    const body = JSON.parse(response.body);
    return body.status !== 'IN_PROGRESS';
  } catch (e) {
    return false;
  }
}

export function pollUntilTerminal(transactionNo, maxWaitMs = MAX_WAIT_MS) {
  const startTime = Date.now();
  let waitTime = POLL_MIN_MS;
  let pollCount = 0;
  
  while (Date.now() - startTime < maxWaitMs) {
    const response = poll(transactionNo);
    pollCount++;
    
    if (isTerminal(response)) {
      return { 
        response, 
        pollCount, 
        duration: Date.now() - startTime,
        timedOut: false 
      };
    }
    
    sleep(waitTime / 1000);
    waitTime = Math.min(waitTime * 1.5, POLL_MAX_MS);
  }
  
  return { 
    response: null, 
    pollCount, 
    duration: maxWaitMs,
    timedOut: true 
  };
}
