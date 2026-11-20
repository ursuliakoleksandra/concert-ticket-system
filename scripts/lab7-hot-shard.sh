#!/usr/bin/env bash
# ЛР7, 2.3: Hot Shard Scenario і оптимізація стратегії.
# Однакове навантаження на запис (ключі лише парні — профіль even) для двох функцій маршрутизації:
#   modulo     — user_id % 2: усі парні ключі на одному шарді → Hot Shard
#   consistent — hash(user_id) на кільці з віртуальними вузлами → навантаження вирівнюється
# Фіксуються частка записів кожного шарду, затримка запису і CPU контейнерів шардів.
#   scripts/lab7-hot-shard.sh [VUs — 100] [тривалість — 30s]
# Результат: results/lab7-hot-shard.md, results/lab7-hot-shard-{modulo,consistent}.*, results/lab7-hot-shard.svg
source "$(dirname "$0")/lib.sh"
mkdir -p results

vus="${1:-100}"
duration="${2:-30s}"
dur_s="${duration%s}"
rows=()
chart=()
for strategy in modulo consistent; do
    apps_with_env SHARD_STRATEGY="$strategy" RATE_LIMIT_PER_MINUTE=1000000
    api POST /api/admin/ledger/reset-counters >/dev/null
    step "Hot Shard: $strategy, профіль even, $vus VUs × $duration"
    ( sleep $(( 10 + 5 + dur_s / 2 )); stats_snapshot > "results/lab7-hot-shard-$strategy-stats.txt" ) &
    k6_run lab7-hot-shard.js TAG="lab7-hot-shard-$strategy" PATTERN=even VUS="$vus" DURATION="$duration" \
        CONDITIONS="SHARD_STRATEGY=$strategy, ключі лише парні"
    wait
    dist=$(api GET /api/admin/ledger/distribution)
    wa=$(printf '%s' "$dist" | grep -o '"shard":"shard-a"[^}]*"writesPercent":[0-9.]*' | sed 's/.*"writesPercent"://')
    wb=$(printf '%s' "$dist" | grep -o '"shard":"shard-b"[^}]*"writesPercent":[0-9.]*' | sed 's/.*"writesPercent"://')
    ca=$(awk '$1 == "resale-shard-a" { print $2 }' "results/lab7-hot-shard-$strategy-stats.txt")
    cb=$(awk '$1 == "resale-shard-b" { print $2 }' "results/lab7-hot-shard-$strategy-stats.txt")
    ma=$(awk '$1 == "resale-shard-a" { print $3 }' "results/lab7-hot-shard-$strategy-stats.txt")
    mb=$(awk '$1 == "resale-shard-b" { print $3 }' "results/lab7-hot-shard-$strategy-stats.txt")
    IFS=, read -r rps p95 p99 err < "results/lab7-hot-shard-$strategy.row"
    rows+=("| $strategy | ${wa:-0} | ${wb:-0} | ${ca:-?} | ${cb:-?} | ${ma:-?} | ${mb:-?} | $rps | $p95 | $p99 | $err |")
    chart+=("$strategy|${wa:-0}|${wb:-0}")
done

{
    echo "| Функція | Записів на Shard A, % | Записів на Shard B, % | CPU Shard A | CPU Shard B | RAM Shard A | RAM Shard B | Throughput, RPS | p95, мс | p99, мс | Error Rate, % |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|"
    printf '%s\n' "${rows[@]}"
    echo
    echo "POST /api/ledger/entries, user_id лише парні (1..20000), $vus VUs × $duration; CPU і RAM — знімок docker stats посередині прогону, кожен шард обмежено 1 CPU."
} | tee results/lab7-hot-shard.md

printf '%s\n' "${chart[@]}" | bar_chart_svg results/lab7-hot-shard.svg \
    "Hot Shard: частка операцій запису до і після оптимізації" "Частка записів, %" "Shard A" "Shard B"

apps_with_env SHARD_STRATEGY=consistent RATE_LIMIT_PER_MINUTE=20
