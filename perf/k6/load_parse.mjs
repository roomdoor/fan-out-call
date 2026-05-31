#!/usr/bin/env node
// Parse rpm_*.json results + count COMPLETED/FAILED in gateway logs.
// Usage: node load_parse.mjs [resultsDir] [logsDir]

import { readdirSync, readFileSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execSync } from 'node:child_process';

const baseDir = new URL('.', import.meta.url).pathname;
const resultsDir = process.argv[2] || join(baseDir, 'results');
const logsDir = process.argv[3] || join(baseDir, 'logs');

const NAME_RE = /^rpm_(\d+)\.json$/;
const files = readdirSync(resultsDir)
  .filter((f) => NAME_RE.test(f))
  .sort((a, b) => Number(a.match(/\d+/)[0]) - Number(b.match(/\d+/)[0]));

if (files.length === 0) {
  console.error(`No rpm_*.json files found in ${resultsDir}`);
  process.exit(1);
}

function countLog(logPath, pattern) {
  try {
    const out = execSync(`grep -c '${pattern}' '${logPath}' 2>/dev/null || echo 0`, { encoding: 'utf8' });
    return parseInt(out.trim()) || 0;
  } catch {
    return 0;
  }
}

const rows = files.map((f) => {
  const rpm = Number(basename(f).match(NAME_RE)[1]);
  const j = JSON.parse(readFileSync(join(resultsDir, f), 'utf8'));
  const m = j.metrics || {};
  const g = (name, key) => (m[name] == null ? undefined : m[name][key]);

  const logPath = join(logsDir, `gateway_rpm_${rpm}.log`);
  const completed = countLog(logPath, 'Background fan-out completed.*status=COMPLETED');
  const failed = countLog(logPath, 'Run marked as FAILED');
  const partial = countLog(logPath, 'Background fan-out completed.*status=PARTIAL');

  return {
    rpm,
    iterations: g('iterations', 'count'),
    dropped: g('dropped_iterations', 'count') ?? 0,
    e2eAvg: g('e2e_completion_time', 'avg'),
    e2eP95: g('e2e_completion_time', 'p(95)'),
    e2eMax: g('e2e_completion_time', 'max'),
    timeoutRate: g('timeout_waiting_rate', 'value'),
    httpFailedRate: g('http_req_failed', 'value'),
    completed,
    failed,
    partial,
  };
});

const fmtMs = (x) => (x == null ? '—' : Math.round(x).toLocaleString());
const fmtPct = (x) => (x == null ? '—' : `${(x * 100).toFixed(2)}%`);

const header = ['RPM', 'iters', 'dropped', 'COMPLETED', 'FAILED', 'PARTIAL', '실효 처리율 /min', 'e2e avg (ms)', 'e2e p95 (ms)', 'timeout_rate'];
const sep = header.map(() => '---');
const body = rows.map((r) => {
  // Duration was 2m so per-minute rate = completed / 2
  const completedPerMin = r.completed / 2;
  const successRate = r.completed + r.failed > 0 ? r.completed / (r.completed + r.failed) : 0;
  return [
    String(r.rpm),
    fmtMs(r.iterations),
    fmtMs(r.dropped),
    fmtMs(r.completed),
    fmtMs(r.failed),
    fmtMs(r.partial),
    `${completedPerMin.toFixed(1)} (${(successRate * 100).toFixed(0)}%)`,
    fmtMs(r.e2eAvg),
    fmtMs(r.e2eP95),
    fmtPct(r.timeoutRate),
  ];
});

const table = [header, sep, ...body].map((r) => `| ${r.join(' | ')} |`).join('\n');

console.log('');
const titleNote = process.env.RUN_NOTE || 'pool 설정 고정 · mock: 정상 7~13s · slow×2개 30s · 2min/회차';
console.log(`### Load 스윕 결과 (${titleNote})`);
console.log('');
console.log('> **실효 처리율**은 게이트웨이 로그에서 직접 카운트한 `status=COMPLETED`(50/50 성공) 건수 ÷ 2분.');
console.log('> "Success rate %"는 `COMPLETED / (COMPLETED + FAILED)`.');
console.log('');
console.log(table);
console.log('');

// Identify breakpoint: first RPM where success rate drops below 95%
let breakpointIdx = -1;
for (let i = 0; i < rows.length; i++) {
  const r = rows[i];
  const total = r.completed + r.failed;
  if (total === 0) continue;
  const rate = r.completed / total;
  if (rate < 0.95) { breakpointIdx = i; break; }
}

if (breakpointIdx >= 0) {
  const br = rows[breakpointIdx];
  const total = br.completed + br.failed;
  const rate = br.completed / total;
  console.log(`**Breakpoint**: ${br.rpm} RPM에서 success rate가 ${(rate * 100).toFixed(1)}%로 떨어짐 (95% 미만).`);
  if (breakpointIdx > 0) {
    const last = rows[breakpointIdx - 1];
    const lastTotal = last.completed + last.failed;
    const lastRate = lastTotal > 0 ? last.completed / lastTotal : 0;
    console.log(`마지막 안정 구간: **${last.rpm} RPM** (${(lastRate * 100).toFixed(1)}% 성공, COMPLETED=${last.completed}).`);
  }
} else {
  console.log('**모든 회차에서 success rate ≥ 95%.** 100 RPM까지 시스템이 안정적으로 처리.');
  const last = rows[rows.length - 1];
  const lastTotal = last.completed + last.failed;
  const lastRate = lastTotal > 0 ? last.completed / lastTotal : 0;
  console.log(`최고 부하 ${last.rpm} RPM 결과: COMPLETED=${last.completed}, FAILED=${last.failed} (성공 ${(lastRate * 100).toFixed(1)}%).`);
}
