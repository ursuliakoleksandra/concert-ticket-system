#!/usr/bin/env bash
# ЛР5, Scaling Impact Analysis: однакове навантаження на пул з 1, 2 і 3 вузлів.
# Scalability Efficiency Factor: E(N) = RPS(N) / (N × RPS(1)); 1,0 — ідеальне лінійне масштабування.
#   scripts/lab5-scaling.sh [VUs — 100] [тривалість — 30s] [сценарій — scenario-a-read.js]
# Результат: results/lab5-scaling.md і results/lab5-scaling-{1,2,3}.*
source "$(dirname "$0")/lib.sh"

vus="${1:-100}"
duration="${2:-30s}"
scenario="${3:-scenario-a-read.js}"

for n in 1 2 3; do
    profile="scale-$n"
    [[ "$n" == 3 ]] && profile="round-robin"
    step "Scaling: $n вузл(и), $vus VUs × $duration ($scenario)"
    bash scripts/lb-switch.sh "$profile" >/dev/null
    sleep 2
    k6_run "$scenario" TAG="lab5-scaling-$n" STAGES="$vus" STAGE_DURATION="$duration" \
        WARMUP_DURATION=15s WARMUP_VUS=20 CONDITIONS="$n instance(s), $vus VUs"
done
bash scripts/lb-switch.sh round-robin >/dev/null

row() { tail -n 1 "results/lab5-scaling-$1.csv"; }   # vus,requests,rps,avg,p50,p95,p99,max,error_pct
rps1=$(row 1 | cut -d, -f3)
{
    echo "| Вузлів (N) | Throughput, RPS | p50, мс | p95, мс | p99, мс | Error Rate, % | Приріст RPS(N)/RPS(1) | Efficiency E(N) = RPS(N) / (N × RPS(1)) |"
    echo "|---|---|---|---|---|---|---|---|"
    for n in 1 2 3; do
        IFS=, read -r _ _ rps _ p50 p95 p99 _ err <<< "$(row "$n")"
        awk -v n="$n" -v r="$rps" -v r1="$rps1" -v p50="$p50" -v p95="$p95" -v p99="$p99" -v e="$err" \
            'BEGIN { printf "| %d | %s | %s | %s | %s | %s | %.2f× | %.2f |\n", n, r, p50, p95, p99, e, r / r1, r / (n * r1) }'
    done
    echo
    echo "Навантаження: $scenario, $vus VUs × $duration на кожну конфігурацію, Round Robin."
} | tee results/lab5-scaling.md
