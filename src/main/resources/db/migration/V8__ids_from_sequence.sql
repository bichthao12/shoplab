-- Id lấy từ sequence thay vì IDENTITY: Hibernate xin trước 50 id mỗi lần (allocationSize = 50),
-- nên không phải INSERT ngay từng dòng để biết id và gộp được nhiều câu INSERT thành một lượt gửi.
--
-- Bỏ IDENTITY và tạo sequence thường vì sequence của cột IDENTITY không hiện trong
-- information_schema.sequences – nơi Hibernate kiểm tra schema khi khởi động (ddl-auto=validate).
--  - INCREMENT BY 50 khớp allocationSize của @SequenceGenerator (Hibernate cũng kiểm tra điều này).
--  - setval: lần nextval tiếp theo = id lớn nhất + 50, nên dải id đầu tiên Hibernate xin
--    (từ id lớn nhất + 1) không trùng dữ liệu cũ. Bảng rỗng thì bắt đầu từ 1.
--  - DEFAULT nextval: INSERT bằng SQL không ghi id vẫn chạy như cũ, không trùng dải id Hibernate đang giữ.

ALTER TABLE products ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE products_id_seq INCREMENT BY 50 OWNED BY products.id;
SELECT setval('products_id_seq', COALESCE(max(id), 1), max(id) IS NOT NULL) FROM products;
ALTER TABLE products ALTER COLUMN id SET DEFAULT nextval('products_id_seq');

ALTER TABLE orders ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE orders_id_seq INCREMENT BY 50 OWNED BY orders.id;
SELECT setval('orders_id_seq', COALESCE(max(id), 1), max(id) IS NOT NULL) FROM orders;
ALTER TABLE orders ALTER COLUMN id SET DEFAULT nextval('orders_id_seq');

ALTER TABLE order_items ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE order_items_id_seq INCREMENT BY 50 OWNED BY order_items.id;
SELECT setval('order_items_id_seq', COALESCE(max(id), 1), max(id) IS NOT NULL) FROM order_items;
ALTER TABLE order_items ALTER COLUMN id SET DEFAULT nextval('order_items_id_seq');
