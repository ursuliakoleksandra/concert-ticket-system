#!/usr/bin/env bash
# ЛР3: серія послідовних запитів через балансувальник і статистика розподілу за X-Instance-ID.
#   scripts/lab3-distribution.sh [кількість запитів, за замовчуванням 30] [шлях]
source "$(dirname "$0")/lib.sh"

count="${1:-30}"
path="${2:-/api/events}"

printf 'Профіль: %s\n' "$(sed -n '2p' nginx/upstreams/active.conf | sed 's/^# //')"
printf 'Запитів: %s → %s%s\n\n' "$count" "$LB_URL" "$path"

sequence=""
for _ in $(seq 1 "$count"); do
    instance=$(curl -s -o /dev/null -D - "$LB_URL$path" \
        | awk 'tolower($1) == "x-instance-id:" { print $2; exit }' | tr -d '\r') || true
    sequence+="${instance:-помилка} "
done

printf 'Послідовність: %s\n\n' "$sequence"
printf '%-12s %8s %8s\n' "Instance" "Запитів" "Частка"
printf '%s\n' $sequence | sort | uniq -c | while read -r hits instance; do
    printf '%-12s %8d %7d%%\n' "$instance" "$hits" $(( hits * 100 / count ))
done
