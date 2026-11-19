#!/usr/bin/env bash
# ЛР8, 2.2 і 2.4: Cache Stampede / Thundering Herd — без захисту і з захистом.
#   none — кожен запит, що не знайшов ключ, сам іде в СУБД (сотні однакових агрегацій одночасно)
#   lock — single-flight у межах вузла + розподілене блокування Redis між вузлами: до СУБД — один запит
# Перед кожним режимом вузли API перестворюються з STATS_PROTECTION=<режим>.
#   scripts/lab8-stampede.sh [VUs — 500] [тривалість — 15s]
# Результат: results/lab8-stampede.md, results/lab8-stampede-{none,lock}.*, results/lab8-stampede-{db-loads,p99}.svg
source "$(dirname "$0")/lib.sh"
mkdir -p results

vus="${1:-500}"
duration="${2:-15s}"
rows=()
loads_by_mode=()
p99_by_mode=()
for mode in none lock; do
    apps_with_env STATS_PROTECTION="$mode" RATE_LIMIT_PER_MINUTE=1000000
    step "Stampede: STATS_PROTECTION=$mode, $vus VUs одночасно на один холодний ключ"
    tx_before=$(db_transactions_on postgres-replica)
    # Знімок CPU на 2-й секунді атаки (розігрів 10 с + пауза 5 с)
    ( sleep 17; stats_snapshot > "results/lab8-stampede-$mode-stats.txt" ) &
    k6_run lab8-stampede.js TAG="lab8-stampede-$mode" VUS="$vus" DURATION="$duration" \
        CONDITIONS="STATS_PROTECTION=$mode, агрегація ~200 мс у СУБД"
    wait
    tx=$(( $(db_transactions_on postgres-replica) - tx_before ))
    cpu=$(awk '$1 == "resale-postgres-replica" { print $2 }' "results/lab8-stampede-$mode-stats.txt")
    IFS=, read -r loads rps p95 p99 err < "results/lab8-stampede-$mode.row"
    label="Unprotected (Stampede)"; [[ "$mode" == "lock" ]] && label="Protected (Single-Flight + Lock)"
    rows+=("| $label | $loads | $tx | ${cpu:-?} | $rps | $p95 | $p99 | $err |")
    loads_by_mode+=("$loads")
    p99_by_mode+=("$p99")
done

{
    echo "| Режим | Звернень до СУБД (агрегація) | Транзакцій репліки за прогін | CPU репліки | Throughput, RPS | p95, мс | p99, мс | Error Rate, % |"
    echo "|---|---|---|---|---|---|---|---|"
    printf '%s\n' "${rows[@]}"
    echo
    echo "GET /api/events/1/stats, $vus VUs одночасно × $duration, ключ попередньо видалено; статистика читається з репліки (ЛР6)."
    echo "Транзакції репліки включають розігрів (20 VUs × 10 с на іншу подію)."
} | tee results/lab8-stampede.md

printf 'Звернень до СУБД|%s|%s\n' "${loads_by_mode[0]}" "${loads_by_mode[1]}" | bar_chart_svg results/lab8-stampede-db-loads.svg \
    "Cache Stampede: звернення до СУБД" "Звернень до СУБД" "Без захисту" "Single-Flight + Lock"
printf 'p99, мс|%s|%s\n' "${p99_by_mode[0]}" "${p99_by_mode[1]}" | bar_chart_svg results/lab8-stampede-p99.svg \
    "Cache Stampede: хвіст затримки" "p99, мс" "Без захисту" "Single-Flight + Lock"

apps_with_env STATS_PROTECTION=lock RATE_LIMIT_PER_MINUTE=20
