#!/usr/bin/env node
// bench.sh 결과를 마크다운 표로 만든다.
//
//   node parse.mjs results/v15-pool512
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2];
if (!root) {
  console.error('usage: node parse.mjs <results-dir>');
  process.exit(1);
}

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (entry.endsWith('.manifest.json')) out.push(p);
  }
  return out;
}

const runs = walk(root).map((manifestPath) => {
  const m = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const summaryPath = manifestPath.replace(/\.manifest\.json$/, '.summary.json');

  let metrics = {};
  try {
    metrics = JSON.parse(readFileSync(summaryPath, 'utf8')).metrics ?? {};
  } catch {
    // k6가 죽은 회차. manifest는 남지만 summary가 없다.
  }
  const g = (name, field) => metrics[name]?.[field] ?? null;

  const c = m.counts ?? {};
  return {
    valid: m.valid === true,
    drainCapped: m.drain_capped === true,
    drainUnreadable: m.drain_unreadable === true,
    countsFailed: m.counts_failed === true,
    mode: m.mode ?? '-',
    pool: m.pool ?? '-',
    rpm: m.rpm,
    durationMin: m.duration_seconds ? m.duration_seconds / 60 : null,
    image: m.gateway_image,
    javaOpts: m.java_opts,
    completed: c.completed ?? null,
    partial: c.partial ?? null,
    failedSaturated: c.failed_saturated ?? null,
    failedError: c.failed_error ?? null,
    failedFinalize: c.failed_finalize ?? null,
    callsSuccess: c.calls_success ?? null,
    callsRejected: c.calls_rejected ?? null,
    callsSubmitError: c.calls_submit_error ?? null,
    callsException: c.calls_exception ?? null,
    callsOther: c.calls_other ?? null,
    balanced: c.balanced !== false,
    runsMissingRows: c.runs_missing_rows ?? null,
    dropped: g('dropped_iterations', 'count') ?? 0,
    e2eP95: g('e2e_completion_time', 'p(95)'),
    timeoutRate: g('timeout_waiting_rate', 'value'),
  };
});

if (runs.length === 0) {
  console.error(`no manifest.json under ${root}`);
  process.exit(1);
}

// 무효 회차는 표에서 빼고 아래에 건수만 적는다. 섞으면 평균이 낮아진다.
const usable = runs.filter((r) => r.valid);

const groups = new Map();
for (const r of usable) {
  const key = `${r.mode}/${r.pool}/${r.rpm}`;
  if (!groups.has(key)) groups.set(key, []);
  groups.get(key).push(r);
}

// "pool1700-q200" -> 1700, "default" -> -1 (맨 앞)
function poolOrder(label) {
  const m = /^pool(\d+)/.exec(label ?? '');
  return m ? Number(m[1]) : -1;
}

const num = (xs) => xs.filter((x) => typeof x === 'number' && !Number.isNaN(x));
const sum = (xs) => num(xs).reduce((a, b) => a + b, 0);
const mean = (xs) => (num(xs).length ? sum(xs) / num(xs).length : null);
const fmt = (x) => (x == null ? '—' : Math.round(x).toLocaleString());
const pct = (x) => (x == null ? '—' : `${(x * 100).toFixed(1)}%`);

// 반복 측정의 최소~최대를 같이 보여준다. 평균만 보면 흩어짐이 숨는다.
function spread(xs) {
  const v = num(xs);
  if (v.length <= 1) return '';
  return ` (${fmt(Math.min(...v))}~${fmt(Math.max(...v))})`;
}

const rows = [...groups.values()]
  .map((reps) => {
    const first = reps[0];
    const completed = mean(reps.map((r) => r.completed));
    return {
      mode: first.mode,
      pool: first.pool,
      rpm: first.rpm,
      n: reps.length,
      completed,
      completedSpread: spread(reps.map((r) => r.completed)),
      partial: mean(reps.map((r) => r.partial)),
      failedSaturated: mean(reps.map((r) => r.failedSaturated)),
      failedError: mean(reps.map((r) => r.failedError)),
      callsRejected: mean(reps.map((r) => r.callsRejected)),
      callsException: mean(reps.map((r) => r.callsException)),
      perMin: completed != null && first.durationMin ? completed / first.durationMin : null,
      e2eP95: mean(reps.map((r) => r.e2eP95)),
      p95Spread: spread(reps.map((r) => r.e2eP95)),
      timeoutRate: mean(reps.map((r) => r.timeoutRate)),
      images: [...new Set(reps.map((r) => r.image))],
    };
  })
  // pool 라벨을 문자열로 정렬하면 1024, 2048, 4096, 512 순이 된다.
  .sort((a, b) => a.mode.localeCompare(b.mode) || poolOrder(a.pool) - poolOrder(b.pool) || a.rpm - b.rpm);

// 거부(REJECTED)는 async-threadpool 에서만 나온다. 예외 열이 없으면
// coroutine·webclient 회차에서 은행콜이 전부 타임아웃 나도 표가 조용하다.
const header = [
  'mode', 'pool', 'RPM', 'n', 'COMPLETED', 'PARTIAL',
  'FAILED(포화)', 'FAILED(오류)', '거부된 은행콜', '예외 은행콜',
  '처리율/분', 'e2e p95 (ms)', 'timeout',
];

const lines = [
  `## ${root}`,
  '',
  `| ${header.join(' | ')} |`,
  `| ${header.map(() => '---').join(' | ')} |`,
  ...rows.map((r) =>
    `| ${[
      r.mode, r.pool, r.rpm, r.n,
      fmt(r.completed) + r.completedSpread,
      fmt(r.partial),
      fmt(r.failedSaturated),
      fmt(r.failedError),
      fmt(r.callsRejected),
      fmt(r.callsException),
      r.perMin == null ? '—' : r.perMin.toFixed(1),
      fmt(r.e2eP95) + r.p95Spread,
      pct(r.timeoutRate),
    ].join(' | ')} |`,
  ),
  '',
];

const notes = [];

// 무효 회차. 왜 무효인지까지 적어야 "측정 실패"와 "포화"가 구분된다.
const invalid = runs.filter((r) => !r.valid);
if (invalid.length > 0) {
  // 드레인이 안 끝난 것과 DB 를 못 읽은 것을 갈라 적는다.
  const unreadable = invalid.filter((r) => r.drainUnreadable).length;
  const capped = invalid.filter((r) => r.drainCapped && !r.drainUnreadable).length;
  const unbalanced = invalid.filter((r) => !r.balanced).length;
  // 집계 쿼리가 실패한 회차. 안 갈라두면 "기동·k6 실패" 로 뭉뚱그려진다.
  const countsFailed = invalid.filter((r) => r.countsFailed).length;
  const rest = invalid.length - capped - unreadable - unbalanced - countsFailed;
  notes.push(
    `⚠️ 무효 회차 ${invalid.length}건 (표에서 제외). ` +
      `드레인 상한 ${capped}건, 드레인 중 DB 못 읽음 ${unreadable}건, ` +
      `집계 쿼리 실패 ${countsFailed}건, 집계 불일치 ${unbalanced}건, ` +
      `기동·k6 실패 ${rest}건.`,
  );
  if (unreadable > 0) {
    notes.push('⚠️ DB를 못 읽어 무효가 된 회차가 있다. 포화가 아니라 SSM·MySQL 문제다.');
  }
}

// 상한에 걸린 회차도 거부 수치는 남아 있다. 천장 판단의 근거가 된다.
const cappedWithCounts = runs.filter((r) => r.drainCapped && !r.drainUnreadable && r.callsRejected != null);
if (cappedWithCounts.length > 0) {
  const rejected = sum(cappedWithCounts.map((r) => r.callsRejected));
  notes.push(
    `ℹ️ 드레인 상한에 걸린 회차 ${cappedWithCounts.length}건의 거부된 은행콜 ${rejected}건. ` +
      `표에는 없지만 manifest에 남아 있다 — 이 지점이 천장 근처라는 신호다.`,
  );
}

// 상태별 합이 전체 run 수와 다르면 어느 칸에도 안 잡힌 run 이 있다는 뜻이다.
if (runs.some((r) => !r.balanced)) {
  notes.push('⚠️ run 상태 합이 전체 run 수와 다른 회차가 있다. 집계에서 빠진 상태가 있다.');
}

// 결과 행이 요청한 은행 수보다 적은 run. 저장에 실패했다는 뜻이고,
// 그 run은 실제보다 성공적으로 보인다.
const missingRows = sum(usable.map((r) => r.runsMissingRows));
if (missingRows > 0) {
  notes.push(
    `⚠️ 은행 결과가 덜 저장된 run ${missingRows}건. 응답은 받았으나 DB에 안 남았다. ` +
      `그 run은 상태가 실제보다 좋게 나오므로 처리량 비교에 쓰지 말 것.`,
  );
}

// 제출 실패는 부하와 무관한 원인(잘못된 은행 코드, 직렬화 오류)이다.
const submitErrors = sum(usable.map((r) => r.callsSubmitError));
if (submitErrors > 0) {
  notes.push(`⚠️ 제출 단계 실패 ${submitErrors}건. 풀 거부가 아니라 설정·코드 문제다. 부하 수치로 읽지 말 것.`);
}

// fan-out이 예외로 중단된 run. 부하 신호인 FAILED(포화)와 구분해서 센다.
const failedErrors = sum(usable.map((r) => r.failedError));
if (failedErrors > 0) {
  notes.push(
    `⚠️ 예외로 중단된 run ${failedErrors}건. 천장이 아니라 코드·설정 문제다. ` +
      `A 의 /var/log/bench/<회차>.log 를 볼 것.`,
  );
}

// 집계 단계에서 터진 run. fan-out 은 끝났으므로 DB 쪽 부하 증상이다.
const failedFinalize = sum(usable.map((r) => r.failedFinalize));
if (failedFinalize > 0) {
  notes.push(
    `⚠️ 집계 단계에서 터진 run ${failedFinalize}건. fan-out 은 끝났고 DB 쪽에서 막힌 것이라 ` +
      `천장 근처의 부하 증상으로 읽어야 한다. 커넥션 풀과 락 대기를 볼 것.`,
  );
}

// 거부는 async-threadpool 전용이라, 다른 모드의 은행콜 실패는 여기로만 보인다.
const exceptions = sum(usable.map((r) => r.callsException));
if (exceptions > 0) {
  notes.push(`⚠️ 예외로 끝난 은행콜 ${exceptions}건. 타임아웃·연결 실패다. mock 과 게이트웨이 사이를 볼 것.`);
}

// mock이 준 에러(E503 등). 은행 쪽 문제라 게이트웨이 천장과 무관하다.
const otherCalls = sum(usable.map((r) => r.callsOther));
if (otherCalls > 0) {
  notes.push(`⚠️ mock이 에러로 답한 은행콜 ${otherCalls}건. mock 쪽 상태를 확인할 것.`);
}

// k6 가 도착률을 못 맞춘 회차는 게이트웨이가 아니라 C 호스트를 잰 것이다.
const dropped = sum(usable.map((r) => r.dropped));
if (dropped > 0) {
  notes.push(`⚠️ k6가 버린 iteration ${dropped}건. 부하 생성기가 못 따라간 것이라 C 호스트 한계를 잰 것일 수 있다.`);
}

const singles = rows.filter((r) => r.n === 1).length;
if (singles > 0) {
  notes.push(`⚠️ ${singles}개 조건이 n=1이다. 흩어짐을 모르는 값이다.`);
}

// 측정 조건이 회차마다 달랐는지 여기서 드러난다.
const allImages = [...new Set(rows.flatMap((r) => r.images))].filter(Boolean);
lines.push('_측정 조건_');
lines.push(`- gateway image: ${allImages.join(', ') || '기록 없음'}`);
if (allImages.length > 1) {
  notes.push('⚠️ 이미지가 둘 이상이다. 이 표의 행들은 서로 다른 빌드로 측정됐다.');
}
const javaOpts = [...new Set(usable.map((r) => r.javaOpts).filter(Boolean))];
lines.push(`- java opts: ${javaOpts.join(' | ') || '없음'}`);
if (javaOpts.length > 1) {
  notes.push('⚠️ JVM 플래그가 회차마다 다르다. pool 비교가 교란된다.');
}

for (const n of notes) lines.push(`- ${n}`);

console.log(lines.join('\n'));
