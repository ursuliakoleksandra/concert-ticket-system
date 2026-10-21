-- Початкові дані для демонстрації

INSERT INTO users (email, full_name) VALUES
    ('olena@example.com',  'Олена Коваль'),
    ('andrii@example.com', 'Андрій Мельник'),
    ('iryna@example.com',  'Ірина Бойко');

INSERT INTO events (title, venue, starts_at, face_value) VALUES
    ('Концерт симфонічного оркестру', 'Чернівецька філармонія', '2026-12-12 19:00:00+02', 600.00),
    ('Фестиваль джазу',               'Театральна площа',       '2027-05-20 18:00:00+03', 900.00);

INSERT INTO tickets (event_id, owner_id, seat) VALUES
    (1, 1, 'Партер, ряд 3, місце 7'),
    (1, 1, 'Партер, ряд 3, місце 8'),
    (1, 2, 'Балкон, ряд 1, місце 2'),
    (2, 2, 'Фан-зона, №145');

INSERT INTO listings (ticket_id, event_id, seller_id, price, status) VALUES
    (1, 1, 1, 650.00, 'ACTIVE'),
    (3, 1, 2, 700.00, 'ACTIVE');
