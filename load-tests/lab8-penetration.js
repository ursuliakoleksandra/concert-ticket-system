// ЛР8: Cache Penetration — потік запитів до неіснуючих подій (GET /api/events/{id}/stats → 404).
// Без Negative Caching кожен такий запит проходить крізь кеш у СУБД; з ним — один запит на id за TTL.
//   setup   — скидання кешу і лічильників
//   attack  — VUS запитують IDS різних неіснуючих id (9 000 001 … 9 000 000 + IDS) протягом DURATION
//   final   — лічильник звернень до СУБД
// Параметри: VUS (100), DURATION (20s), IDS (200), TAG, CONDITIONS.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const TAG = __ENV.TAG || 'lab8-penetration';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '20s';
const SECONDS = Number(DURATION.replace('s', ''));
const IDS = Number(__ENV.IDS || 200);
const STATUSES = ['HIT', 'MISS', 'COALESCED', 'BYPASS', 'none'];

const notFoundLoads = new Gauge('not_found_db_loads');
const cacheStatus = new Counter('cache_status');

const thresholds = {
    'http_req_duration{scenario:attack}': ['max>=0'],
    'http_reqs{scenario:attack}': ['count>=0'],
    not_found_db_loads: ['value>=0'],
};
for (const s of STATUSES) thresholds[`cache_status{status:${s}}`] = ['count>=0'];

export const options = {
    scenarios: {
        attack: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'attack', gracefulStop: '5s' },
        final: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: `${SECONDS + 6}s`, exec: 'snapshot' },
    },
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    http.post(`${BASE_URL}/api/admin/stats-cache/reset`);
}

export function attack() {
    const id = 9000000 + 1 + Math.floor(Math.random() * IDS);
    const res = http.get(`${BASE_URL}/api/events/${id}/stats`, {
        tags: { name: 'GET /api/events/{missing}/stats' },
        responseCallback: http.expectedStatuses(404),
    });
    cacheStatus.add(1, { status: res.headers['X-Cache'] || 'none' });
    check(res, { 'HTTP 404': (r) => r.status === 404 });
}

export function snapshot() {
    const res = http.get(`${BASE_URL}/api/admin/stats-cache`);
    if (res.status === 200) notFoundLoads.add(res.json('notFoundLoads'));
}

function v(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function f(x, d = 1) {
    return Number.isFinite(x) ? x.toFixed(d) : '—';
}

export function handleSummary(data) {
    const total = v(data, 'http_reqs{scenario:attack}', 'count');
    const loads = v(data, 'not_found_db_loads', 'value');
    const statuses = STATUSES
        .map((s) => [s, v(data, `cache_status{status:${s}}`, 'count') || 0])
        .filter(([, n]) => n > 0)
        .map(([s, n]) => `${s}: ${n}`).join(', ');
    const md = [
        `### Cache Penetration — ${TAG}`,
        '',
        `Умови: ${__ENV.CONDITIONS || ''}; ${VUS} VUs × ${DURATION}, ${IDS} різних неіснуючих id.`,
        '',
        '| Запитів | Звернень до СУБД | Частка запитів, що дійшли до СУБД, % | Throughput, RPS | p95, мс | p99, мс |',
        '|---|---|---|---|---|---|',
        `| ${total} | ${f(loads, 0)} | ${f(total ? (100 * loads) / total : 0, 2)} | ${f(total / SECONDS)} | ${f(v(data, 'http_req_duration{scenario:attack}', 'p(95)'))} | ${f(v(data, 'http_req_duration{scenario:attack}', 'p(99)'))} |`,
        '',
        `X-Cache: ${statuses}`,
    ];
    const text = md.join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.md`]: text,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        [`/results/${TAG}.row`]: [total, f(loads, 0), f(total / SECONDS), f(v(data, 'http_req_duration{scenario:attack}', 'p(95)')),
            f(v(data, 'http_req_duration{scenario:attack}', 'p(99)'))].join(',') + '\n',
    };
}
