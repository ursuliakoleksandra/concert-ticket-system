// Генератор навантаження для ЛР3. Звертається лише до балансувальника (BASE_URL).
// Сценарій — «гарячий» запит пікового навантаження: перегляд активних оголошень популярної події.
//
// Параметри (змінні оточення): VUS — кількість паралельних клієнтів, DURATION — тривалість,
// TAG — назва прогону (ім'я файлу результатів у results/), PATH_UNDER_TEST — шлях запиту.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://nginx';
const PATH = __ENV.PATH_UNDER_TEST || '/api/events/1/listings';
const TAG = __ENV.TAG || 'run';
const INSTANCES = ['app1', 'app2', 'app3', 'none'];

const instanceHits = new Counter('instance_hits');

// Порогові значення-заглушки потрібні, щоб k6 обчислив підметрики за кожним instance
const thresholds = {};
for (const instance of INSTANCES) {
    thresholds[`instance_hits{instance:${instance}}`] = ['count>=0'];
}

export const options = {
    vus: Number(__ENV.VUS || 30),
    duration: __ENV.DURATION || '30s',
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds,
};

export default function () {
    const res = http.get(`${BASE_URL}${PATH}`);
    instanceHits.add(1, { instance: res.headers['X-Instance-Id'] || 'none' });
    check(res, { 'HTTP 200': (r) => r.status === 200 });
}

function metricValue(data, name, field) {
    const metric = data.metrics[name];
    return metric && metric.values[field] !== undefined ? metric.values[field] : 0;
}

export function handleSummary(data) {
    const total = metricValue(data, 'http_reqs', 'count');
    const lines = [
        `Прогін: ${TAG}  (VUs=${options.vus}, тривалість=${options.duration})`,
        `  Запитів усього:     ${total}`,
        `  Пропускна здатність: ${metricValue(data, 'http_reqs', 'rate').toFixed(1)} RPS`,
        `  Помилки (не 2xx/3xx): ${(metricValue(data, 'http_req_failed', 'rate') * 100).toFixed(2)} %`,
        `  Latency avg / p95 / p99 / max, мс: ` + ['avg', 'p(95)', 'p(99)', 'max']
            .map((f) => metricValue(data, 'http_req_duration', f).toFixed(1)).join(' / '),
        '  Розподіл за вузлами:',
    ];
    for (const instance of INSTANCES) {
        const hits = metricValue(data, `instance_hits{instance:${instance}}`, 'count');
        if (hits > 0) {
            const share = total > 0 ? ((hits * 100) / total).toFixed(1) : '0.0';
            lines.push(`    ${instance}: ${hits} запитів (${share} %)`);
        }
    }
    const text = lines.join('\n') + '\n';
    return {
        stdout: text,
        [`/results/${TAG}.txt`]: text,
        [`/results/${TAG}.json`]: JSON.stringify(data, null, 2),
        // Один рядок для зведених таблиць: RPS, p95, p99, частка помилок (%)
        [`/results/${TAG}.row`]: [
            metricValue(data, 'http_reqs', 'rate').toFixed(1),
            metricValue(data, 'http_req_duration', 'p(95)').toFixed(1),
            metricValue(data, 'http_req_duration', 'p(99)').toFixed(1),
            (metricValue(data, 'http_req_failed', 'rate') * 100).toFixed(2),
        ].join(' ') + '\n',
    };
}
