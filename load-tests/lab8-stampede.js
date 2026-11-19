// ЛР8: Cache Stampede / Thundering Herd — сотні одночасних запитів до одного холодного ключа.
//   setup     — скидання кешу статистики і лічильника звернень до СУБД
//   warmup    — 20 VUs × 10 с на іншу подію (JIT, пули з'єднань), ключ події EVENT_ID лишається холодним
//   baseline  — знімок лічильника звернень до СУБД перед атакою
//   stampede  — VUS (500) одночасно читають статистику EVENT_ID протягом DURATION
//   final     — знімок лічильника після атаки
// Звернень до СУБД через холодний ключ = final − baseline.
// Параметри: VUS, DURATION, EVENT_ID (1), TAG, CONDITIONS.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Gauge } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const TAG = __ENV.TAG || 'lab8-stampede';
const VUS = Number(__ENV.VUS || 500);
const DURATION = __ENV.DURATION || '15s';
const SECONDS = Number(DURATION.replace('s', ''));
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const STATUSES = ['HIT', 'MISS', 'COALESCED', 'BYPASS', 'none'];

const dbLoadsBefore = new Gauge('db_loads_before');
const dbLoadsAfter = new Gauge('db_loads_after');
const cacheStatus = new Counter('cache_status');

const thresholds = {
    'http_req_duration{scenario:stampede}': ['max>=0'],
    'http_reqs{scenario:stampede}': ['count>=0'],
    'http_req_failed{scenario:stampede}': ['rate>=0'],
    db_loads_before: ['value>=0'],
    db_loads_after: ['value>=0'],
};
for (const s of STATUSES) thresholds[`cache_status{status:${s}}`] = ['count>=0'];

export const options = {
    scenarios: {
        warmup: { executor: 'constant-vus', vus: 20, duration: '10s', exec: 'warm', gracefulStop: '2s' },
        baseline: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: '13s', exec: 'snapshotBefore' },
        stampede: { executor: 'constant-vus', vus: VUS, duration: DURATION, startTime: '15s', exec: 'hit', gracefulStop: '10s' },
        final: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: `${15 + SECONDS + 11}s`, exec: 'snapshotAfter' },
    },
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '60s',
};

export function setup() {
    const res = http.post(`${BASE_URL}/api/admin/stats-cache/reset`);
    if (res.status !== 200) throw new Error(`reset → ${res.status}`);
}

export function warm() {
    http.get(`${BASE_URL}/api/events/${EVENT_ID + 1}/stats`, { tags: { name: 'warmup' } });
}

function dbLoads() {
    const res = http.get(`${BASE_URL}/api/admin/stats-cache`, { tags: { name: 'admin' } });
    return res.status === 200 ? res.json('dbLoads') : NaN;
}

export function snapshotBefore() {
    dbLoadsBefore.add(dbLoads());
}

export function snapshotAfter() {
    dbLoadsAfter.add(dbLoads());
}

export function hit() {
    const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/stats`, { tags: { name: 'GET /api/events/{id}/stats' } });
    cacheStatus.add(1, { status: res.headers['X-Cache'] || 'none' });
    check(res, { 'HTTP 200': (r) => r.status === 200 });
}

function v(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function f(x, d = 1) {
    return Number.isFinite(x) ? x.toFixed(d) : '—';
}

export function handleSummary(data) {
    const loads = v(data, 'db_loads_after', 'value') - v(data, 'db_loads_before', 'value');
    const total = v(data, 'http_reqs{scenario:stampede}', 'count');
    const rps = total / SECONDS;
    const p95 = v(data, 'http_req_duration{scenario:stampede}', 'p(95)');
    const p99 = v(data, 'http_req_duration{scenario:stampede}', 'p(99)');
    const max = v(data, 'http_req_duration{scenario:stampede}', 'max');
    const err = v(data, 'http_req_failed{scenario:stampede}', 'rate') * 100;
    const statuses = STATUSES
        .map((s) => [s, v(data, `cache_status{status:${s}}`, 'count') || 0])
        .filter(([, n]) => n > 0)
        .map(([s, n]) => `${s}: ${n}`).join(', ');

    const md = [
        `### Cache Stampede — ${TAG}`,
        '',
        `Умови: ${__ENV.CONDITIONS || ''}; ${VUS} VUs одночасно × ${DURATION}, один холодний ключ (подія ${EVENT_ID}).`,
        '',
        '| Звернень до СУБД | Запитів | Throughput, RPS | p95, мс | p99, мс | Max, мс | Error Rate, % |',
        '|---|---|---|---|---|---|---|',
        `| ${f(loads, 0)} | ${total} | ${f(rps)} | ${f(p95)} | ${f(p99)} | ${f(max)} | ${f(err, 2)} |`,
        '',
        `X-Cache: ${statuses}`,
    ];
    const text = md.join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.md`]: text,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        [`/results/${TAG}.row`]: [f(loads, 0), f(rps), f(p95), f(p99), f(err, 2)].join(',') + '\n',
    };
}
