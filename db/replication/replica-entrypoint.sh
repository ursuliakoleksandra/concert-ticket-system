#!/bin/sh
# ЛР6. Запуск PostgreSQL у режимі Read Replica (Hot Standby).
# Перший старт (порожній том): роль replicator і слот реплікації на primary, повна копія даних
# pg_basebackup -R (створює standby.signal і primary_conninfo). Далі репліка безперервно отримує
# журнал WAL з primary (асинхронна потокова реплікація) і відкрита лише для читання.
set -eu

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-postgres}"
SLOT="${REPLICATION_SLOT:-resale_replica_1}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "replica: очікування primary ($PRIMARY_HOST)..."
    until pg_isready -h "$PRIMARY_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
        sleep 1
    done

    export PGPASSWORD="$POSTGRES_PASSWORD"
    echo "replica: роль replicator і фізичний слот $SLOT на primary"
    psql -v ON_ERROR_STOP=1 -h "$PRIMARY_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$REPLICATION_PASSWORD';
    END IF;
END
\$\$;
SELECT pg_create_physical_replication_slot('$SLOT')
WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '$SLOT');
SQL
    unset PGPASSWORD

    echo "replica: pg_basebackup з primary"
    pg_basebackup \
        -d "host=$PRIMARY_HOST port=5432 user=replicator password=$REPLICATION_PASSWORD application_name=resale-replica" \
        -D "$PGDATA" -X stream -S "$SLOT" -R --progress
    chmod 0700 "$PGDATA"
fi

# recovery_min_apply_delay: штучна затримка застосування транзакцій (демонстрація Replication Lag / Stale Read)
exec docker-entrypoint.sh postgres \
    -c hba_file=/replication/pg_hba.conf \
    -c hot_standby=on \
    -c hot_standby_feedback=on \
    -c recovery_min_apply_delay="${REPLICA_APPLY_DELAY:-0}"
