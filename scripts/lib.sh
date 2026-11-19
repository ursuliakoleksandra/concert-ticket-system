#!/usr/bin/env bash
# Спільні функції для демонстраційних скриптів. Підключається через: source scripts/lib.sh

set -euo pipefail

# Git Bash (Windows) перетворює аргументи на кшталт /scripts/load.js на шляхи Windows — вимикаємо
export MSYS_NO_PATHCONV=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LB_URL="${LB_URL:-http://localhost:8080}"

LAST_STATUS=""
LAST_INSTANCE=""
LAST_CACHE=""
LAST_ROUTE=""
LAST_SHARD=""
LAST_BODY=""

# Розбір сирої HTTP-відповіді (curl -i) у LAST_STATUS / LAST_INSTANCE / LAST_BODY
_parse_response() {
    local raw="$1"
    LAST_STATUS=$(printf '%s\n' "$raw" | awk 'NR == 1 { print $2 }') || true
    LAST_INSTANCE=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-instance-id:" { print $2; exit }' | tr -d '\r') || true
    LAST_CACHE=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-cache:" { print $2; exit }' | tr -d '\r') || true
    LAST_ROUTE=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-db-route:" { print $2; exit }' | tr -d '\r') || true
    LAST_SHARD=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-shard:" { print $2; exit }' | tr -d '\r') || true
    LAST_BODY=$(printf '%s\n' "$raw" | awk 'body { print } /^\r?$/ { body = 1 }') || true
}

_print_last() {
    local label="$1"
    printf '%-34s → HTTP %s  [X-Instance-ID: %s]%s%s%s\n' "$label" "$LAST_STATUS" "${LAST_INSTANCE:--}" \
        "${LAST_CACHE:+  [X-Cache: $LAST_CACHE]}" "${LAST_ROUTE:+  [X-DB-Route: $LAST_ROUTE]}" \
        "${LAST_SHARD:+  [X-Shard: $LAST_SHARD]}"
    if [[ -n "$LAST_BODY" ]]; then
        printf '      %s\n' "$LAST_BODY"
    fi
}

# Запит безпосередньо до конкретного екземпляра (в обхід балансувальника).
# Виконується curl усередині контейнера екземпляра — вузли не мають опублікованих портів.
#   on_node NODE METHOD PATH [JSON_BODY] [USER_ID]
on_node() {
    local target="$1" method="$2" path="$3" body="${4:-}" user="${5:-}"
    local args=(-s -i -X "$method" "http://localhost:8080$path" -H 'Content-Type: application/json')
    [[ -n "$user" ]] && args+=(-H "X-User-Id: $user")
    [[ -n "$body" ]] && args+=(-d "$body")
    local raw
    raw=$(docker compose exec -T "$target" curl "${args[@]}") || raw="HTTP/1.1 000 NO-RESPONSE"
    _parse_response "$raw"
    _print_last "[$target] $method $path"
}

# Запит через балансувальник (єдина точка входу)
#   lb METHOD PATH [JSON_BODY] [USER_ID]
lb() {
    local method="$1" path="$2" body="${3:-}" user="${4:-}"
    local args=(-s -i -X "$method" "$LB_URL$path" -H 'Content-Type: application/json')
    [[ -n "$user" ]] && args+=(-H "X-User-Id: $user")
    [[ -n "$body" ]] && args+=(-d "$body")
    local raw
    raw=$(curl "${args[@]}") || raw="HTTP/1.1 000 NO-RESPONSE"
    _parse_response "$raw"
    _print_last "[LB] $method $path"
}

# Перше поле "id" з тіла останньої відповіді
last_id() {
    printf '%s' "$LAST_BODY" | grep -o '"id":[0-9]*' | head -n 1 | cut -d: -f2 || true
}

expect_status() {
    local expected="$1"
    if [[ "$LAST_STATUS" != "$expected" ]]; then
        printf '  ✗ Очікувався статус %s, отримано %s\n' "$expected" "$LAST_STATUS" >&2
        exit 1
    fi
    printf '  ✓ %s\n' "$expected"
}

# ЛР4: перевірка джерела даних відповіді (заголовок X-Cache)
expect_cache() {
    local expected="$1"
    if [[ "$LAST_CACHE" != "$expected" ]]; then
        printf '  ✗ Очікувався X-Cache: %s, отримано %s\n' "$expected" "${LAST_CACHE:--}" >&2
        exit 1
    fi
    printf '  ✓ X-Cache: %s\n' "$expected"
}

expect_body_not_contains() {
    local needle="$1"
    if [[ "$LAST_BODY" == *"$needle"* ]]; then
        printf '  ✗ У відповіді не повинно бути «%s»\n' "$needle" >&2
        exit 1
    fi
    printf '  ✓ не містить %s\n' "$needle"
}

expect_body_contains() {
    local needle="$1"
    if [[ "$LAST_BODY" != *"$needle"* ]]; then
        printf '  ✗ У відповіді немає «%s»\n' "$needle" >&2
        exit 1
    fi
    printf '  ✓ містить %s\n' "$needle"
}

# Очікування, доки екземпляр не стане healthy (до 120 с)
wait_healthy() {
    local target="$1"
    printf 'Очікування готовності %s' "$target"
    for _ in $(seq 1 60); do
        if docker compose exec -T "$target" curl -fs http://localhost:8080/health >/dev/null 2>&1; then
            printf ' — готовий\n'
            return 0
        fi
        printf '.'
        sleep 2
    done
    printf '\n%s не став healthy за 120 с\n' "$target" >&2
    exit 1
}

# Повторне читання конфігурації Nginx: заново визначаються IP-адреси вузлів
# (після перестворення/перезапуску контейнера адреса може змінитися)
lb_reload() {
    docker compose exec -T nginx nginx -s reload
}

step() {
    printf '\n=== %s ===\n' "$1"
}

# Нова подія + квиток для користувача 1 + оголошення від його імені на вказаному вузлі.
# Результат: змінні EVENT_ID, TICKET_ID, LISTING_ID
create_listing_on() {
    local target="$1" seat="${2:-Сектор A, місце $RANDOM}"
    on_node "$target" POST /api/events \
        '{"title":"Демо-подія ЛР2","venue":"Чернівці","startsAt":"2030-06-01T18:00:00Z","faceValue":500}'
    expect_status 201
    EVENT_ID=$(last_id)
    on_node "$target" POST /api/tickets "{\"eventId\":$EVENT_ID,\"ownerId\":1,\"seat\":\"$seat\"}"
    expect_status 201
    TICKET_ID=$(last_id)
    on_node "$target" POST /api/listings "{\"ticketId\":$TICKET_ID,\"price\":550}" 1
    expect_status 201
    LISTING_ID=$(last_id)
}

# Запуск k6 (генератор навантаження) у мережі public — звертається лише до балансувальника.
#   run_k6 TAG [VUS] [DURATION]                    — сценарій ЛР3 (load-tests/load.js)
run_k6() {
    local tag="$1" vus="${2:-30}" duration="${3:-30s}"
    k6_run load.js TAG="$tag" VUS="$vus" DURATION="$duration"
}

# Запуск довільного сценарію з load-tests/ зі змінними оточення (ЛР5)
#   k6_run scenario-a-read.js TAG=... STAGES=10,50 STAGE_DURATION=30s
k6_run() {
    local script="$1"; shift
    local envs=()
    for kv in "$@"; do envs+=(-e "$kv"); done
    mkdir -p results
    docker compose run --rm -T ${envs[@]+"${envs[@]}"} k6 run --quiet "/scripts/$script"
}

# Перестворення вузлів API з іншими змінними оточення (кеш, rate limit) — ЛР5.
#   apps_with_env CACHE_ENABLED=false RATE_LIMIT_PER_MINUTE=1000000
apps_with_env() {
    step "Перестворення app1..app3: $*"
    env "$@" docker compose up -d --no-deps app1 app2 app3
    wait_healthy app1
    wait_healthy app2
    wait_healthy app3
    lb_reload
}

# Кількість транзакцій PostgreSQL з моменту старту (commit + rollback) — міра навантаження на БД
db_transactions() {
    docker compose exec -T postgres psql -U resale -d ticket_resale -tAc \
        "select xact_commit + xact_rollback from pg_stat_database where datname = 'ticket_resale'" | tr -d '\r '
}

# Знімок CPU/пам'яті контейнерів (для звітів ЛР5)
stats_snapshot() {
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
}

# ---------------------------------------------------------------------------------------------
# ЛР6–ЛР8

# Значення простого поля JSON з тіла останньої відповіді: json_field title → "Нова назва"
json_field() {
    printf '%s' "$LAST_BODY" | grep -o "\"$1\":\(\"[^\"]*\"\|[^,}]*\)" | head -n 1 | cut -d: -f2- | tr -d '"' || true
}

expect_route() {
    local expected="$1"
    if [[ "$LAST_ROUTE" != "$expected" ]]; then
        printf '  ✗ Очікувався маршрут X-DB-Route: %s, отримано: %s\n' "$expected" "${LAST_ROUTE:-—}" >&2
        exit 1
    fi
    printf '  ✓ X-DB-Route: %s\n' "$expected"
}

expect_shard() {
    local expected="$1"
    if [[ "$LAST_SHARD" != "$expected" ]]; then
        printf '  ✗ Очікувався шард %s, отримано: %s\n' "$expected" "${LAST_SHARD:-—}" >&2
        exit 1
    fi
    printf '  ✓ X-Shard: %s\n' "$expected"
}

# SQL на конкретному вузлі СУБД: psql_on postgres-replica ticket_resale "select ..."
psql_on() {
    local service="$1" db="$2" sql="$3"
    docker compose exec -T "$service" psql -U resale -d "$db" -tAc "$sql" | tr -d '\r'
}

# Транзакцій з моменту старту на вузлі (commit + rollback): db_transactions_on postgres-replica
db_transactions_on() {
    local service="$1" db="${2:-ticket_resale}"
    psql_on "$service" "$db" "select xact_commit + xact_rollback from pg_stat_database where datname = '$db'" | tr -d ' '
}

# Очікування healthcheck будь-якого контейнера compose (СУБД, Redis) — до 120 с
wait_container_healthy() {
    local service="$1" state=""
    printf 'Очікування готовності %s' "$service"
    for _ in $(seq 1 60); do
        state=$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q "$service")" 2>/dev/null || true)
        if [[ "$state" == "healthy" ]]; then
            printf ' — готовий\n'
            return 0
        fi
        printf '.'
        sleep 2
    done
    printf '\n%s не став healthy за 120 с\n' "$service" >&2
    exit 1
}

# Після перезапуску репліки вузли API ще ~10 с вважають її недоступною (resale.db.retry-after) —
# чекаємо, поки читання знову підуть на репліку
wait_replica_route() {
    printf 'Очікування повернення читань на репліку'
    for _ in $(seq 1 30); do
        local route
        route=$(curl -s -o /dev/null -D - "$LB_URL/api/events?page=0&size=1" \
            | awk 'tolower($1) == "x-db-route:" { print $2; exit }' | tr -d '\r') || true
        if [[ "$route" == "replica" ]]; then
            printf ' — так\n'
            return 0
        fi
        printf '.'
        sleep 2
    done
    printf '\nЧитання не повернулися на репліку за 60 с\n' >&2
    exit 1
}

# Перезапуск репліки з іншою штучною затримкою застосування WAL: replica_with_delay 5s | 0
replica_with_delay() {
    step "Репліка з recovery_min_apply_delay = $1"
    REPLICA_APPLY_DELAY="$1" docker compose up -d --no-deps postgres-replica
    wait_container_healthy postgres-replica
    wait_replica_route
}

# Короткий запит до API через балансувальник без друку: api GET /api/admin/replication
api() {
    local method="$1" path="$2"
    curl -s -X "$method" "$LB_URL$path"
}

# SVG-діаграма зі згрупованими стовпчиками (без зовнішніх залежностей — awk).
#   bar_chart_svg OUT.svg "Заголовок" "Підпис осі Y" "Серія 1" "Серія 2" < рядки "категорія|значення1|значення2"
bar_chart_svg() {
    local out="$1" title="$2" ylabel="$3" s1="$4" s2="$5"
    awk -F'|' -v title="$title" -v ylabel="$ylabel" -v s1="$s1" -v s2="$s2" '
        { cat[NR] = $1; a[NR] = $2 + 0; b[NR] = $3 + 0; if ($2 > max) max = $2; if ($3 > max) max = $3 }
        END {
            n = NR; if (max <= 0) max = 1
            step = 10 ^ int(log(max) / log(10)); top = step
            while (top < max) top += step
            W = 720; H = 400; L = 70; R = 20; T = 60; B = 60
            pw = W - L - R; ph = H - T - B; gw = pw / n; bw = gw * 0.32
            printf "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" font-family=\"Arial, sans-serif\" font-size=\"12\">\n", W, H
            printf "<rect width=\"100%%\" height=\"100%%\" fill=\"#ffffff\"/>\n"
            printf "<text x=\"%d\" y=\"24\" font-size=\"16\" font-weight=\"bold\">%s</text>\n", L, title
            for (i = 0; i <= 4; i++) {
                y = T + ph - ph * i / 4; v = top * i / 4
                printf "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#e5e5e5\"/>\n", L, y, L + pw, y
                printf "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%g</text>\n", L - 6, y + 4, v
            }
            printf "<text transform=\"translate(16,%d) rotate(-90)\" text-anchor=\"middle\">%s</text>\n", T + ph / 2, ylabel
            for (i = 1; i <= n; i++) {
                x0 = L + gw * (i - 1) + gw * 0.16
                ha = ph * a[i] / top; hb = ph * b[i] / top
                printf "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#2a78d6\"/>\n", x0, T + ph - ha, bw, ha
                printf "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#eb6834\"/>\n", x0 + bw + 4, T + ph - hb, bw, hb
                printf "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-size=\"11\">%g</text>\n", x0 + bw / 2, T + ph - ha - 4, a[i]
                printf "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-size=\"11\">%g</text>\n", x0 + bw * 1.5 + 4, T + ph - hb - 4, b[i]
                printf "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>\n", L + gw * (i - 0.5), T + ph + 18, cat[i]
            }
            printf "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", L, T + ph, L + pw, T + ph
            printf "<rect x=\"%d\" y=\"36\" width=\"12\" height=\"12\" fill=\"#2a78d6\"/><text x=\"%d\" y=\"46\">%s</text>\n", L, L + 16, s1
            printf "<rect x=\"%d\" y=\"36\" width=\"12\" height=\"12\" fill=\"#eb6834\"/><text x=\"%d\" y=\"46\">%s</text>\n", L + 220, L + 236, s2
            print "</svg>"
        }' > "$out"
}
