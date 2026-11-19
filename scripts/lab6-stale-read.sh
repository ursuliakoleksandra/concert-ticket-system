#!/usr/bin/env bash
# ЛР6, 2.3–2.4: Replication Lag → Stale Read і захист Read-After-Write Consistency.
#   1. Репліка перезапускається із затримкою застосування WAL (recovery_min_apply_delay = DELAY)
#   2. Користувач 1 змінює назву події (запис на primary)
#   3. Інший клієнт одразу читає подію з репліки → стара назва (Stale Read)
#   4. Автор зміни читає ту саму подію → нова назва: Session Sticky Routing спрямовує його на primary
#   5. Критичні дані (статус оголошення) завжди читаються з primary (Explicit Primary Routing)
#   6. Після затримки репліка наздоганяє primary (Eventual Consistency)
# Запуск: bash scripts/lab6-stale-read.sh [DELAY — 5s] | tee results/lab6-stale-read.txt
source "$(dirname "$0")/lib.sh"
mkdir -p results

delay="${1:-5s}"
delay_seconds="${delay%s}"
trap 'REPLICA_APPLY_DELAY=0 docker compose up -d --no-deps postgres-replica >/dev/null 2>&1 || true' EXIT

step "Підготовка: подія, що вже є на репліці"
lb POST /api/events '{"title":"Stale-read: стара назва","venue":"Чернівці","startsAt":"2030-10-01T18:00:00Z","faceValue":500}'
expect_status 201
EVENT_ID=$(last_id)

replica_with_delay "$delay"
lb GET "/api/events/$EVENT_ID"
expect_route replica
expect_body_contains 'стара назва'

step "1. Користувач 1 змінює назву (PUT з X-User-Id: 1 → primary)"
lb PUT "/api/events/$EVENT_ID" \
    '{"title":"Stale-read: НОВА назва","venue":"Чернівці","startsAt":"2030-10-01T18:00:00Z","faceValue":500}' 1
expect_status 200
expect_route primary

step "2. Інший користувач (X-User-Id: 2) одразу читає подію → репліка, СТАРІ дані"
lb GET "/api/events/$EVENT_ID" "" 2
expect_status 200
expect_route replica
expect_body_contains 'стара назва'
printf '  → Stale Read: репліка ще не застосувала зміну (затримка %s)\n' "$delay"

step "3. Автор зміни (X-User-Id: 1) читає подію → primary, НОВІ дані (Read-Your-Own-Writes)"
lb GET "/api/events/$EVENT_ID" "" 1
expect_status 200
expect_route primary-sticky
expect_body_contains 'НОВА назва'

step "Затримка реплікації в цей момент"
api GET /api/admin/replication
printf '\n'

step "4. Критичні дані — завжди з primary (Explicit Primary Routing)"
lb GET /api/listings/1
expect_route primary-strict
lb GET /api/users/3/purchases
expect_route primary-strict

step "5. Через $delay репліка наздоганяє primary (Eventual Consistency)"
sleep $(( delay_seconds + 2 ))
lb GET "/api/events/$EVENT_ID" "" 2
expect_status 200
expect_route replica
expect_body_contains 'НОВА назва'

replica_with_delay 0
trap - EXIT
printf '\nStale Read відтворено, захист Read-After-Write Consistency працює.\n'
