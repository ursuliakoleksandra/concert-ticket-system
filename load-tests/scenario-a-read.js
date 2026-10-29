// Scenario A — Read-Intensive: перегляд каталогу покупцями під час старту перепродажу.
//   80 % — GET /api/events/{id}/listings (кешований endpoint, ЛР4: Redis Cache-Aside)
//   20 % — GET /api/events?page=0&size=20 (каталог подій, читається з PostgreSQL)
// Ресурсний профіль: Redis (кеш), пам'ять і CPU вузлів API, читання з PostgreSQL при MISS.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, buildOptions, report } from './lib/common.js';

const EVENT_ID = __ENV.EVENT_ID || '1';
const cacheHit = new Rate('cache_hit');

export const options = buildOptions(true);

export default function () {
    if (Math.random() < 0.8) {
        const res = http.get(`${BASE_URL}/api/events/${EVENT_ID}/listings`, {
            tags: { name: 'GET /api/events/{id}/listings' },
        });
        cacheHit.add(res.headers['X-Cache'] === 'HIT');
        check(res, { 'listings 200': (r) => r.status === 200 });
    } else {
        const res = http.get(`${BASE_URL}/api/events?page=0&size=20`, { tags: { name: 'GET /api/events' } });
        check(res, { 'events 200': (r) => r.status === 200 });
    }
}

export function handleSummary(data) {
    return report(data, 'lab5-scenario-a-read', 'Scenario A — Read-Intensive');
}
