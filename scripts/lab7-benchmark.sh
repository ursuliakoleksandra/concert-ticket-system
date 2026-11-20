#!/usr/bin/env bash
# ЛР7, 2.6: Single Database Node проти Sharded Cluster.
# Змішане навантаження на журнал операцій: 50 % запис, 35 % Shard-Local читання (історія користувача),
# 15 % Cross-Shard читання (звіт продажів події, Scatter-Gather). Перед кожним прогоном —
# однаковий набір даних (100 000 записів, 10 000 користувачів).
#   single  — SHARD_URLS лише shard-a: усі дані на одному вузлі (1 CPU)
#   sharded — shard-a + shard-b (по 1 CPU), Consistent Hashing
#   scripts/lab7-benchmark.sh [ступені — 25,50,100,200] [тривалість — 30s]
# Результат: results/lab7-benchmark.md, results/lab7-benchmark-{single,sharded}.*
source "$(dirname "$0")/lib.sh"
mkdir -p results

stages="${1:-25,50,100,200}"
duration="${2:-30s}"
single="shard-a=jdbc:postgresql://shard-a:5432/ledger"

for topology in single sharded; do
    if [[ "$topology" == "single" ]]; then
        apps_with_env SHARD_URLS="$single" SHARD_STRATEGY=consistent RATE_LIMIT_PER_MINUTE=1000000
    else
        apps_with_env SHARD_STRATEGY=consistent RATE_LIMIT_PER_MINUTE=1000000
    fi
    step "$topology: підготовка даних"
    api POST "/api/admin/ledger/generate?count=100000&users=10000&pattern=uniform"
    printf '\n'
    step "$topology: змішане навантаження, ступені $stages × $duration"
    k6_run lab7-benchmark.js TAG="lab7-benchmark-$topology" STAGES="$stages" STAGE_DURATION="$duration" \
        CONDITIONS="$topology topology"
done

{
    echo "| Топологія | VUs | Throughput, RPS | Запис p95 / p99, мс | Shard-Local p95 / p99, мс | Cross-Shard p95 / p99, мс | Error Rate, % |"
    echo "|---|---|---|---|---|---|---|"
    for topology in single sharded; do
        label="Single Node"; [[ "$topology" == "sharded" ]] && label="Sharded (2 шарди)"
        tail -n +2 "results/lab7-benchmark-$topology.csv" | while IFS=, read -r vus rps w95 w99 l95 l99 f95 f99 err; do
            echo "| $label | $vus | $rps | $w95 / $w99 | $l95 / $l99 | $f95 / $f99 | $err |"
        done
    done
    echo
    echo "50 % POST /api/ledger/entries, 35 % GET /api/users/{id}/ledger, 15 % GET /api/reports/events/{id}/sales; кожен шард — 1 CPU."
} | tee results/lab7-benchmark.md

apps_with_env SHARD_STRATEGY=consistent RATE_LIMIT_PER_MINUTE=20
