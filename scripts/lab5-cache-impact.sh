#!/usr/bin/env bash
# ЛР5, Caching Impact Analysis: Scenario A (Read-Intensive) з вимкненим кешем і з прогрітим кешем.
# Окрім latency/throughput вимірюється навантаження на СУБД: кількість транзакцій PostgreSQL
# (pg_stat_database: xact_commit + xact_rollback) і CPU контейнера resale-postgres.
#   scripts/lab5-cache-impact.sh [VUs — 50] [тривалість — 30s]
# Результат: results/lab5-cache-impact.md і results/lab5-cache-{disabled,enabled}.*
source "$(dirname "$0")/lib.sh"

vus="${1:-50}"
duration="${2:-30s}"
rate_limit="${RATE_LIMIT_PER_MINUTE:-1000000}"

declare_results=()
for mode in disabled enabled; do
    flag=false; [[ "$mode" == "enabled" ]] && flag=true
    apps_with_env CACHE_ENABLED="$flag" RATE_LIMIT_PER_MINUTE="$rate_limit"

    step "Cache $mode: Scenario A, $vus VUs × $duration (розігрів 15 с прогріває кеш)"
    tx_before=$(db_transactions)
    started=$(date +%s)
    # Знімок CPU посередині основного ступеня (розігрів 15 с + пауза 5 с + половина ступеня)
    ( sleep 35; stats_snapshot > "results/lab5-cache-$mode-stats.txt" ) &
    k6_run scenario-a-read.js TAG="lab5-cache-$mode" STAGES="$vus" STAGE_DURATION="$duration" \
        WARMUP_DURATION=15s WARMUP_VUS=20 CONDITIONS="cache $mode, 3 instances, $vus VUs"
    wait
    elapsed=$(( $(date +%s) - started ))
    tx_after=$(db_transactions)
    tx_rate=$(awk -v a="$tx_before" -v b="$tx_after" -v t="$elapsed" 'BEGIN { printf "%.0f", (b - a) / t }')
    pg_cpu=$(awk '$1 == "resale-postgres" { print $2 }' "results/lab5-cache-$mode-stats.txt")
    hit=$(grep -o 'Cache hit ratio[^:]*: [0-9.]*' "results/lab5-cache-$mode.md" | awk '{ print $NF }')
    declare_results+=("$mode|$(tail -n 1 "results/lab5-cache-$mode.csv")|$tx_rate|${pg_cpu:-?}|${hit:-0}")
done

{
    echo "| Режим | Throughput, RPS | Avg, мс | p50, мс | p95, мс | p99, мс | Error Rate, % | Cache hit, % | Транзакцій БД/с | CPU PostgreSQL |"
    echo "|---|---|---|---|---|---|---|---|---|---|"
    for r in "${declare_results[@]}"; do
        IFS='|' read -r mode csv tx cpu hit <<< "$r"
        IFS=, read -r _ _ rps avg p50 p95 p99 _ err <<< "$csv"
        label="Cache Disabled"; [[ "$mode" == "enabled" ]] && label="Cache Enabled (warm)"
        echo "| $label | $rps | $avg | $p50 | $p95 | $p99 | $err | $hit | $tx | $cpu |"
    done
    echo
    echo "Навантаження: Scenario A, $vus VUs × $duration, 3 вузли, Round Robin. Транзакції БД — за весь прогін з розігрівом."
} | tee results/lab5-cache-impact.md

apps_with_env CACHE_ENABLED=true RATE_LIMIT_PER_MINUTE="$rate_limit"
