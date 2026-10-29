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
LAST_BODY=""

# Розбір сирої HTTP-відповіді (curl -i) у LAST_STATUS / LAST_INSTANCE / LAST_BODY
_parse_response() {
    local raw="$1"
    LAST_STATUS=$(printf '%s\n' "$raw" | awk 'NR == 1 { print $2 }') || true
    LAST_INSTANCE=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-instance-id:" { print $2; exit }' | tr -d '\r') || true
    LAST_CACHE=$(printf '%s\n' "$raw" | awk 'tolower($1) == "x-cache:" { print $2; exit }' | tr -d '\r') || true
    LAST_BODY=$(printf '%s\n' "$raw" | awk 'body { print } /^\r?$/ { body = 1 }') || true
}

_print_last() {
    local label="$1"
    printf '%-34s → HTTP %s  [X-Instance-ID: %s]%s\n' "$label" "$LAST_STATUS" "${LAST_INSTANCE:--}" \
        "${LAST_CACHE:+  [X-Cache: $LAST_CACHE]}"
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
