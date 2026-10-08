-- ===================== WALLETS (module wallet) =====================
-- Ví tiền: mỗi người dùng một ví, số dư không bao giờ âm.
-- user_id là id bên module user, không có khoá ngoại sang users (khoá ngoại chỉ nối bảng trong cùng module, xem V10).
-- Id lấy từ sequence bước 50, khớp @SequenceGenerator(allocationSize = 50) như các bảng khác (xem V8).
CREATE SEQUENCE wallets_id_seq INCREMENT BY 50;

CREATE TABLE wallets (
    id          BIGINT         PRIMARY KEY DEFAULT nextval('wallets_id_seq'),
    user_id     BIGINT         NOT NULL,
    balance     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_wallets_user    UNIQUE (user_id),   -- 1 user ↔ 1 ví
    CONSTRAINT ck_wallets_balance CHECK (balance >= 0)
);

ALTER SEQUENCE wallets_id_seq OWNED BY wallets.id;
