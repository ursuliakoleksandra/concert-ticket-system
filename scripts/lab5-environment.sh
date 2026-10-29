#!/usr/bin/env bash
# ЛР5: Baseline Environment Specification — фіксує конфігурацію середовища на момент вимірювань.
# Результат: results/lab5-environment.txt
source "$(dirname "$0")/lib.sh"
mkdir -p results

limit_of() {   # limit_of CONTAINER → "N CPU, M МБ" з HostConfig
    docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$1" 2>/dev/null \
        | awk '{ cpu = ($1 > 0) ? sprintf("%.1f CPU", $1 / 1e9) : "без ліміту CPU";
                 mem = ($2 > 0) ? sprintf("%d МБ", $2 / 1048576) : "без ліміту RAM"; print cpu ", " mem }'
}
app_env() {    # app_env CONTAINER NAME → значення змінної оточення контейнера
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" | awk -F= -v k="$2" '$1 == k { print $2 }'
}
psql_value() {
    docker compose exec -T postgres psql -U resale -d ticket_resale -tAc "$1" | tr -d '\r '
}
redis_value() {
    docker compose exec -T redis redis-cli "$@" | tr -d '\r' | tail -n 1
}

{
    echo "=== Baseline Environment Specification ==="
    echo "Дата вимірювань:        $(date '+%Y-%m-%d %H:%M')"
    docker info --format 'Хост (Docker Engine):   {{.NCPU}} логічних CPU, {{.MemTotal}} байт RAM, {{.OperatingSystem}}, Docker {{.ServerVersion}}' \
        | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/ && $(i+1) == "байт") { $i = sprintf("%.1f", $i / 1073741824); $(i+1) = "ГБ" } print }'
    echo
    echo "--- System Under Test ---"
    for c in resale-app1 resale-app2 resale-app3; do
        printf '%-24s %s; CACHE_ENABLED=%s; RATE_LIMIT_PER_MINUTE=%s\n' "$c:" "$(limit_of "$c")" \
            "$(app_env "$c" CACHE_ENABLED)" "$(app_env "$c" RATE_LIMIT_PER_MINUTE)"
    done
    pool=$(app_env resale-app1 DB_POOL_SIZE)
    echo "HikariCP (на вузол):     maximum-pool-size = ${pool:-10}, connection-timeout = 5000 мс"
    echo "resale-postgres:         $(limit_of resale-postgres); max_connections = $(psql_value 'show max_connections'); shared_buffers = $(psql_value 'show shared_buffers')"
    echo "                         $(psql_value 'select version()' | cut -c1-40)"
    echo "resale-redis:            $(limit_of resale-redis); maxmemory-policy = $(redis_value CONFIG GET maxmemory-policy); ключів = $(redis_value DBSIZE)"
    echo "resale-lb (Nginx):       $(limit_of resale-lb); профіль: $(sed -n '2p' nginx/upstreams/active.conf | sed 's/^# //')"
    echo
    echo "--- Load Generator ---"
    k6_cpus=$(awk '/^  k6:/ { f = 1 } f && /cpus:/ { gsub(/"/, "", $2); print $2; exit }' docker-compose.yml)
    echo "k6 0.54 (контейнер):     ${k6_cpus:-?} CPU; мережа public → лише Nginx (http://nginx)"
    echo "Ізоляція:                k6 обмежено ${k6_cpus:-?} CPU, кожен вузол API — 1 CPU; генератор і SUT працюють на одному хості"
} | tee results/lab5-environment.txt
