#!/usr/bin/env bash
# ЛР8: усі експерименти (≈ 15–20 хв). Вивід кожного кроку зберігається в results/.
source "$(dirname "$0")/lib.sh"
mkdir -p results
bash scripts/lab8-stampede.sh     2>&1 | tee results/lab8-stampede.txt
bash scripts/lab8-avalanche.sh    2>&1 | tee results/lab8-avalanche.txt
bash scripts/lab8-penetration.sh  2>&1 | tee results/lab8-penetration.txt
printf '\nГотово. Результати ЛР8 — у results/lab8-*\n'
