#!/usr/bin/env bash
# ЛР6, 2.5: відмова вузлів СУБД і Graceful Degradation.
#   A. docker compose stop postgres-replica → читання автоматично йдуть на primary (Primary Fallback)
#   B. docker compose stop postgres         → запис блокується (режим лише читання),
#                                             читання каталогу продовжуються з репліки
# Запуск: bash scripts/lab6-failover.sh | tee results/lab6-failover.txt
source "$(dirname "$0")/lib.sh"
mkdir -p results
trap 'docker compose start postgres postgres-replica >/dev/null 2>&1 || true' EXIT

health() {
    lb GET /health
    printf '      → %s\n' "$(json_field status)"
}

step "Вихідний стан"
health
expect_body_contains '"status":"UP"'
lb GET /api/events?size=1
expect_route replica

# --------------------------------------------------------------------------------------------
step "A. Втрата репліки: docker compose stop postgres-replica"
docker compose stop postgres-replica
for i in 1 2 3; do
    lb GET "/api/events?size=1&n=$i"
    expect_status 200
    expect_route primary-fallback
done
printf '  → читання каталогу працюють: маршрутизатор перейшов на primary без помилок для клієнта\n'
lb POST /api/events '{"title":"Під час відмови репліки","venue":"Чернівці","startsAt":"2030-11-01T18:00:00Z","faceValue":500}'
expect_status 201
health
expect_body_contains '"status":"DEGRADED"'
expect_body_contains '"replica":"DOWN"'

step "Відновлення репліки"
docker compose start postgres-replica
wait_container_healthy postgres-replica
wait_replica_route
health
expect_body_contains '"status":"UP"'

# --------------------------------------------------------------------------------------------
step "B. Втрата primary: docker compose stop postgres"
docker compose stop postgres
sleep 2

printf '\n-- Запис заблоковано (контрольована помилка, а не падіння сервісу) --\n'
lb POST /api/events '{"title":"Під час відмови primary","venue":"Чернівці","startsAt":"2030-11-01T18:00:00Z","faceValue":500}'
expect_status 503
expect_body_contains 'Режим лише читання'

printf '\n-- Каталог (Eventual Consistency) — з репліки --\n'
for i in 1 2 3; do
    lb GET "/api/events?size=1&n=$i"
    expect_status 200
    expect_route replica
done
lb GET /api/events/1
expect_status 200
expect_route replica

printf '\n-- Критичні дані (Strong Consistency) потребують primary — 503 --\n'
lb GET /api/users/3/purchases
expect_status 503

health
expect_body_contains '"status":"READ_ONLY"'

step "Відновлення primary"
docker compose start postgres
wait_container_healthy postgres
# Вузли API пробують primary знову після resale.db.retry-after (10 с)
sleep 12
lb POST /api/events '{"title":"Після відновлення primary","venue":"Чернівці","startsAt":"2030-11-01T18:00:00Z","faceValue":500}'
expect_status 201
expect_route primary
wait_replica_route
health
expect_body_contains '"status":"UP"'

trap - EXIT
printf '\nВідмова кожного вузла СУБД обробляється без падіння backend-сервісів.\n'
