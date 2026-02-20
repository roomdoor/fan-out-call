#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

function toNumber(value, fallback = null) {
  return Number.isFinite(value) ? value : fallback;
}

function getMetricValue(summary, metricName, key) {
  return summary?.metrics?.[metricName]?.[key];
}

function getRate(summary, metricName) {
  const metric = summary?.metrics?.[metricName] || {};
  return toNumber(metric.value, toNumber(metric.rate, 0));
}

function findEvidenceDirs(baseDir) {
  if (!fs.existsSync(baseDir)) {
    return [];
  }
  
  return fs.readdirSync(baseDir, { withFileTypes: true })
    .filter(dirent => dirent.isDirectory())
    .map(dirent => path.join(baseDir, dirent.name))
    .filter(dir => !path.basename(dir).toLowerCase().startsWith('sample-'))
    .filter(dir => fs.existsSync(path.join(dir, 'manifest.json')) && fs.existsSync(path.join(dir, 'summary.json')));
}

function loadManifest(evidenceDir) {
  const manifestPath = path.join(evidenceDir, 'manifest.json');
  return JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));
}

function loadSummary(evidenceDir) {
  const summaryPath = path.join(evidenceDir, 'summary.json');
  return JSON.parse(fs.readFileSync(summaryPath, 'utf-8'));
}

function loadSystemMetricsSummary(evidenceDir) {
  const filePath = path.join(evidenceDir, 'system-metrics-summary.json');
  if (!fs.existsSync(filePath)) return null;
  return JSON.parse(fs.readFileSync(filePath, 'utf-8'));
}

function extractMetrics(summary) {
  const iterationsCount = toNumber(getMetricValue(summary, 'iterations', 'count'), 0);
  const httpReqCount = toNumber(getMetricValue(summary, 'http_reqs', 'count'), iterationsCount);
  const droppedIterations = toNumber(getMetricValue(summary, 'dropped_iterations', 'count'), 0);
  const checksPasses = toNumber(getMetricValue(summary, 'checks', 'passes'), 0);
  const checksFails = toNumber(getMetricValue(summary, 'checks', 'fails'), 0);
  const checksTotal = checksPasses + checksFails;

  const completedCountCheck =
    summary?.root_group?.checks?.['completed count equals requested count'] || null;
  const completedCountCheckFails = toNumber(completedCountCheck?.fails, 0);
  const completedCountCheckPasses = toNumber(completedCountCheck?.passes, 0);
  const completedCountCheckTotal = completedCountCheckPasses + completedCountCheckFails;

  const pollsCount = toNumber(getMetricValue(summary, 'polls_per_transaction', 'count'), 0);
  const pollsPerTxAvg = iterationsCount > 0 ? pollsCount / iterationsCount : null;

  return {
    httpReqDurationAvg: toNumber(getMetricValue(summary, 'http_req_duration', 'avg'), 0),
    httpReqDurationP95: toNumber(getMetricValue(summary, 'http_req_duration', 'p(95)'), 0),
    httpReqDurationP99: toNumber(getMetricValue(summary, 'http_req_duration', 'p(99)'), null),
    httpReqFailedRate: getRate(summary, 'http_req_failed'),
    e2eCompletionAvg: toNumber(getMetricValue(summary, 'e2e_completion_time', 'avg'), 0),
    e2eCompletionP95: toNumber(getMetricValue(summary, 'e2e_completion_time', 'p(95)'), 0),
    e2eCompletionP99: toNumber(getMetricValue(summary, 'e2e_completion_time', 'p(99)'), null),
    timeoutRate: getRate(summary, 'timeout_waiting_rate'),
    checksPassRate: checksTotal > 0 ? checksPasses / checksTotal : null,
    checksFails,
    iterations: Math.round(iterationsCount),
    httpReqs: Math.round(httpReqCount),
    droppedIterations: Math.round(droppedIterations),
    pollsPerTxAvg,
    completedCountCheckFailRate:
      completedCountCheckTotal > 0 ? completedCountCheckFails / completedCountCheckTotal : null,
    completedCountCheckFails,
  };
}

function formatDuration(ms) {
  if (ms == null) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatPercent(rate) {
  if (rate == null) return '-';
  return `${(rate * 100).toFixed(2)}%`;
}

function formatNumber(value, digits = 0) {
  if (value == null) return '-';
  return Number(value).toFixed(digits);
}

function formatIso(ts) {
  if (!ts) return '-';
  return ts.replace('T', ' ').replace('Z', '');
}

function formatCpu(rate) {
  if (rate == null) return '-';
  return `${(rate * 100).toFixed(1)}%`;
}

function appendRunsTable(report, title, runs) {
  if (runs.length === 0) return report;

  report += `## ${title}\n\n`;
  report += '| Mode | Run Time | E2E Avg | E2E P95 | E2E P99 | HTTP Avg | HTTP P95 | Error | Timeout | Checks Pass | CompletedMismatch | Iterations | HTTP Reqs | Dropped | Polls/Tx |\n';
  report += '|------|----------|---------|---------|---------|----------|----------|-------|---------|-------------|-------------------|------------|-----------|---------|----------|\n';

  runs.forEach(run => {
    const m = run.metrics;
    report += `| ${run.mode} | ${formatIso(run.timestamp)} | ${formatDuration(m.e2eCompletionAvg)} | ${formatDuration(m.e2eCompletionP95)} | ${formatDuration(m.e2eCompletionP99)} | ${formatDuration(m.httpReqDurationAvg)} | ${formatDuration(m.httpReqDurationP95)} | ${formatPercent(m.httpReqFailedRate)} | ${formatPercent(m.timeoutRate)} | ${formatPercent(m.checksPassRate)} | ${formatPercent(m.completedCountCheckFailRate)} (${m.completedCountCheckFails}) | ${m.iterations} | ${m.httpReqs} | ${m.droppedIterations} | ${formatNumber(m.pollsPerTxAvg, 2)} |\n`;
  });

  report += '\n';
  return report;
}

function generateReport(evidenceDirs) {
  const runs = evidenceDirs.map(dir => {
    const manifest = loadManifest(dir);
    const summary = loadSummary(dir);
    const metrics = extractMetrics(summary);
    
    return {
      mode: manifest.mode,
      profile: manifest.profile,
      timestamp: manifest.timestamp,
      runId: manifest.runId,
      metrics,
      systemMetrics: loadSystemMetricsSummary(dir),
    };
  });
  
  const baselineRuns = runs.filter(r => r.profile === 'baseline');
  const stressRuns = runs.filter(r => r.profile === 'stress');
  
  let report = '# Performance Test Report\n\n';
  report += `Generated: ${new Date().toISOString()}\n\n`;
  report += '## How To Read\n\n';
  report += '- `E2E` is submit -> polling until terminal status (`status != IN_PROGRESS`).\n';
  report += '- `CompletedMismatch` means `completed count equals requested count` check fail rate.\n';
  report += '- `Polls/Tx` helps estimate polling overhead per transaction.\n\n';
  
  report = appendRunsTable(report, 'Baseline Results', baselineRuns);
  report = appendRunsTable(report, 'Stress Results', stressRuns);

  report += '## Server Resource Snapshot\n\n';
  report += '| Mode | Run Time | Proc CPU Avg | Proc CPU Max | Sys CPU Avg | JVM Live Thr Max | Async Active Max | Async Queue Max | Async Pool Max | Samples |\n';
  report += '|------|----------|--------------|--------------|-------------|------------------|------------------|-----------------|----------------|---------|\n';
  runs.forEach(run => {
    const s = run.systemMetrics;
    report += `| ${run.mode} | ${formatIso(run.timestamp)} | ${formatCpu(s?.processCpu?.avg)} | ${formatCpu(s?.processCpu?.max)} | ${formatCpu(s?.systemCpu?.avg)} | ${formatNumber(s?.jvmThreadsLive?.max, 0)} | ${formatNumber(s?.executorActive?.max, 0)} | ${formatNumber(s?.executorQueued?.max, 0)} | ${formatNumber(s?.executorPoolSize?.max, 0)} | ${formatNumber(s?.samples, 0)} |\n`;
  });
  report += '\n';

  const mismatchedRuns = runs.filter(r => (r.metrics.completedCountCheckFails || 0) > 0);
  if (mismatchedRuns.length > 0) {
    report += '## Data Quality Alerts\n\n';
    report += '| Mode | Run Time | CompletedMismatch Fail Count | CompletedMismatch Fail Rate |\n';
    report += '|------|----------|-----------------------------|----------------------------|\n';
    mismatchedRuns.forEach(run => {
      report += `| ${run.mode} | ${formatIso(run.timestamp)} | ${run.metrics.completedCountCheckFails} | ${formatPercent(run.metrics.completedCountCheckFailRate)} |\n`;
    });
    report += '\n';
  }
  
  report += '## Summary\n\n';
  report += `- Total runs: ${runs.length}\n`;
  report += `- Baseline runs: ${baselineRuns.length}\n`;
  report += `- Stress runs: ${stressRuns.length}\n`;
  report += `- Modes tested: ${[...new Set(runs.map(r => r.mode))].join(', ')}\n`;
  
  return report;
}

function main() {
  const evidenceBaseDir = path.join(__dirname, '..', '..', '.sisyphus', 'evidence', 'perf');
  
  console.log(`Looking for evidence in: ${evidenceBaseDir}`);
  
  const evidenceDirs = findEvidenceDirs(evidenceBaseDir);
  
  if (evidenceDirs.length === 0) {
    console.log('No evidence directories found. Run some tests first!');
    process.exit(1);
  }
  
  console.log(`Found ${evidenceDirs.length} test runs`);
  
  const report = generateReport(evidenceDirs);
  
  const reportPath = path.join(evidenceBaseDir, 'REPORT.md');
  fs.writeFileSync(reportPath, report);
  
  console.log(`\nReport saved to: ${reportPath}`);
  console.log('\n' + report);
}

main();
