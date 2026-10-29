// Scenario B — Write-Intensive: продавці масово виставляють квитки на перепродаж.
// Ітерація: POST /api/tickets (випуск квитка) → POST /api/listings (оголошення).
// Ресурсний профіль: INSERT у PostgreSQL (WAL, fsync при COMMIT), унікальні індекси,
// перевірки бізнес-правил, інвалідація кешу події в Redis після кожного оголошення.
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, JSON_HEADERS, buildOptions, report, uid, userHeaders } from './lib/common.js';

export const options = buildOptions();

export function setup() {
    const seller = http.post(`${BASE_URL}/api/users`,
        JSON.stringify({ email: `seller-b-${uid()}@load.test`, fullName: 'Load Seller B' }), { headers: JSON_HEADERS });
    const event = http.post(`${BASE_URL}/api/events`,
        JSON.stringify({ title: 'Load test B', venue: 'Chernivtsi', startsAt: '2031-01-15T18:00:00Z', faceValue: 500 }),
        { headers: JSON_HEADERS });
    if (seller.status !== 201 || event.status !== 201) {
        fail(`setup: користувач ${seller.status}, подія ${event.status}`);
    }
    return { sellerId: seller.json('id'), eventId: event.json('id') };
}

export default function (ctx) {
    const ticket = http.post(`${BASE_URL}/api/tickets`,
        JSON.stringify({ eventId: ctx.eventId, ownerId: ctx.sellerId, seat: `B-${uid()}` }),
        { headers: JSON_HEADERS, tags: { name: 'POST /api/tickets' } });
    if (!check(ticket, { 'ticket 201': (r) => r.status === 201 })) return;

    const listing = http.post(`${BASE_URL}/api/listings`,
        JSON.stringify({ ticketId: ticket.json('id'), price: 550 }),
        { headers: userHeaders(ctx.sellerId), tags: { name: 'POST /api/listings' } });
    check(listing, { 'listing 201': (r) => r.status === 201 });
}

export function handleSummary(data) {
    return report(data, 'lab5-scenario-b-write', 'Scenario B — Write-Intensive');
}
