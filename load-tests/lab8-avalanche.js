// ЛР8: Cache Avalanche — синхронне закінчення TTL групи популярних ключів.
//   setup   — EVENTS нових подій; скидання кешу; ОДНОЧАСНИЙ прогрів статистики всіх подій
//             (усі ключі створюються в одну секунду → без jitter закінчуються в одну секунду)
//   load    — VUS читають статистику випадкових подій протягом DURATION
//   monitor — щосекунди фіксує приріст звернень до СУБД
// Результат — часовий ряд: звернення до СУБД і p95 затримки по секундах + графіки SVG.
// Параметри: VUS (100), DURATION (60s), EVENTS (50), TAG, CONDITIONS.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { lineChartSvg } from './lib/chart.js';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const TAG = __ENV.TAG || 'lab8-avalanche';
const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || '60s';
const SECONDS = Number(DURATION.replace('s', ''));
const EVENTS = Number(__ENV.EVENTS || 50);

const dbLoadsPerSec = new Counter('db_loads_per_sec');
const statsMs = new Trend('stats_ms', true);

const thresholds = {
    'http_req_duration{scenario:load}': ['max>=0'],
    'http_req_failed{scenario:load}': ['rate>=0'],
    'http_reqs{scenario:load}': ['count>=0'],
};
for (let s = 0; s < SECONDS; s++) {
    thresholds[`db_loads_per_sec{sec:${s}}`] = ['count>=0'];
    thresholds[`stats_ms{sec:${s}}`] = ['max>=0'];
}

export const options = {
    scenarios: {
        load: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'read', gracefulStop: '5s' },
        monitor: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'monitor', gracefulStop: '2s' },
    },
    thresholds,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '120s',
};

export function setup() {
    const create = [];
    for (let i = 0; i < EVENTS; i++) {
        create.push(['POST', `${BASE_URL}/api/events`, JSON.stringify({
            title: `Avalanche ${i}`, venue: 'Чернівці', startsAt: '2031-01-01T18:00:00Z', faceValue: 500,
        }), { headers: { 'Content-Type': 'application/json' } }]);
    }
    const ids = http.batch(create).map((r) => r.json('id'));
    http.post(`${BASE_URL}/api/admin/stats-cache/reset`);
    // Усі ключі прогріваються одночасно — однаковий момент створення
    http.batch(ids.map((id) => ['GET', `${BASE_URL}/api/events/${id}/stats`]));
    return { ids, t0: Date.now() };
}

function second(t0) {
    return Math.floor((Date.now() - t0) / 1000);
}

export function read(ctx) {
    const id = ctx.ids[Math.floor(Math.random() * ctx.ids.length)];
    const res = http.get(`${BASE_URL}/api/events/${id}/stats`, { tags: { name: 'GET /api/events/{id}/stats' } });
    check(res, { 'HTTP 200': (r) => r.status === 200 });
    const sec = second(ctx.t0);
    if (sec < SECONDS) statsMs.add(res.timings.duration, { sec: String(sec) });
}

let previous = null;
export function monitor(ctx) {
    const res = http.get(`${BASE_URL}/api/admin/stats-cache`, { tags: { name: 'admin' } });
    if (res.status === 200) {
        const total = res.json('dbLoads');
        if (previous !== null) {
            const sec = second(ctx.t0);
            if (sec < SECONDS) dbLoadsPerSec.add(total - previous, { sec: String(sec) });
        }
        previous = total;
    }
    sleep(1);
}

function v(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function f(x, d = 1) {
    return Number.isFinite(x) ? x.toFixed(d) : '—';
}

export function handleSummary(data) {
    const secs = [];
    const loads = [];
    const p95 = [];
    for (let s = 0; s < SECONDS; s++) {
        secs.push(s);
        loads.push(v(data, `db_loads_per_sec{sec:${s}}`, 'count') || 0);
        p95.push(v(data, `stats_ms{sec:${s}}`, 'p(95)'));
    }
    const totalLoads = loads.reduce((a, b) => a + b, 0);
    const peakLoads = Math.max(...loads);
    const peakP95 = Math.max(...p95.filter(Number.isFinite));
    const rps = v(data, 'http_reqs{scenario:load}', 'count') / SECONDS;
    const subtitle = `${__ENV.CONDITIONS || TAG}; ${EVENTS} ключів, ${VUS} VUs`;

    const md = [
        `### Cache Avalanche — ${TAG}`,
        '',
        `Умови: ${subtitle}; ${DURATION}.`,
        '',
        '| Звернень до СУБД | Пік звернень за 1 с | Throughput, RPS | p95, мс | p99, мс | Пік p95 за 1 с, мс | Error Rate, % |',
        '|---|---|---|---|---|---|---|',
        `| ${totalLoads} | ${peakLoads} | ${f(rps)} | ${f(v(data, 'http_req_duration{scenario:load}', 'p(95)'))} | ${f(v(data, 'http_req_duration{scenario:load}', 'p(99)'))} | ${f(peakP95)} | ${f(v(data, 'http_req_failed{scenario:load}', 'rate') * 100, 2)} |`,
        '',
        'Звернення до СУБД по секундах: ' + loads.join(' '),
    ];
    const text = md.join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.md`]: text,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        [`/results/${TAG}.row`]: [totalLoads, peakLoads, f(rps), f(v(data, 'http_req_duration{scenario:load}', 'p(95)')),
            f(v(data, 'http_req_duration{scenario:load}', 'p(99)')), f(peakP95)].join(',') + '\n',
        [`/results/${TAG}-db-loads.svg`]: lineChartSvg({
            title: 'Звернення до СУБД за секунду', subtitle,
            xLabel: 'Секунда тесту', yLabel: 'Звернень / с', unit: '',
            x: secs, series: [{ name: 'Звернення до СУБД', values: loads }],
        }),
        [`/results/${TAG}-latency.svg`]: lineChartSvg({
            title: 'p95 затримки за секунду', subtitle,
            xLabel: 'Секунда тесту', yLabel: 'p95, мс', unit: 'мс',
            x: secs, series: [{ name: 'p95', values: p95 }],
        }),
    };
}
