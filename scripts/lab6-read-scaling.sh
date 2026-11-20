#!/usr/bin/env bash
# ЛР6, 2.6: Read Scaling Impact — Single Primary проти Primary + Replica.
# Scenario A (Read-Intensive) з ЛР5 з ВИМКНЕНИМ кешем, щоб кожне читання доходило до СУБД.
# Обидві конфігурації в однакових умовах: 3 вузли API, по 1 CPU на primary і replica.
#   scripts/lab6-read-scaling.sh [ступені VUs — 25,50,100,200] [тривалість ступеня — 30s]
# Результат: results/lab6-read-scaling.md, results/lab6-{single,replica}.* і знімки docker stats
source "$(dirname "$0")/lib.sh"
mkdir -p results

stages="${1:-25,50,100,200}"
duration="${2:-30s}"
rate_limit=1000000
IFS=, read -r -a stage_list <<< "$stages"
dur_s="${duration%s}"
# Знімок CPU посередині передостаннього ступеня: розігрів 20 с + пауза 5 с + (ступені до нього)
snapshot_at=$(( 25 + (${#stage_list[@]} - 2) * (dur_s + 5) + dur_s / 2 ))

rows=()
for mode in single replica; do
    enabled=false; [[ "$mode" == "replica" ]] && enabled=true
    apps_with_env CACHE_ENABLED=false RATE_LIMIT_PER_MINUTE="$rate_limit" READ_REPLICA_ENABLED="$enabled"

    step "Topology: $mode (READ_REPLICA_ENABLED=$enabled), stages $stages × $duration"
    p_before=$(db_transactions_on postgres)
    r_before=$(db_transactions_on postgres-replica)
    started=$(date +%s)
    ( sleep "$snapshot_at"; stats_snapshot > "results/lab6-$mode-stats.txt" ) &
    k6_run scenario-a-read.js TAG="lab6-$mode" STAGES="$stages" STAGE_DURATION="$duration" \
        CONDITIONS="$mode topology, cache disabled, 3 instances"
    wait
    elapsed=$(( $(date +%s) - started ))
    p_tx=$(( ($(db_transactions_on postgres) - p_before) / elapsed ))
    r_tx=$(( ($(db_transactions_on postgres-replica) - r_before) / elapsed ))
    p_cpu=$(awk '$1 == "resale-postgres" { print $2 }' "results/lab6-$mode-stats.txt")
    r_cpu=$(awk '$1 == "resale-postgres-replica" { print $2 }' "results/lab6-$mode-stats.txt")
    rows+=("$mode|$p_tx|$r_tx|${p_cpu:-?}|${r_cpu:-?}")
done

label() { [[ "$1" == "single" ]] && echo "Single Primary" || echo "Primary + Replica"; }
{
    echo "#### Throughput і затримка за ступенями"
    echo
    echo "| Топологія | VUs | Throughput, RPS | p50, мс | p95, мс | p99, мс | Error Rate, % |"
    echo "|---|---|---|---|---|---|---|"
    for mode in single replica; do
        tail -n +2 "results/lab6-$mode.csv" | while IFS=, read -r vus _ rps _ p50 p95 p99 _ err; do
            echo "| $(label "$mode") | $vus | $rps | $p50 | $p95 | $p99 | $err |"
        done
    done
    echo
    echo "#### Навантаження на вузли СУБД"
    echo
    echo "| Топологія | Транзакцій/с primary | Транзакцій/с replica | CPU primary | CPU replica |"
    echo "|---|---|---|---|---|"
    for r in "${rows[@]}"; do
        IFS='|' read -r mode p_tx r_tx p_cpu r_cpu <<< "$r"
        echo "| $(label "$mode") | $p_tx | $r_tx | $p_cpu | $r_cpu |"
    done
    echo
    echo "Scenario A, кеш вимкнено, ступені $stages × $duration; CPU — знімок docker stats на передостанньому ступені."
} | tee results/lab6-read-scaling.md

apps_with_env CACHE_ENABLED=true RATE_LIMIT_PER_MINUTE=20 READ_REPLICA_ENABLED=true
