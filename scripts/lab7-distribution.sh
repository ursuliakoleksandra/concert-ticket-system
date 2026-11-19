#!/usr/bin/env bash
# ЛР7, 2.3: Dataset Distribution Baseline — розподіл записів між шардами для трьох функцій
# маршрутизації і трьох профілів ключів.
#   uniform — user_id рівномірно 1..USERS (типовий випадок)
#   even    — лише парні user_id (ID з кроком 2: низька ентропія молодшого біта)
#   whale   — 50 % записів одного користувача (великий тенант, Hot Key)
# Для кожної функції вузли API перестворюються з SHARD_STRATEGY=...
#   scripts/lab7-distribution.sh [записів — 100000] [користувачів — 10000]
# Результат: results/lab7-distribution.md, results/lab7-distribution.svg
source "$(dirname "$0")/lib.sh"
mkdir -p results

count="${1:-100000}"
users="${2:-10000}"
table=()
chart=()
for strategy in modulo hash consistent; do
    apps_with_env SHARD_STRATEGY="$strategy"
    for pattern in uniform even whale; do
        step "$strategy / $pattern: генерація $count записів"
        out=$(api POST "/api/admin/ledger/generate?count=$count&users=$users&pattern=$pattern")
        printf '%s\n' "$out"
        a=$(printf '%s' "$out" | grep -o '"shard":"shard-a","records":[0-9]*,"recordsPercent":[0-9.]*' | sed 's/.*"recordsPercent"://')
        b=$(printf '%s' "$out" | grep -o '"shard":"shard-b","records":[0-9]*,"recordsPercent":[0-9.]*' | sed 's/.*"recordsPercent"://')
        ra=$(printf '%s' "$out" | grep -o '"shard":"shard-a","records":[0-9]*' | sed 's/.*"records"://')
        rb=$(printf '%s' "$out" | grep -o '"shard":"shard-b","records":[0-9]*' | sed 's/.*"records"://')
        wa=$(printf '%s' "$out" | grep -o '"shard":"shard-a"[^}]*"writesPercent":[0-9.]*' | sed 's/.*"writesPercent"://')
        wb=$(printf '%s' "$out" | grep -o '"shard":"shard-b"[^}]*"writesPercent":[0-9.]*' | sed 's/.*"writesPercent"://')
        table+=("| $strategy | $pattern | $ra | ${a:-0} | ${wa:-0} | $rb | ${b:-0} | ${wb:-0} |")
        chart+=("$strategy / $pattern|${a:-0}|${b:-0}")
    done
done

{
    echo "| Функція маршрутизації | Профіль ключів | Shard A, записів | Shard A, частка даних % | Shard A, частка Write Operations % | Shard B, записів | Shard B, частка даних % | Shard B, частка Write Operations % |"
    echo "|---|---|---|---|---|---|---|---|"
    printf '%s\n' "${table[@]}"
    echo
    echo "$count записів, $users користувачів; частка Write Operations — лічильники записів кожного шарду в Redis (resale:shard:writes:*), скинуті перед генерацією."
} | tee results/lab7-distribution.md

printf '%s\n' "${chart[@]}" | bar_chart_svg results/lab7-distribution.svg \
    "Розподіл записів між шардами" "Частка записів, %" "Shard A" "Shard B"
printf '\nГрафік: results/lab7-distribution.svg\n'

apps_with_env SHARD_STRATEGY=consistent
api POST "/api/admin/ledger/generate?count=$count&users=$users&pattern=uniform" >/dev/null
