#!/usr/bin/env bash
# ЛР6: усі демонстрації та вимірювання (≈ 12–15 хв). Вивід кожного кроку зберігається в results/.
source "$(dirname "$0")/lib.sh"
mkdir -p results
bash scripts/lab6-replication-demo.sh 2>&1 | tee results/lab6-replication-demo.txt
bash scripts/lab6-stale-read.sh 5s    2>&1 | tee results/lab6-stale-read.txt
bash scripts/lab6-failover.sh         2>&1 | tee results/lab6-failover.txt
bash scripts/lab6-read-scaling.sh     2>&1 | tee results/lab6-read-scaling.txt
printf '\nГотово. Результати ЛР6 — у results/lab6-*\n'
