#!/usr/bin/env bash
# ЛР2, сценарій Cross-Instance Consistency.
# Послідовні запити одного бізнес-сценарію виконуються РІЗНИМИ екземплярами напряму
# (без балансувальника і без sticky sessions). Кожен наступний крок бачить результат попереднього.
source "$(dirname "$0")/lib.sh"

step "1. CRUD події: POST → app1, GET → app2, PUT → app2, GET → app1"
on_node app1 POST /api/events \
    '{"title":"Джазовий вечір","venue":"Філармонія","startsAt":"2030-03-15T19:00:00Z","faceValue":400}'
expect_status 201
EVENT_ID=$(last_id)

on_node app2 GET "/api/events/$EVENT_ID"
expect_status 200
expect_body_contains '"title":"Джазовий вечір"'

on_node app2 PUT "/api/events/$EVENT_ID" \
    '{"title":"Джазовий вечір (перенесено)","venue":"Філармонія","startsAt":"2030-03-22T19:00:00Z","faceValue":400}'
expect_status 200

on_node app1 GET "/api/events/$EVENT_ID"
expect_status 200
expect_body_contains '(перенесено)'

step "2. Перепродаж: оголошення → app2, бронювання → app3, перевірка → app1, купівля → app2/app1"
on_node app1 POST /api/tickets "{\"eventId\":$EVENT_ID,\"ownerId\":1,\"seat\":\"Партер, ряд 5, місце 10\"}"
expect_status 201
TICKET_ID=$(last_id)

on_node app2 POST /api/listings "{\"ticketId\":$TICKET_ID,\"price\":450}" 1
expect_status 201
LISTING_ID=$(last_id)

# Ірина (id=3) бронює оголошення через app3 — бронювання записується в Redis
on_node app3 POST "/api/listings/$LISTING_ID/hold" "" 3
expect_status 201

# app1 бачить бронювання, створене іншим екземпляром
on_node app1 GET "/api/listings/$LISTING_ID/hold"
expect_status 200
expect_body_contains '"buyerId":3'

# Андрій (id=2) через app2 намагається купити заброньоване оголошення → 409
on_node app2 POST "/api/listings/$LISTING_ID/purchase" "" 2
expect_status 409

# Ірина купує через app1 — бронювання, створене на app3, визнається
on_node app1 POST "/api/listings/$LISTING_ID/purchase" "" 3
expect_status 201

# Результат купівлі видно з будь-якого екземпляра
on_node app3 GET "/api/listings/$LISTING_ID"
expect_status 200
expect_body_contains '"status":"SOLD"'

on_node app2 GET /api/users/3/tickets
expect_status 200
expect_body_contains "\"id\":$TICKET_ID"

step "3. Розподілений rate limit: ліміт спільний для всіх екземплярів"
# Ліміт за замовчуванням — 20 POST-запитів бронювання/купівлі на користувача за хвилину.
# Запити рівномірно розкидаються по трьох вузлах; локальний лічильник дозволив би 3 × 20 = 60.
# Окремий новий користувач — щоб ліміт не заважав іншим сценаріям протягом хвилини.
on_node app1 POST /api/users "{\"email\":\"bot-$RANDOM$RANDOM@example.com\",\"fullName\":\"Тестовий бот\"}"
expect_status 201
BOT_ID=$(last_id)
limited_on=""
for i in $(seq 1 25); do
    target="app$(( (i % 3) + 1 ))"
    raw=$(docker compose exec -T "$target" curl -s -o /dev/null -w '%{http_code}' \
        -X POST "http://localhost:8080/api/listings/$LISTING_ID/hold" -H "X-User-Id: $BOT_ID") || raw="000"
    if [[ "$raw" == "429" ]]; then
        limited_on="запит №$i на $target"
        break
    fi
done
if [[ -n "$limited_on" ]]; then
    printf '  ✓ 429 Too Many Requests: %s (лічильник у Redis, спільний для app1..app3)\n' "$limited_on"
else
    printf '  ✗ Ліміт не спрацював за 25 запитів\n' >&2
    exit 1
fi

printf '\nУсі перевірки пройдено: кожен екземпляр бачить актуальний стан, створений іншими.\n'
