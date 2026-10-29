// Спільна частина сценаріїв ЛР5: профіль навантаження (warm-up + ступені VUs) і звіт.
//
// Профіль: послідовні k6-сценарії з постійною кількістю VUs (constant-vus) — по одному на ступінь.
// Кожен ступінь має тег stage=<VUs>, тому k6 рахує метрики окремо для кожного ступеня.
//
// Змінні оточення:
//   STAGES          ступені VUs через кому          (за замовчуванням 10,25,50,100,200)
//   STAGE_DURATION  тривалість ступеня              (30s)
//   WARMUP_DURATION тривалість розігріву, 0s — без  (20s)
//   WARMUP_VUS      VUs на розігріві                (10)
//   TAG             ім'я звіту в results/           (назва сценарію)
import { lineChartSvg } from './chart.js';

export const BASE_URL = __ENV.BASE_URL || 'http://nginx';

const STAGES = (__ENV.STAGES || '10,25,50,100,200').split(',').map((v) => Number(v.trim())).filter((v) => v > 0);
const STAGE_DURATION = __ENV.STAGE_DURATION || '30s';
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '20s';
const WARMUP_VUS = Number(__ENV.WARMUP_VUS || 10);
const GAP_SECONDS = 5; // пауза між ступенями, щоб ступені не перетиналися

function seconds(d) {
    const m = String(d).match(/^(\d+(?:\.\d+)?)(ms|s|m)$/);
    if (!m) throw new Error(`Невірна тривалість: ${d}`);
    const n = Number(m[1]);
    return m[2] === 'ms' ? n / 1000 : m[2] === 'm' ? n * 60 : n;
}

/** @param withCacheMetric true — сценарій рахує частку X-Cache: HIT (метрика cache_hit) */
export function buildOptions(withCacheMetric = false) {
    const scenarios = {};
    const thresholds = {};
    let offset = 0;
    if (seconds(WARMUP_DURATION) > 0) {
        scenarios.warmup = {
            executor: 'constant-vus', vus: WARMUP_VUS, duration: WARMUP_DURATION,
            startTime: '0s', gracefulStop: '5s', tags: { stage: 'warmup' },
        };
        offset += seconds(WARMUP_DURATION) + GAP_SECONDS;
    }
    for (const vus of STAGES) {
        scenarios[`vus_${vus}`] = {
            executor: 'constant-vus', vus, duration: STAGE_DURATION,
            startTime: `${offset}s`, gracefulStop: `${GAP_SECONDS}s`, tags: { stage: String(vus) },
        };
        offset += seconds(STAGE_DURATION) + GAP_SECONDS;
        // Порогові значення-заглушки змушують k6 обчислити підметрики кожного ступеня
        thresholds[`http_req_duration{stage:${vus}}`] = ['max>=0'];
        thresholds[`http_reqs{stage:${vus}}`] = ['count>=0'];
        thresholds[`http_req_failed{stage:${vus}}`] = ['rate>=0'];
    }
    if (withCacheMetric) thresholds['cache_hit'] = ['rate>=0'];
    return {
        scenarios,
        thresholds,
        summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
        setupTimeout: '180s',
    };
}

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function userHeaders(userId) {
    return { 'Content-Type': 'application/json', 'X-User-Id': String(userId) };
}

/** Унікальний рядок для місць/email, стійкий до перевикористання VU між ступенями. */
export function uid() {
    return `${__VU}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function val(data, name, field) {
    const m = data.metrics[name];
    return m && m.values && m.values[field] !== undefined ? m.values[field] : NaN;
}

function rows(data) {
    const dur = seconds(STAGE_DURATION);
    return STAGES.map((vus) => {
        const d = `http_req_duration{stage:${vus}}`;
        const count = val(data, `http_reqs{stage:${vus}}`, 'count');
        return {
            vus,
            requests: count,
            rps: count / dur,
            avg: val(data, d, 'avg'),
            p50: val(data, d, 'med'),
            p95: val(data, d, 'p(95)'),
            p99: val(data, d, 'p(99)'),
            max: val(data, d, 'max'),
            errors: val(data, `http_req_failed{stage:${vus}}`, 'rate') * 100,
        };
    });
}

function n(v, digits = 1) {
    return Number.isFinite(v) ? v.toFixed(digits) : '—';
}

/**
 * Звіт сценарію: таблиця в stdout, Markdown/CSV/JSON і два SVG-графіки в /results.
 * @param name  ідентифікатор сценарію (ім'я файлів)
 * @param title людська назва сценарію
 */
export function report(data, name, title) {
    const tag = __ENV.TAG || name;
    const r = rows(data);
    const hitRate = val(data, 'cache_hit', 'rate');
    const conditions = `${__ENV.CONDITIONS || ''}`.trim();

    const md = [];
    md.push(`### ${title}`);
    md.push('');
    if (conditions) md.push(`Умови: ${conditions}.`);
    md.push(`Ступінь: ${STAGE_DURATION} з постійною кількістю VUs; розігрів: ${WARMUP_DURATION} × ${WARMUP_VUS} VUs.`);
    md.push('');
    md.push('| Навантаження (VUs) | Запитів | Throughput (RPS) | Avg, мс | p50, мс | p95, мс | p99 (Tail), мс | Max, мс | Error Rate, % |');
    md.push('|---|---|---|---|---|---|---|---|---|');
    for (const x of r) {
        md.push(`| ${x.vus} | ${x.requests} | ${n(x.rps)} | ${n(x.avg)} | ${n(x.p50)} | ${n(x.p95)} | ${n(x.p99)} | ${n(x.max)} | ${n(x.errors, 2)} |`);
    }
    if (Number.isFinite(hitRate)) {
        md.push('');
        md.push(`Cache hit ratio (X-Cache: HIT серед кешованих запитів): ${n(hitRate * 100)} %`);
    }
    const text = md.join('\n') + '\n';

    const csv = ['vus,requests,rps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,error_pct']
        .concat(r.map((x) => [x.vus, x.requests, n(x.rps), n(x.avg), n(x.p50), n(x.p95), n(x.p99), n(x.max), n(x.errors, 2)].join(',')))
        .join('\n') + '\n';

    const files = {
        stdout: text,
        [`/results/${tag}.md`]: text,
        [`/results/${tag}.csv`]: csv,
        [`/results/${tag}.json`]: JSON.stringify(data, null, 2),
    };

    if (r.length > 1) {
        const subtitle = `${title}; ступінь ${STAGE_DURATION}, constant VUs`;
        files[`/results/${tag}-throughput.svg`] = lineChartSvg({
            title: 'Throughput залежно від навантаження',
            subtitle,
            xLabel: 'VUs', yLabel: 'Throughput, RPS', unit: 'RPS',
            x: r.map((x) => x.vus),
            series: [{ name: 'RPS', values: r.map((x) => x.rps) }],
        });
        files[`/results/${tag}-latency.svg`] = lineChartSvg({
            title: 'Перцентилі затримки залежно від навантаження',
            subtitle,
            xLabel: 'VUs', yLabel: 'Затримка, мс', unit: 'мс',
            x: r.map((x) => x.vus),
            series: [
                { name: 'p50', values: r.map((x) => x.p50) },
                { name: 'p95', values: r.map((x) => x.p95) },
                { name: 'p99', values: r.map((x) => x.p99) },
            ],
        });
        files[`/results/${tag}-errors.svg`] = lineChartSvg({
            title: 'Частка помилок залежно від навантаження',
            subtitle,
            xLabel: 'VUs', yLabel: 'Error Rate, %', unit: '%',
            x: r.map((x) => x.vus),
            series: [{ name: 'Error Rate', values: r.map((x) => x.errors) }],
        });
    }
    return files;
}
