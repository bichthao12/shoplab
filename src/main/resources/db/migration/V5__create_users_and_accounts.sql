-- ===================== USERS =====================
-- Hồ sơ người dùng (thông tin cá nhân). Thông tin đăng nhập nằm ở bảng accounts.
CREATE TABLE users (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,                 -- luôn lưu dạng lowercase
    full_name   VARCHAR(255) NOT NULL,
    phone       VARCHAR(20),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_users_email           UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

-- ===================== ACCOUNTS =====================
-- Thông tin đăng nhập, quan hệ 1-1 với users.
CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    username       VARCHAR(50)  NOT NULL,              -- luôn lưu dạng lowercase
    password_hash  VARCHAR(255) NOT NULL,              -- vd: bcrypt, không bao giờ lưu mật khẩu thô
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_accounts_user              FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_accounts_user              UNIQUE (user_id),   -- 1 user ↔ 1 account
    CONSTRAINT uk_accounts_username          UNIQUE (username),
    CONSTRAINT ck_accounts_username_lowercase CHECK (username = lower(username)),
    CONSTRAINT ck_accounts_status            CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);
