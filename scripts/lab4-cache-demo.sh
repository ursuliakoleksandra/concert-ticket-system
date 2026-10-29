#!/usr/bin/env bash
# ЛР4: демонстрація розподіленого кешу (Cache-Aside) для GET /api/events/{id}/listings.
#   1. Cache MISS → HIT, причому HIT обслуговують різні вузли (кеш спільний, у Redis)
#   2. Затримка: холодні (MISS) проти гарячих (HIT) запитів
#   3. Інвалідація при POST (нове оголошення), POST purchase (продаж) і DELETE (скасування)
#   4. Fallback: зупинка Redis → читання напряму з БД (X-Cache: BYPASS), система працює
# Запуск: bash scripts/lab4-cache-demo.sh | tee results/lab4-cache-demo.txt
source "$(dirname "$0")/lib.sh"

SAMPLES="${SAMPLES:-30}"
mkdir -p results

# --------------------------------------------------------------------------------------------
step "1. MISS → HIT: перший запит читає БД, наступні — Redis (з будь-якого вузла)"
# Унікальний параметр maxPrice гарантує новий ключ кешу, тобто чистий MISS
unique_max=$((100000 + RANDOM))
path="/api/events/1/listings?maxPrice=$unique_max"
lb GET "$path"; expect_status 200; expect_cache MISS
lb GET "$path"; expect_status 200; expect_cache HIT
lb GET "$path"; expect_status 200; expect_cache HIT

# --------------------------------------------------------------------------------------------
step "2. Затримка: $SAMPLES холодних (MISS) і $SAMPLES гарячих (HIT) запитів через Nginx"
# Вимір часу відповіді: curl -w %{time_total}; X-Cache перевіряється для кожного запиту
measure() {   # measure PATH → друкує "<час_с> <X-Cache>"
    curl -s -o /dev/null -D - -w 'TIME=%{time_total}\n' "$LB_URL$1" \
        | awk 'tolower($1) == "x-cache:" { c = $2 } /^TIME=/ { sub("TIME=", ""); t = $0 }
               END { gsub("\r", "", c); print t, c }'
}
summarize() { # summarize LABEL FILE → avg / p50 / p95 / max у мс
    sort -n "$2" | awk -v label="$1" '{ v[NR] = $1 * 1000; s += v[NR] }
        END { p50 = v[int((NR + 1) * 0.50)]; p95 = v[int(NR * 0.95 + 0.5)];
              printf "| %-22s | %6d | %8.1f | %8.1f | %8.1f | %8.1f |\n", label, NR, s / NR, p50, p95, v[NR] }'
}
tmp_cold=$(mktemp); tmp_hot=$(mktemp)
cold_misses=0; hot_hits=0
base=$((200000 + RANDOM))
for i in $(seq 1 "$SAMPLES"); do
    read -r t c < <(measure "/api/events/1/listings?maxPrice=$((base + i))")
    echo "$t" >> "$tmp_cold"; [[ "$c" == "MISS" ]] && cold_misses=$((cold_misses + 1))
done
hot_path="/api/events/1/listings?maxPrice=$((base + 1))"     # уже закешовано першим холодним запитом
for _ in $(seq 1 "$SAMPLES"); do
    read -r t c < <(measure "$hot_path")
    echo "$t" >> "$tmp_hot"; [[ "$c" == "HIT" ]] && hot_hits=$((hot_hits + 1))
done
{
    printf '| %-22s | %6s | %8s | %8s | %8s | %8s |\n' "Запит" "N" "avg, мс" "p50, мс" "p95, мс" "max, мс"
    printf '|%s|%s|%s|%s|%s|%s|\n' "------------------------" "--------" "----------" "----------" "----------" "----------"
    summarize "Cold (Cache MISS)" "$tmp_cold"
    summarize "Hot (Cache HIT)" "$tmp_hot"
    printf '\nX-Cache: холодних MISS %d/%d, гарячих HIT %d/%d\n' "$cold_misses" "$SAMPLES" "$hot_hits" "$SAMPLES"
} | tee results/lab4-latency.txt
rm -f "$tmp_cold" "$tmp_hot"

# --------------------------------------------------------------------------------------------
step "3. Інвалідація: кожна мутація оголошень події → наступне читання MISS зі свіжими даними"
lb POST /api/events '{"title":"Cache demo","venue":"Chernivtsi","startsAt":"2030-09-01T18:00:00Z","faceValue":500}'
expect_status 201
event_id=$(last_id)
listings="/api/events/$event_id/listings"

lb GET "$listings"; expect_status 200; expect_cache MISS
lb GET "$listings"; expect_status 200; expect_cache HIT

printf '\n-- POST /api/listings (нове оголошення) --\n'
lb POST /api/tickets "{\"eventId\":$event_id,\"ownerId\":1,\"seat\":\"Cache-A1\"}"; expect_status 201
ticket_a=$(last_id)
lb POST /api/listings "{\"ticketId\":$ticket_a,\"price\":550}" 1; expect_status 201
listing_a=$(last_id)
lb GET "$listings"; expect_status 200; expect_cache MISS; expect_body_contains "\"id\":$listing_a,\"ticketId\""
lb GET "$listings"; expect_status 200; expect_cache HIT;  expect_body_contains "\"id\":$listing_a,\"ticketId\""

printf '\n-- POST /api/listings/{id}/purchase (продаж) --\n'
lb POST "/api/listings/$listing_a/purchase" "" 3; expect_status 201
lb GET "$listings"; expect_status 200; expect_cache MISS; expect_body_not_contains "\"id\":$listing_a,\"ticketId\""

printf '\n-- DELETE /api/listings/{id} (скасування) --\n'
lb POST /api/tickets "{\"eventId\":$event_id,\"ownerId\":1,\"seat\":\"Cache-A2\"}"; expect_status 201
ticket_b=$(last_id)
lb POST /api/listings "{\"ticketId\":$ticket_b,\"price\":560}" 1; expect_status 201
listing_b=$(last_id)
lb GET "$listings"; expect_status 200; expect_cache MISS; expect_body_contains "\"id\":$listing_b,\"ticketId\""
lb DELETE "/api/listings/$listing_b" "" 1; expect_status 204
lb GET "$listings"; expect_status 200; expect_cache MISS; expect_body_not_contains "\"id\":$listing_b,\"ticketId\""
lb GET "$listings"; expect_status 200; expect_cache HIT

# --------------------------------------------------------------------------------------------
step "4. Fallback: аварійна зупинка Redis — система працює напряму з БД"
lb POST /api/events '{"title":"Fallback demo","venue":"Chernivtsi","startsAt":"2030-10-01T18:00:00Z","faceValue":500}'
expect_status 201
fb_event=$(last_id)
lb POST /api/tickets "{\"eventId\":$fb_event,\"ownerId\":1,\"seat\":\"Fallback-1\"}"; expect_status 201
fb_ticket=$(last_id)
lb POST /api/listings "{\"ticketId\":$fb_ticket,\"price\":550}" 1; expect_status 201
fb_listing=$(last_id)
lb POST /api/tickets "{\"eventId\":$fb_event,\"ownerId\":1,\"seat\":\"Fallback-2\"}"; expect_status 201
fb_ticket2=$(last_id)
lb POST /api/listings "{\"ticketId\":$fb_ticket2,\"price\":550}" 1; expect_status 201
fb_listing2=$(last_id)
fb_path="/api/events/$fb_event/listings"

# Redis запускається знову навіть якщо скрипт перерветься
trap 'docker compose start redis >/dev/null 2>&1 || true' EXIT
docker compose stop redis
docker compose ps --all --format '  {{.Name}}: {{.State}}' redis

lb GET "$fb_path"; expect_status 200; expect_cache BYPASS; expect_body_contains "\"id\":$fb_listing,\"ticketId\""
lb GET "$fb_path"; expect_status 200; expect_cache BYPASS
lb GET "$fb_path"; expect_status 200; expect_cache BYPASS
lb GET /health; expect_status 200; expect_body_contains '"status":"DEGRADED"'
printf '\n-- Купівля без Redis: перевірка бронювання пропускається, продаж гарантує PostgreSQL --\n'
lb POST "/api/listings/$fb_listing/purchase" "" 2; expect_status 201
printf '\n-- Бронювання без Redis недоступне (очікувано 503) --\n'
lb POST "/api/listings/$fb_listing2/hold" "" 2; expect_status 503

printf '\nЗапуск Redis і очікування перепідключення вузлів'
docker compose start redis >/dev/null
recovered=""
for _ in $(seq 1 45); do
    status=$(measure "$fb_path" | awk '{ print $2 }')
    if [[ "$status" == "MISS" || "$status" == "HIT" ]]; then recovered="yes"; break; fi
    printf '.'; sleep 2
done
printf '\n'
[[ -n "$recovered" ]] || { printf '  ✗ Кеш не відновився за 90 с\n' >&2; exit 1; }
# Продаж під час відмови не інвалідував кеш, але записів цієї події в кеші до відмови не було
lb GET "$fb_path"; expect_status 200; expect_body_not_contains "\"id\":$fb_listing,\"ticketId\""
lb GET "$fb_path"; expect_status 200; expect_cache HIT
lb GET /health; expect_status 200; expect_body_contains '"status":"UP"'

printf '\nУсі перевірки ЛР4 пройдено. Затримки збережено в results/lab4-latency.txt\n'
