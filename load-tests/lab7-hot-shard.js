// ЛР7: Hot Shard Scenario — навантаження на запис у журнал операцій із заданим профілем ключів.
//   PATTERN=even    — лише парні user_id (за key % 2 усі потрапляють на один шард)
//   PATTERN=uniform — рівномірні user_id
//   PATTERN=whale   — половина операцій одного користувача
// Затримка й кількість записів рахуються окремо для кожного шарду (заголовок X-Shard).
// Параметри: VUS, DURATION, USERS (10000), PATTERN, TAG, CONDITIONS.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const TAG = __ENV.TAG || 'lab7-hot-shard';
const PATTERN = __ENV.PATTERN || 'even';
const USERS = Number(__ENV.USERS || 10000);
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '30s';
const SHARDS = ['shard-a', 'shard-b'];

const shardWriteMs = new Trend('shard_write_ms', true);
const shardWrites = new Counter('shard_writes');

const thresholds = {
    'http_req_duration{scenario:load}': ['max>=0'],
    'http_reqs{scenario:load}': ['count>=0'],
    'http_req_failed{scenario:load}': ['rate>=0'],
};
for (const s of SHARDS) {
    thresholds[`shard_write_ms{shard:${s}}`] = ['max>=0'];
    thresholds[`shard_writes{shard:${s}}`] = ['count>=0'];
}

export const options = {
    scenarios: {
        warmup: { executor: 'constant-vus', vus: 10, duration: '10s', gracefulStop: '2s' },
        load: { executor: 'constant-vus', vus: VUS, duration: DURATION, startTime: '15s', gracefulStop: '5s' },
    },
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function userId() {
    const r = 1 + Math.floor(Math.random() * USERS);
    if (PATTERN === 'even') return 2 * r;
    if (PATTERN === 'whale') return Math.random() < 0.5 ? 1 : r + 1;
    return r;
}

export default function () {
    const body = JSON.stringify({
        userId: userId(),
        eventId: 1 + Math.floor(Math.random() * 50),
        type: Math.random() < 0.5 ? 'BUY' : 'SELL',
        amount: 100 + Math.floor(Math.random() * 1400),
    });
    const res = http.post(`${BASE_URL}/api/ledger/entries`, body, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'POST /api/ledger/entries' },
    });
    check(res, { 'HTTP 201': (r) => r.status === 201 });
    if (exec.scenario.name === 'load') {
        const shard = res.headers['X-Shard'] || 'none';
        shardWriteMs.add(res.timings.duration, { shard });
        shardWrites.add(1, { shard });
    }
}

function v(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function f(x, d = 1) {
    return Number.isFinite(x) ? x.toFixed(d) : '—';
}

export function handleSummary(data) {
    const seconds = Number(DURATION.replace('s', ''));
    const total = v(data, 'http_reqs{scenario:load}', 'count');
    const rps = total / seconds;
    const p95 = v(data, 'http_req_duration{scenario:load}', 'p(95)');
    const p99 = v(data, 'http_req_duration{scenario:load}', 'p(99)');
    const err = v(data, 'http_req_failed{scenario:load}', 'rate') * 100;

    const md = [
        `### Hot Shard — ${TAG}`,
        '',
        `Умови: ${__ENV.CONDITIONS || ''}; профіль ключів ${PATTERN}; ${VUS} VUs × ${DURATION}.`,
        '',
        `Throughput: ${f(rps)} RPS, p95 ${f(p95)} мс, p99 ${f(p99)} мс, помилок ${f(err, 2)} %.`,
        '',
        '| Шард | Записів | Частка, % | p50, мс | p95, мс | p99, мс | Max, мс |',
        '|---|---|---|---|---|---|---|',
    ];
    let written = 0;
    for (const s of SHARDS) written += v(data, `shard_writes{shard:${s}}`, 'count') || 0;
    for (const s of SHARDS) {
        const n = v(data, `shard_writes{shard:${s}}`, 'count') || 0;
        const t = `shard_write_ms{shard:${s}}`;
        md.push(`| ${s} | ${n} | ${f(written ? (100 * n) / written : 0)} | ${f(v(data, t, 'med'))} | ${f(v(data, t, 'p(95)'))} | ${f(v(data, t, 'p(99)'))} | ${f(v(data, t, 'max'))} |`);
    }
    const text = md.join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.md`]: text,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        [`/results/${TAG}.row`]: [f(rps), f(p95), f(p99), f(err, 2)].join(',') + '\n',
    };
}
