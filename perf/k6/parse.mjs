#!/usr/bin/env node
// Parse k6 --summary-export JSONs produced by sweep.sh and emit a markdown table.
// Usage: node parse.mjs [resultsDir]

import { readdirSync, readFileSync } from 'node:fs';
import { join, basename } from 'node:path';

const resultsDir = process.argv[2] || join(import.meta.dirname || new URL('.', import.meta.url).pathname, 'results');

const NAME_RE = /^pool_(\d+)(?:_q(\d+))?\.json$/;
const files = readdirSync(resultsDir)
  .filter((f) => NAME_RE.test(f))
  .sort((a, b) => {
    const ma = a.match(NAME_RE), mb = b.match(NAME_RE);
    return Number(ma[1]) - Number(mb[1]) || Number(ma[2] ?? 0) - Number(mb[2] ?? 0);
  });

if (files.length === 0) {
  console.error(`No pool_*.json files found in ${resultsDir}`);
  process.exit(1);
}

const rows = files.map((f) => {
  const nameMatch = basename(f).match(NAME_RE);
  const pool = Number(nameMatch[1]);
  const queue = nameMatch[2] == null ? null : Number(nameMatch[2]);
  const j = JSON.parse(readFileSync(join(resultsDir, f), 'utf8'));
  const metrics = j.metrics || {};
  const g = (name, key) => (metrics[name] == null ? undefined : metrics[name][key]);
  return {
    pool,
    queue,
    e2eAvg: g('e2e_completion_time', 'avg'),
    e2eP95: g('e2e_completion_time', 'p(95)'),
    e2eMax: g('e2e_completion_time', 'max'),
    httpFailedRate: g('http_req_failed', 'value'),
    timeoutRate: g('timeout_waiting_rate', 'value'),
    iterations: g('iterations', 'count'),
    dropped: g('dropped_iterations', 'count') ?? 0,
    httpReqs: g('http_reqs', 'count'),
    pollsPerTx: g('polls_per_transaction', 'rate'),
    checksPassRate: g('checks', 'value'),
  };
});

const fmtMs = (x) => (x == null ? '—' : Math.round(x).toLocaleString());
const fmtPct = (x) => (x == null ? '—' : `${(x * 100).toFixed(2)}%`);
const fmtInt = (x) => (x == null ? '—' : Math.round(x).toLocaleString());

// Diminishing-returns: percent improvement in e2e p95 vs previous pool.
const withDelta = rows.map((r, i) => {
  if (i === 0) return { ...r, deltaP95: null };
  const prev = rows[i - 1];
  if (prev.e2eP95 == null || r.e2eP95 == null) return { ...r, deltaP95: null };
  return { ...r, deltaP95: (prev.e2eP95 - r.e2eP95) / prev.e2eP95 };
});

// Mark first row where the p95 improvement falls below 5% as the diminishing-returns inflection.
const DIMINISH_THRESHOLD = 0.05;
let diminishAtIdx = -1;
for (let i = 1; i < withDelta.length; i++) {
  const d = withDelta[i].deltaP95;
  if (d != null && d < DIMINISH_THRESHOLD) { diminishAtIdx = i; break; }
}

const hasQueue = withDelta.some((r) => r.queue != null);
const header = ['maxPoolSize', ...(hasQueue ? ['queueCapacity', 'capacity (pool+queue)'] : []), 'e2e avg (ms)', 'e2e p95 (ms)', 'http_req_failed', 'timeout_waiting_rate', 'iterations', 'dropped_iterations', 'Δ p95 vs prev'];
const sep = header.map(() => '---');
const body = withDelta.map((r, i) => [
  String(r.pool),
  ...(hasQueue ? [r.queue == null ? '—' : String(r.queue), r.queue == null ? '—' : String(r.pool + r.queue)] : []),
  fmtMs(r.e2eAvg),
  fmtMs(r.e2eP95),
  fmtPct(r.httpFailedRate),
  fmtPct(r.timeoutRate),
  fmtInt(r.iterations),
  fmtInt(r.dropped),
  r.deltaP95 == null ? '—' : `${(r.deltaP95 * 100).toFixed(1)}%${i === diminishAtIdx ? '  ← 수확 체감' : ''}`,
]);

const table = [header, sep, ...body].map((r) => `| ${r.join(' | ')} |`).join('\n');

console.log('');
const titleSuffix = process.env.MOCK_LATENCY_NOTE || 'mock 환경 · 상대 비교용 · 절대 성능치 아님';
console.log(`### maxPoolSize 스윕 결과 (${titleSuffix})`);
console.log('');
console.log(table);
console.log('');
if (diminishAtIdx >= 0) {
  const r = withDelta[diminishAtIdx];
  console.log(`**수확 체감 지점**: maxPoolSize=${r.pool}. 이전 단계 대비 p95 개선이 ${DIMINISH_THRESHOLD * 100}% 미만으로 떨어짐.`);
} else {
  console.log(`**수확 체감 지점**: 스윕 구간 내에서 p95 개선이 ${DIMINISH_THRESHOLD * 100}% 미만으로 떨어진 단계 없음.`);
}
console.log('');
console.log('_부가 정보_');
console.log(withDelta.map((r) => `- pool=${r.pool}${r.queue == null ? '' : `, queue=${r.queue}`}: http_reqs=${fmtInt(r.httpReqs)}, polls_per_tx_rate=${r.pollsPerTx == null ? '—' : r.pollsPerTx.toFixed(1)}/s, checks_pass=${r.checksPassRate == null ? '—' : (r.checksPassRate * 100).toFixed(1) + '%'}, e2e_max=${fmtMs(r.e2eMax)} ms`).join('\n'));
