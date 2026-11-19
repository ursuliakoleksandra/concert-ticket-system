#!/usr/bin/env bash
# ЛР6, 2.1: топологія Primary + Replica і автоматична реплікація.
#   1. Стан потокової реплікації на primary (pg_stat_replication) і режим репліки (pg_is_in_recovery)
#   2. Запис через API → рядок з'являється на репліці (запит напряму до репліки, в обхід застосунку)
#   3. Спроба запису напряму в репліку → відмова: репліка доступна лише для читання
#   4. Позиції журналу WAL і затримка реплікації (GET /api/admin/replication)
# Запуск: bash scripts/lab6-replication-demo.sh | tee results/lab6-replication-demo.txt
source "$(dirname "$0")/lib.sh"
mkdir -p results

step "1. Топологія: primary передає журнал WAL, репліка його відтворює"
printf 'primary — pg_stat_replication (клієнти потокової реплікації):\n'
psql_on postgres ticket_resale "select application_name, client_addr, state, sync_state, sent_lsn, replay_lsn from pg_stat_replication" \
    | sed 's/^/  /'
printf 'primary — pg_is_in_recovery: %s\n' "$(psql_on postgres ticket_resale 'select pg_is_in_recovery()')"
printf 'replica — pg_is_in_recovery: %s (t — режим Hot Standby, лише читання)\n' \
    "$(psql_on postgres-replica ticket_resale 'select pg_is_in_recovery()')"

step "2. Запис через API (мутація виконується на primary)"
title="Реплікація $(date +%H:%M:%S)"
lb POST /api/events "{\"title\":\"$title\",\"venue\":\"Чернівці\",\"startsAt\":\"2030-09-01T18:00:00Z\",\"faceValue\":500}"
expect_status 201
expect_route primary
EVENT_ID=$(last_id)

sleep 1
step "Той самий рядок напряму на репліці (SELECT у контейнері postgres-replica)"
on_replica=$(psql_on postgres-replica ticket_resale "select id || ' | ' || title from events where id = $EVENT_ID")
printf '  %s\n' "${on_replica:-<немає>}"
[[ "$on_replica" == *"$title"* ]] || { printf '  ✗ Рядка немає на репліці\n' >&2; exit 1; }
printf '  ✓ рядок, записаний на primary, є на репліці\n'

step "Читання каталогу через API — з репліки"
lb GET "/api/events/$EVENT_ID"
expect_status 200
expect_route replica
expect_body_contains "$title"

step "3. Запис напряму в репліку — заборонено"
if out=$(docker compose exec -T postgres-replica psql -U resale -d ticket_resale -c \
        "insert into users (email, full_name) values ('replica-write@test', 'X')" 2>&1); then
    printf '  ✗ Репліка прийняла запис\n' >&2
    exit 1
fi
printf '  %s\n' "$(printf '%s' "$out" | grep -m1 ERROR)"
printf '  ✓ репліка відхиляє мутації (read-only transaction)\n'

step "4. Стан реплікації: позиції WAL і затримка"
api GET /api/admin/replication
printf '\n\nРеплікацію перевірено: мутації primary автоматично відтворюються на репліці.\n'
