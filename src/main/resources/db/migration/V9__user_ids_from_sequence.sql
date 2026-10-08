-- users / accounts chuyển sang lấy id từ sequence như các bảng khác (xem V8 để biết lý do và cách làm).

ALTER TABLE users ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE users_id_seq INCREMENT BY 50 OWNED BY users.id;
SELECT setval('users_id_seq', COALESCE(max(id), 1), max(id) IS NOT NULL) FROM users;
ALTER TABLE users ALTER COLUMN id SET DEFAULT nextval('users_id_seq');

ALTER TABLE accounts ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE accounts_id_seq INCREMENT BY 50 OWNED BY accounts.id;
SELECT setval('accounts_id_seq', COALESCE(max(id), 1), max(id) IS NOT NULL) FROM accounts;
ALTER TABLE accounts ALTER COLUMN id SET DEFAULT nextval('accounts_id_seq');
