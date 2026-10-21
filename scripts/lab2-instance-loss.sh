#!/usr/bin/env bash
# ЛР2, сценарій Restart / Instance Loss.
# Бізнес-сценарій «бронювання → купівля» починається на app1, app1 аварійно зупиняється (SIGKILL),
# сценарій завершується на app2. Після перезапуску app1 бачить актуальний стан.
source "$(dirname "$0")/lib.sh"

step "1. Підготовка: оголошення та бронювання на app1"
create_listing_on app1
on_node app1 POST "/api/listings/$LISTING_ID/hold" "" 3
expect_status 201

step "2. Аварійна зупинка app1 (docker kill — SIGKILL, без graceful shutdown)"
docker compose kill app1
docker compose ps --all --format '  {{.Name}}: {{.State}} ({{.Status}})'

step "3. app2 підхоплює сценарій: бронювання на місці, купівля успішна"
on_node app2 GET "/api/listings/$LISTING_ID/hold"
expect_status 200
expect_body_contains '"buyerId":3'

on_node app2 POST "/api/listings/$LISTING_ID/purchase" "" 3
expect_status 201

on_node app3 GET /api/users/3/purchases
expect_status 200
expect_body_contains "\"listingId\":$LISTING_ID"

step "4. Повторна купівля того ж оголошення іншим покупцем через app3 → 409"
on_node app3 POST "/api/listings/$LISTING_ID/purchase" "" 2
expect_status 409

step "5. Перезапуск app1: стан не втрачено"
docker compose start app1
wait_healthy app1
on_node app1 GET "/api/listings/$LISTING_ID"
expect_status 200
expect_body_contains '"status":"SOLD"'

on_node app1 GET "/api/listings/$LISTING_ID/hold"
expect_status 404   # бронювання знято після успішного COMMIT купівлі

printf '\nЦілісність бізнес-стану збережено: втрата app1 не вплинула на сценарій.\n'
