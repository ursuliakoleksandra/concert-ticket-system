#!/usr/bin/env bash
# ЛР7: детермінована маршрутизація за ключем шардингу (user_id) і модель решардингу.
#   1. Топологія: шарди та функція маршрутизації (GET /api/admin/sharding)
#   2. Нові записи кількох користувачів → кожен потрапляє на свій шард (заголовок X-Shard)
#   3. Перевірка фізичного розміщення: SELECT напряму на shard-a і shard-b
#   4. Повторний запис того ж користувача — той самий шард (детермінованість)
#   5. Shard-Local запит проти Cross-Shard (Scatter-Gather): час виконання
#   6. Модель решардингу: частка ключів, що переносяться при 2 → 3, 3 → 4, 10 → 11 шардах
# Запуск: bash scripts/lab7-routing-demo.sh | tee results/lab7-routing-demo.txt
source "$(dirname "$0")/lib.sh"
mkdir -p results

step "1. Топологія"
api GET /api/admin/sharding
printf '\n'

step "2. Записи користувачів 101–106 → шард обирає Shard Router"
declare -A shard_of
for user in 101 102 103 104 105 106; do
    lb POST /api/ledger/entries "{\"userId\":$user,\"eventId\":1,\"type\":\"BUY\",\"amount\":650}"
    expect_status 201
    shard_of[$user]="$LAST_SHARD"
done

step "3. Фізичне розміщення: рядки користувача лише на його шарді"
for user in 101 102 103 104 105 106; do
    a=$(psql_on shard-a ledger "select count(*) from ledger_entries where user_id = $user")
    b=$(psql_on shard-b ledger "select count(*) from ledger_entries where user_id = $user")
    printf '  user %s → %-8s  shard-a: %s рядк., shard-b: %s рядк.\n' "$user" "${shard_of[$user]}" "$a" "$b"
    if [[ "${shard_of[$user]}" == "shard-a" && "$b" != "0" ]] || [[ "${shard_of[$user]}" == "shard-b" && "$a" != "0" ]]; then
        printf '  ✗ Рядок знайдено не на тому шарді\n' >&2
        exit 1
    fi
done
printf '  ✓ кожен рядок лежить лише на шарді, обраному маршрутизатором\n'

step "4. Детермінованість: той самий ключ на різних вузлах API → той самий шард"
for _ in 1 2 3; do
    lb GET /api/users/101/ledger?limit=1
    expect_shard "${shard_of[101]}"
done
api GET /api/admin/sharding/route/101
printf '\n'

step "5. Shard-Local проти Cross-Shard (Scatter-Gather)"
lb GET /api/users/101/ledger?limit=20
printf '  Shard-Local: 1 шард (%s)\n' "$LAST_SHARD"
for mode in parallel sequential; do
    lb GET "/api/reports/events/1/sales?mode=$mode"
    printf '  Cross-Shard (%s): шарди %s, загальний час %s мс\n' "$mode" "$LAST_SHARD" "$(json_field totalMs)"
done

step "6. Модель решардингу (100 000 ключів)"
for change in "2 3" "3 4" "10 11"; do
    read -r from to <<< "$change"
    printf '\n%s → %s шардів:\n' "$from" "$to"
    api GET "/api/admin/sharding/resharding?from=$from&to=$to&keys=100000" \
        | grep -o '"strategy":"[a-z]*"[^}]*"movedPercent":[0-9.]*' \
        | sed 's/"strategy":"\([a-z]*\)".*"movedPercent":\([0-9.]*\)/  \1: переноситься \2 % ключів/'
done
printf '\n'
