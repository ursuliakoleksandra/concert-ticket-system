-- ЛР7. Журнал операцій користувачів (Ledger): кожна купівля дає запис BUY покупцю і SELL продавцю.
-- Таблиця існує на кожному шарді; рядок зберігається на шарді, обраному за ключем шардингу user_id.

CREATE TABLE ledger_entries (
    -- UUID замість BIGSERIAL: послідовності на різних шардах незалежні, тож автоінкремент
    -- дав би однакові id на shard-a і shard-b. gen_random_uuid() унікальний без координації.
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      BIGINT        NOT NULL,            -- ключ шардингу
    event_id     BIGINT        NOT NULL,
    entry_type   VARCHAR(4)    NOT NULL CHECK (entry_type IN ('BUY', 'SELL')),
    amount       NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    purchase_id  BIGINT,                            -- NULL для синтетичних записів генератора
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Shard-Local запит: історія операцій користувача, найновіші першими
CREATE INDEX ix_ledger_user_created ON ledger_entries (user_id, created_at DESC);
-- Cross-Shard запит: звіт продажів події (виконується на кожному шарді)
CREATE INDEX ix_ledger_event ON ledger_entries (event_id);
-- Ідемпотентність кроків саги купівлі: повтор того самого кроку не створює дубліката.
-- Унікальність гарантується лише в межах шарду — глобальних UNIQUE між шардами не існує.
CREATE UNIQUE INDEX ux_ledger_purchase_side ON ledger_entries (purchase_id, entry_type)
    WHERE purchase_id IS NOT NULL;
