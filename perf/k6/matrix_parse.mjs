#!/usr/bin/env node
// Aggregate matrix-pool<P>-q<Q>/rpm_<N>.json + gateway logs into a 2D table.
// Usage: node matrix_parse.mjs [resultsDir]

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execSync } from 'node:child_process';

const baseDir = new URL('.', import.meta.url).pathname;
const resultsDir = process.argv[2] || join(baseDir, 'results');
const logsDir = join(baseDir, 'logs');

const DIR_RE = /^matrix-pool(\d+)-q(\d+)$/;
const dirs = readdirSync(resultsDir)
  .filter((d) => DIR_RE.test(d))
  .sort((a, b) => {
    const ma = a.match(DIR_RE), mb = b.match(DIR_RE);
    return Number(ma[1]) - Number(mb[1]) || Number(ma[2]) - Number(mb[2]);
  });

if (dirs.length === 0) {
  console.error(`No matrix-pool*-q* directories in ${resultsDir}`);
  process.exit(1);
}

function countLog(path, pattern) {
  if (!existsSync(path)) return 0;
  try {
    const out = execSync(`grep -c '${pattern}' '${path}' 2>/dev/null || echo 0`, { encoding: 'utf8' });
    return parseInt(out.trim()) || 0;
  } catch { return 0; }
}

const configs = dirs.map((d) => {
  const m = d.match(DIR_RE);
  const pool = Number(m[1]);
  const queue = Number(m[2]);
  const rpmFiles = readdirSync(join(resultsDir, d))
    .filter((f) => /^rpm_(\d+)\.json$/.test(f))
    .sort((a, b) => Number(a.match(/\d+/)[0]) - Number(b.match(/\d+/)[0]));

  const cells = rpmFiles.map((f) => {
    const rpm = Number(f.match(/\d+/)[0]);
    const j = JSON.parse(readFileSync(join(resultsDir, d, f), 'utf8'));
    const m = j.metrics || {};
    const g = (name, key) => (m[name] == null ? undefined : m[name][key]);
    const logPath = join(logsDir, `gateway_rpm_${rpm}.log`);
    // NOTE: gateway log is per-RPM only — last config's log overwrites previous configs.
    // So gateway-log COMPLETED/FAILED is only reliable for the FINAL config in the sweep.
    return {
      rpm,
      iterations: g('iterations', 'count'),
      e2eAvg: g('e2e_completion_time', 'avg'),
      e2eP95: g('e2e_completion_time', 'p(95)'),
      timeoutRate: g('timeout_waiting_rate', 'value'),
      checksValue: g('checks', 'value'),
      checksPasses: g('checks', 'passes'),
      checksFails: g('checks', 'fails'),
    };
  });

  return { pool, queue, cells };
});

// For each cell, derive: "fully completed" iterations.
// Each iteration runs up to 5 checks; 4 are trivial (submit/transactionNo/no-timeout/terminal),
// last is `completedCount === requestedBankCount`. If timed out, only 3 trivial checks run.
// Total checks ≈ 5*iters - 2*timedout_iters (the last check + body check skipped on timeout).
// More robust: derive "fully completed" = checks.passes - 4*iters (when no timeouts).
// Simpler approximation: derive fail count of last check ≈ checks.fails - timeouts*2 (rough).
//
// Cleanest: count passes for ONLY the "completed count equals requested count" check.
// k6 summary-export aggregates all checks together though, not per-name.
// → fallback: total_checks_expected = 5 * iters_no_timeout + 3 * iters_timeout
//   fails = total - passes; subtract those known to be "completedCount" fails.
//
// Heuristic for this exercise: fully_completed ≈ checks.passes - (4*iters_no_timeout + 3*iters_timeout)
function fullyCompleted(c) {
  if (c.iterations == null || c.checksPasses == null) return null;
  const timedOut = Math.round((c.timeoutRate ?? 0) * c.iterations);
  const itersNoTo = c.iterations - timedOut;
  const trivialPasses = 4 * itersNoTo + 3 * timedOut;
  const fc = c.checksPasses - trivialPasses;
  return Math.max(0, fc);
}

const fmt = (x) => (x == null ? '—' : Math.round(x).toLocaleString());
const fmtPct = (x) => (x == null ? '—' : `${(x * 100).toFixed(1)}%`);
const fmtSec = (x) => (x == null ? '—' : `${(x / 1000).toFixed(1)}s`);

// Build 2D table: rows = RPM, cols = configs
const allRpms = Array.from(new Set(configs.flatMap((c) => c.cells.map((x) => x.rpm)))).sort((a, b) => a - b);

console.log('');
console.log('### Matrix sweep 결과 (mock: 정상 7~13s · slow×2개 30s · 2min/회차)');
console.log('');
console.log(`> 각 셀: **fully_completed/min** (= 50/50 완료 ÷ 2분) / e2e p95 / timeout_rate`);
console.log('> fully_completed는 k6 checks 메트릭에서 5개 check 중 마지막(`completedCount==50`) 패스 카운트를 역산.');
console.log('');

const headerCfg = ['RPM', ...configs.map((c) => `pool=${c.pool}/q=${c.queue}`)];
const sep = headerCfg.map(() => '---');
const rows = allRpms.map((rpm) => {
  const row = [String(rpm)];
  for (const cfg of configs) {
    const cell = cfg.cells.find((x) => x.rpm === rpm);
    if (!cell) { row.push('—'); continue; }
    const fc = fullyCompleted(cell);
    const fcPerMin = fc == null ? null : fc / 2;
    row.push(`**${fcPerMin == null ? '—' : fcPerMin.toFixed(1)}** / p95 ${fmtSec(cell.e2eP95)} / to ${fmtPct(cell.timeoutRate)}`);
  }
  return row;
});
console.log([headerCfg, sep, ...rows].map((r) => `| ${r.join(' | ')} |`).join('\n'));
console.log('');

// Per-config detail
console.log('---');
console.log('');
console.log('### 설정별 세부');
console.log('');
for (const cfg of configs) {
  console.log(`#### pool=${cfg.pool}, queue=${cfg.queue} (in-flight 슬롯 ${cfg.pool + cfg.queue})`);
  console.log('');
  console.log('| RPM | iters | fully_completed/min | e2e avg | e2e p95 | timeout_rate | checks_pass |');
  console.log('| --- | --- | --- | --- | --- | --- | --- |');
  for (const cell of cfg.cells) {
    const fc = fullyCompleted(cell);
    console.log(`| ${cell.rpm} | ${fmt(cell.iterations)} | **${fc == null ? '—' : (fc / 2).toFixed(1)}** | ${fmtSec(cell.e2eAvg)} | ${fmtSec(cell.e2eP95)} | ${fmtPct(cell.timeoutRate)} | ${fmtPct(cell.checksValue)} |`);
  }
  console.log('');
}

console.log('---');
console.log('');
console.log('### 비교 메모');
console.log('- fully_completed/min이 RPM과 같으면 그 부하를 완벽하게 처리한다는 뜻.');
console.log('- "최적" 후보는 모든 RPM에서 fully_completed ≈ RPM이면서 가장 작은 슬롯을 쓰는 설정.');
