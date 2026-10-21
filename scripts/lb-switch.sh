#!/usr/bin/env bash
# Live-переключення алгоритму балансування без перезапуску Nginx (graceful reload).
#   scripts/lb-switch.sh round-robin | least-conn | weighted | ip-hash | scale-1 | scale-2
source "$(dirname "$0")/lib.sh"

profile="${1:-}"
src="nginx/upstreams/$profile.conf"
if [[ -z "$profile" || "$profile" == "active" || ! -f "$src" ]]; then
    printf 'Використання: %s <профіль>\nДоступні профілі:\n' "$0" >&2
    for f in nginx/upstreams/*.conf; do
        name=$(basename "$f" .conf)
        [[ "$name" != "active" ]] && printf '  %s\n' "$name" >&2
    done
    exit 1
fi

cp nginx/upstreams/active.conf nginx/upstreams/.active.bak
{
    printf '# АКТИВНИЙ ПРОФІЛЬ — генерується scripts/lb-switch.sh, копія одного з файлів цього каталогу\n'
    cat "$src"
} > nginx/upstreams/active.conf

# Перевірка конфігурації перед застосуванням; у разі помилки — відкат
if ! docker compose exec -T nginx nginx -t; then
    mv nginx/upstreams/.active.bak nginx/upstreams/active.conf
    printf 'Конфігурація некоректна, профіль не змінено\n' >&2
    exit 1
fi
rm -f nginx/upstreams/.active.bak

# reload: нові worker-процеси з новою конфігурацією, старі завершують поточні запити
docker compose exec -T nginx nginx -s reload
printf 'Активний профіль балансування: %s\n' "$profile"
grep -E '^\s*(least_conn|ip_hash|server)' nginx/upstreams/active.conf
