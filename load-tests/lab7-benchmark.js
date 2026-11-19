// ЛР7: Single Node проти Sharded Cluster — змішане навантаження на журнал операцій.
//   50 % — POST /api/ledger/entries               (Shard-Local запис)
//   35 % — GET /api/users/{id}/ledger              (Shard-Local читання)
//   15 % — GET /api/reports/events/{id}/sales      (Cross-Shard, Scatter-Gather)
// Профіль: розігрів + ступені VUs (lib/common.js). Перцентилі рахуються окремо для кожного типу операції.
// Параметри: STAGES, STAGE_DURATION, USERS (10000), EVENTS (50), TAG, CONDITIONS.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS, buildOptions } from './lib/common.js';
import { lineChartSvg } from './lib/chart.js';

const TAG = __ENV.TAG || 'lab7-benchmark';
const USERS = Number(__ENV.USERS || 10000);
const EVENTS = Number(__ENV.EVENTS || 50);
const STAGES = (__ENV.STAGES || '10,25,50,100,200').split(',').map((x) => Number(x.trim())).filter((x) => x > 0);
const STAGE_SECONDS = Number((__ENV.STAGE_DURATION || '30s').replace('s', ''));
const OPS = ['write', 'local', 'fanout'];

const opMs = new Trend('op_ms', true);

export const options = buildOptions(false);
for (const vus of STAGES) {
    for (const op of OPS) {
        options.thresholds[`op_ms{op:${op},stage:${vus}}`] = ['max>=0'];
    }
}

const rnd = (n) => 1 + Math.floor(Math.random() * n);

export default function () {
    const p = Math.random();
    let res;
    let op;
    if (p < 0.5) {
        op = 'write';
        res = http.post(`${BASE_URL}/api/ledger/entries`, JSON.stringify({
            userId: rnd(USERS), eventId: rnd(EVENTS), type: Math.random() < 0.5 ? 'BUY' : 'SELL', amount: 100 + rnd(1400),
        }), { headers: JSON_HEADERS, tags: { name: 'POST /api/ledger/entries' } });
        check(res, { 'write 201': (r) => r.status === 201 });
    } else if (p < 0.85) {
        op = 'local';
        res = http.get(`${BASE_URL}/api/users/${rnd(USERS)}/ledger?limit=20`, { tags: { name: 'GET /api/users/{id}/ledger' } });
        check(res, { 'local 200': (r) => r.status === 200 });
    } else {
        op = 'fanout';
        res = http.get(`${BASE_URL}/api/reports/events/${rnd(EVENTS)}/sales`, { tags: { name: 'GET /api/reports/events/{id}/sales' } });
        check(res, { 'fanout 200': (r) => r.status === 200 });
    }
    opMs.add(res.timings.duration, { op });
}

function v(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function f(x, d = 1) {
    return Number.isFinite(x) ? x.toFixed(d) : '—';
}

export function handleSummary(data) {
    const rows = STAGES.map((vus) => {
        const r = { vus, rps: v(data, `http_reqs{stage:${vus}}`, 'count') / STAGE_SECONDS,
            err: v(data, `http_req_failed{stage:${vus}}`, 'rate') * 100 };
        for (const op of OPS) {
            r[`${op}95`] = v(data, `op_ms{op:${op},stage:${vus}}`, 'p(95)');
            r[`${op}99`] = v(data, `op_ms{op:${op},stage:${vus}}`, 'p(99)');
        }
        return r;
    });
    const md = [
        `### Журнал операцій — ${TAG}`,
        '',
        `Умови: ${__ENV.CONDITIONS || ''}; ступінь ${STAGE_SECONDS} с.`,
        '',
        '| VUs | RPS | Запис p95 / p99, мс | Shard-Local p95 / p99, мс | Cross-Shard p95 / p99, мс | Error Rate, % |',
        '|---|---|---|---|---|---|',
        ...rows.map((r) => `| ${r.vus} | ${f(r.rps)} | ${f(r.write95)} / ${f(r.write99)} | ${f(r.local95)} / ${f(r.local99)} | ${f(r.fanout95)} / ${f(r.fanout99)} | ${f(r.err, 2)} |`),
    ];
    const text = md.join('\n') + '\n';
    const csv = ['vus,rps,write_p95,write_p99,local_p95,local_p99,fanout_p95,fanout_p99,error_pct']
        .concat(rows.map((r) => [r.vus, f(r.rps), f(r.write95), f(r.write99), f(r.local95), f(r.local99),
            f(r.fanout95), f(r.fanout99), f(r.err, 2)].join(','))).join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.md`]: text,
        [`/results/${TAG}.csv`]: csv,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        [`/results/${TAG}-latency.svg`]: lineChartSvg({
            title: 'p95 за типом операції залежно від навантаження',
            subtitle: `${__ENV.CONDITIONS || TAG}; ступінь ${STAGE_SECONDS} с, constant VUs`,
            xLabel: 'VUs', yLabel: 'p95, мс', unit: 'мс',
            x: rows.map((r) => r.vus),
            series: [
                { name: 'Запис', values: rows.map((r) => r.write95) },
                { name: 'Shard-Local', values: rows.map((r) => r.local95) },
                { name: 'Cross-Shard', values: rows.map((r) => r.fanout95) },
            ],
        }),
    };
}
