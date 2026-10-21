#!/usr/bin/env bash
# ЛР3, scale-out експеримент: однакове навантаження на пул з 1, 2 і 3 вузлів.
#   scripts/lab3-scale.sh [VUs — 60] [тривалість — 30s]
source "$(dirname "$0")/lib.sh"

vus="${1:-60}"
duration="${2:-30s}"

step "Прогрів JVM усіх вузлів (15 с)"
bash scripts/lb-switch.sh round-robin >/dev/null
run_k6 warmup 20 15s >/dev/null

for n in 1 2 3; do
    profile="scale-$n"
    [[ "$n" == 3 ]] && profile="round-robin"
    step "Пул з $n вузл(ів)"
    bash scripts/lb-switch.sh "$profile" >/dev/null
    sleep 2
    run_k6 "scale-$n" "$vus" "$duration"
done

bash scripts/lb-switch.sh round-robin >/dev/null

step "Зведення"
printf '%-8s %10s %10s %10s %10s\n' "Вузлів" "RPS" "p95, мс" "p99, мс" "Помилки,%"
for n in 1 2 3; do
    read -r rps p95 p99 failed < "results/scale-$n.row"
    printf '%-8s %10s %10s %10s %10s\n' "$n" "$rps" "$p95" "$p99" "$failed"
done
