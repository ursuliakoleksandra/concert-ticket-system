#!/usr/bin/env bash
# ЛР7: усі демонстрації та вимірювання (≈ 20–25 хв). Вивід кожного кроку зберігається в results/.
source "$(dirname "$0")/lib.sh"
mkdir -p results
bash scripts/lab7-routing-demo.sh  2>&1 | tee results/lab7-routing-demo.txt
bash scripts/lab7-distribution.sh  2>&1 | tee results/lab7-distribution.txt
bash scripts/lab7-hot-shard.sh     2>&1 | tee results/lab7-hot-shard.txt
bash scripts/lab7-benchmark.sh     2>&1 | tee results/lab7-benchmark.txt
printf '\nГотово. Результати ЛР7 — у results/lab7-*\n'
