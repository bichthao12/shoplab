-- ===================== PRODUCTS =====================
CREATE TABLE products (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku          VARCHAR(64)    NOT NULL,
    name         VARCHAR(255)   NOT NULL,
    description  TEXT,
    price        NUMERIC(12, 2) NOT NULL,
    stock        INTEGER        NOT NULL DEFAULT 0,
    active       BOOLEAN        NOT NULL DEFAULT TRUE,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_products_sku   UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    CONSTRAINT ck_products_stock CHECK (stock >= 0)
);

-- ===================== ORDERS =====================
CREATE TABLE orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_name   VARCHAR(255)   NOT NULL,
    customer_email  VARCHAR(255)   NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_orders_total  CHECK (total_amount >= 0)
);

CREATE INDEX idx_orders_status     ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);

-- ===================== ORDER_ITEMS =====================
CREATE TABLE order_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT         NOT NULL,
    product_id  BIGINT         NOT NULL,
    quantity    INTEGER        NOT NULL,
    unit_price  NUMERIC(12, 2) NOT NULL,   -- giá tại thời điểm đặt hàng

    CONSTRAINT fk_order_items_order   FOREIGN KEY (order_id)   REFERENCES orders (id)   ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT uk_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity   CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_product_id ON order_items (product_id);