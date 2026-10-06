-- Thêm cột category để lọc sản phẩm: GET /api/products?category=ao
ALTER TABLE products
    ADD COLUMN category VARCHAR(50) NOT NULL DEFAULT 'uncategorized';

CREATE INDEX idx_products_category ON products (category);
