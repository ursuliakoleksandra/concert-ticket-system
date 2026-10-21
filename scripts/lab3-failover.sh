#!/usr/bin/env bash
# ЛР3, симуляція аварії під навантаженням.
# k6 безперервно надсилає запити через балансувальник; на 15-й секунді app2 зупиняється,
# на 30-й — запускається знову. Очікуваний результат: 0 % помилок для клієнта.
#   scripts/lab3-failover.sh [stop|kill]   stop — SIGTERM (graceful), kill — SIGKILL (аварія)
source "$(dirname "$0")/lib.sh"

mode="${1:-stop}"
if [[ "$mode" != "stop" && "$mode" != "kill" ]]; then
    printf 'Використання: %s [stop|kill]\n' "$0" >&2
    exit 1
fi

bash scripts/lb-switch.sh round-robin >/dev/null

step "Навантаження 60 с, 20 VUs (у фоні)"
run_k6 "failover-$mode" 20 60s &
k6_pid=$!

sleep 15
step "t≈15 с: docker compose $mode app2"
docker compose "$mode" app2

sleep 15
step "t≈30 с: docker compose start app2"
docker compose start app2
wait_healthy app2
lb_reload

wait "$k6_pid"
step "Журнал Nginx"
logs=$(docker compose logs --no-log-prefix --since 120s nginx 2>&1) || true
printf 'Збої з%sєднання, зафіксовані пасивним health check:\n' "'"
printf '%s\n' "$logs" | grep -E 'while connecting to upstream|temporarily disabled|no live upstreams' | tail -n 5 || true
retried=$(printf '%s\n' "$logs" | grep -cE 'upstream=[^ ]+, ') || true
printf 'Запитів, повторених на іншому вузлі (proxy_next_upstream): %s\n' "${retried:-0}"
