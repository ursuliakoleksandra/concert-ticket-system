// Scenario C — Complex Workflow: наскрізний бізнес-сценарій перепродажу.
//   1. GET  /api/events                        — покупець переглядає каталог
//   2. POST /api/events                        — організатор публікує подію
//   3. POST /api/tickets                       — випуск квитка продавцю
//   4. POST /api/listings                      — продавець виставляє квиток на перепродаж
//   5. GET  /api/events/{id}/listings          — покупець шукає оголошення (Cache MISS → HIT)
//   6. POST /api/listings/{id}/hold            — бронювання в Redis (SET NX)
//   7. POST /api/listings/{id}/purchase        — купівля: блокування рядків, 3 UPDATE/INSERT, COMMIT
//   8. GET  /api/listings/{id}                 — перевірка статусу SOLD
// Кожна ітерація створює власну подію, тому ліміт «4 квитки на подію» не спрацьовує.
// Ресурсний профіль: змішаний — читання, кеш, Redis, транзакції з блокуваннями (SELECT … FOR UPDATE).
// Потребує підвищеного RATE_LIMIT_PER_MINUTE (див. scripts/lab5-run-all.sh), інакше бронювання отримають 429.
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, JSON_HEADERS, buildOptions, report, uid, userHeaders } from './lib/common.js';

const BUYERS = Number(__ENV.BUYERS || 200);

export const options = buildOptions();

export function setup() {
    const mk = (name) => {
        const res = http.post(`${BASE_URL}/api/users`,
            JSON.stringify({ email: `${name}-${uid()}@load.test`, fullName: `Load ${name}` }), { headers: JSON_HEADERS });
        if (res.status !== 201) fail(`setup: користувач ${name} → ${res.status}`);
        return res.json('id');
    };
    const sellerId = mk('seller-c');
    const buyers = [];
    for (let i = 0; i < BUYERS; i++) buyers.push(mk(`buyer-${i}`));
    return { sellerId, buyers };
}

export default function (ctx) {
    const buyerId = ctx.buyers[(__VU - 1) % ctx.buyers.length];
    const ok = (res, name, status) => check(res, { [`${name} ${status}`]: (r) => r.status === status });

    ok(http.get(`${BASE_URL}/api/events?page=0&size=20`, { tags: { name: 'GET /api/events' } }), 'events', 200);

    const event = http.post(`${BASE_URL}/api/events`,
        JSON.stringify({ title: `Workflow ${uid()}`, venue: 'Chernivtsi', startsAt: '2031-06-01T18:00:00Z', faceValue: 500 }),
        { headers: JSON_HEADERS, tags: { name: 'POST /api/events' } });
    if (!ok(event, 'event', 201)) return;
    const eventId = event.json('id');

    const ticket = http.post(`${BASE_URL}/api/tickets`,
        JSON.stringify({ eventId, ownerId: ctx.sellerId, seat: `C-${uid()}` }),
        { headers: JSON_HEADERS, tags: { name: 'POST /api/tickets' } });
    if (!ok(ticket, 'ticket', 201)) return;

    const listing = http.post(`${BASE_URL}/api/listings`,
        JSON.stringify({ ticketId: ticket.json('id'), price: 550 }),
        { headers: userHeaders(ctx.sellerId), tags: { name: 'POST /api/listings' } });
    if (!ok(listing, 'listing', 201)) return;
    const listingId = listing.json('id');

    ok(http.get(`${BASE_URL}/api/events/${eventId}/listings`, { tags: { name: 'GET /api/events/{id}/listings' } }),
        'search', 200);

    const hold = http.post(`${BASE_URL}/api/listings/${listingId}/hold`, null,
        { headers: userHeaders(buyerId), tags: { name: 'POST /api/listings/{id}/hold' } });
    if (!ok(hold, 'hold', 201)) return;

    const purchase = http.post(`${BASE_URL}/api/listings/${listingId}/purchase`, null,
        { headers: userHeaders(buyerId), tags: { name: 'POST /api/listings/{id}/purchase' } });
    if (!ok(purchase, 'purchase', 201)) return;

    const sold = http.get(`${BASE_URL}/api/listings/${listingId}`, { tags: { name: 'GET /api/listings/{id}' } });
    check(sold, { 'status SOLD': (r) => r.status === 200 && r.json('status') === 'SOLD' });
}

export function handleSummary(data) {
    return report(data, 'lab5-scenario-c-workflow', 'Scenario C — Complex Workflow');
}
