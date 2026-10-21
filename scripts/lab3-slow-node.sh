#!/usr/bin/env bash
# ЛР3, експеримент з асиметричним навантаженням.
# app2 отримує синтетичну затримку; під однаковим навантаженням порівнюються Round Robin і Least Connections.
#   scripts/lab3-slow-node.sh [затримка, мс — за замовчуванням 300] [VUs — 30] [тривалість — 30s]
source "$(dirname "$0")/lib.sh"

delay="${1:-300}"
vus="${2:-30}"
duration="${3:-30s}"

step "Перестворення app2 із затримкою ${delay} мс"
APP2_DELAY_MS="$delay" docker compose up -d --no-deps app2
wait_healthy app2
lb_reload

step "Прогрів JVM (10 с)"
run_k6 warmup 10 10s >/dev/null

for profile in round-robin least-conn; do
    step "Алгоритм: $profile"
    bash scripts/lb-switch.sh "$profile"
    run_k6 "slow-node-$profile" "$vus" "$duration"
done

step "Відновлення app2 без затримки та профілю round-robin"
docker compose up -d --no-deps app2
wait_healthy app2
bash scripts/lb-switch.sh round-robin

printf '\nРезультати: results/slow-node-round-robin.txt, results/slow-node-least-conn.txt\n'
