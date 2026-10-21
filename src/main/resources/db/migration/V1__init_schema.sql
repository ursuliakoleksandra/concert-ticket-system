-- Схема платформи перепродажу квитків

CREATE TABLE users (
    id          BIGSERIAL    PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    full_name   VARCHAR(150) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id          BIGSERIAL     PRIMARY KEY,
    title       VARCHAR(200)  NOT NULL,
    venue       VARCHAR(200)  NOT NULL,
    starts_at   TIMESTAMPTZ   NOT NULL,
    face_value  NUMERIC(10,2) NOT NULL CHECK (face_value > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE tickets (
    id          BIGSERIAL   PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events(id),
    owner_id    BIGINT      NOT NULL REFERENCES users(id),
    seat        VARCHAR(50) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tickets_event_seat UNIQUE (event_id, seat)
);
CREATE INDEX ix_tickets_owner ON tickets(owner_id);

CREATE TABLE listings (
    id          BIGSERIAL     PRIMARY KEY,
    ticket_id   BIGINT        NOT NULL REFERENCES tickets(id),
    event_id    BIGINT        NOT NULL REFERENCES events(id),
    seller_id   BIGINT        NOT NULL REFERENCES users(id),
    price       NUMERIC(10,2) NOT NULL CHECK (price > 0),
    status      VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'SOLD', 'CANCELLED')),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- Один квиток не може мати більше одного активного оголошення
CREATE UNIQUE INDEX ux_listings_active_ticket ON listings(ticket_id) WHERE status = 'ACTIVE';
-- Основний «гарячий» запит: активні оголошення події, відсортовані за ціною
CREATE INDEX ix_listings_event_status_price ON listings(event_id, status, price);

CREATE TABLE purchases (
    id          BIGSERIAL     PRIMARY KEY,
    listing_id  BIGINT        NOT NULL UNIQUE REFERENCES listings(id),
    buyer_id    BIGINT        NOT NULL REFERENCES users(id),
    seller_id   BIGINT        NOT NULL REFERENCES users(id),
    event_id    BIGINT        NOT NULL REFERENCES events(id),
    price       NUMERIC(10,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- Перевірка ліміту покупок на подію для одного покупця
CREATE INDEX ix_purchases_buyer_event ON purchases(buyer_id, event_id);
