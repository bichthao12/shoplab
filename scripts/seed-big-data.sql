-- =====================================================================================================================
-- Sinh dữ liệu lớn cho thí nghiệm index: mặc định 1 triệu user và 10 triệu đơn hàng, sinh thẳng trong PostgreSQL bằng
-- generate_series (không gọi API).
--
-- Chạy:   powershell -ExecutionPolicy Bypass -File .\scripts\seed-big-data.ps1      (kiểm tra RAM Docker, hỏi trước khi xoá)
-- Hoặc:   docker cp scripts/seed-big-data.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -v users=1000000 -v orders=10000000 -f /tmp/seed-big-data.sql
--
-- Cần: bảng đã được Flyway tạo (chạy app một lần), app ĐÃ TẮT, Docker có ít nhất 4 GB RAM, khoảng 5 GB đĩa trống.
--
-- XOÁ SẠCH users, accounts, wallets, orders, order_items, idempotency_keys rồi sinh lại; giữ nguyên products.
-- Mọi bước nạp nằm trong MỘT transaction: lỗi giữa chừng thì rollback hết, DB như cũ (kể cả các index đã tạm bỏ).
--
-- Dữ liệu sinh ra:
--   users     id 1..N, đăng ký rải đều trong 3 năm gần nhất, id tăng theo thời gian đăng ký.
--             Tên Việt theo tỉ lệ họ thật (Nguyễn ~38%, Trần 11%, Lê 9.5%, Phạm 7%...), nam nữ mỗi bên một nửa.
--             Email ascii chữ thường <tên>.<họ><id>@gmail.com (75%), yahoo.com, outlook.com... 70% có số điện thoại.
--   accounts  mỗi user một tài khoản: username = phần trước @ của email, mật khẩu chung "shoplab-seed" (BCrypt),
--             97% ACTIVE, 2% LOCKED, 1% DISABLED.
--   orders    id 1..M, ngày đặt rải đều trong 2 năm gần nhất (~13.700 đơn/ngày với 10 triệu đơn). id tăng theo ngày
--             đặt như đơn thật (id lấy từ sequence lúc đặt), nên bảng nằm trên đĩa theo thứ tự thời gian.
--             Người đặt là một user đã đăng ký trước ngày đặt; user đăng ký sớm đặt nhiều hơn (lệch, có user hàng
--             nghìn đơn, có user không đơn nào). Tên, email trên đơn chụp từ hồ sơ user như app làm.
--             Trạng thái theo tuổi đơn, như một shop thật:
--               dưới 1 ngày  PENDING 40%  PAID 40%  SHIPPED 15%                 CANCELLED 5%
--               1–7 ngày     PENDING 3%   PAID 10%  SHIPPED 50%  COMPLETED 30%  CANCELLED 7%
--               trên 7 ngày  PENDING 2% (đơn treo: chưa thanh toán, không ai huỷ)  COMPLETED 90%  CANCELLED 8%
--             → cả bảng khoảng 2% PENDING, 0.1% PAID, 0.4% SHIPPED, 89% COMPLETED, 8% CANCELLED.
--             Tổng tiền 50.000–5.000.000 (đơn nhỏ nhiều hơn đơn lớn). Đơn không có dòng hàng (order_items).
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8
\if :{?users}
\else
    \set users 1000000
\endif
\if :{?orders}
\else
    \set orders 10000000
\endif

\set QUIET on
SET client_min_messages = warning;      -- bỏ các NOTICE không cần thiết
SET lock_timeout = '10s';               -- psql khác đang giữ bảng thì báo lỗi, không chờ mãi
SET work_mem = '256MB';                 -- bảng băm 1 triệu user (gắn tên, email vào đơn) và sắp xếp nằm trong RAM
SET maintenance_work_mem = '512MB';     -- dựng lại index sau khi nạp

-- App phải tắt: Hibernate lấy trước từng dải 50 id từ sequence và giữ trong bộ nhớ. App đang chạy trong lúc sinh thì
-- dải nó đang giữ (vd user 10002..10050) trùng id user vừa sinh → lần đăng ký tiếp theo lỗi trùng khoá chính.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_stat_activity
               WHERE datname = current_database() AND application_name = 'PostgreSQL JDBC Driver') THEN
        RAISE EXCEPTION 'App (hoặc chương trình Java khác) đang kết nối DB này: tắt app rồi chạy lại'
            USING DETAIL = 'App giữ sẵn dải id lấy trước từ sequence, sinh xong sẽ cấp id trùng dữ liệu mới.';
    END IF;
END
$$;
\set QUIET off

\echo 'Sinh' :users 'user,' :orders 'đơn hàng'
\timing on

BEGIN;

\echo
\echo '== 1/7 Xoá dữ liệu cũ (giữ products)'
TRUNCATE users, accounts, wallets, orders, order_items, idempotency_keys;

\echo
\echo '== 2/7 Tạm bỏ index phụ của users, accounts, orders (nạp xong mới dựng: nhanh hơn cập nhật index từng dòng)'
-- Index phụ = index không gắn với ràng buộc nào (khoá chính, unique giữ nguyên). Lấy định nghĩa từ chính DB nên
-- index mới thêm bằng migration sau này cũng được bỏ rồi dựng lại đúng như cũ.
CREATE TEMP TABLE seed_dropped_index ON COMMIT DROP AS
SELECT i.indexrelid::regclass::text AS name, pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i
WHERE i.indrelid IN ('users'::regclass, 'accounts'::regclass, 'orders'::regclass)
  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid);

SELECT format('DROP INDEX %s', name) FROM seed_dropped_index ORDER BY name \gexec

\echo
\echo '== 3/7 users'
-- Mỗi tầng con sinh số ngẫu nhiên một lần cho mỗi dòng (PostgreSQL không gộp tầng có random() vào tầng ngoài),
-- tầng ngoài dùng lại các số đó: họ chọn một lần cho cả tên lẫn email, giới tính một lần cho cả tên đệm lẫn tên.
INSERT INTO users (id, email, full_name, phone, version, created_at, updated_at)
SELECT n.id,
       -- bỏ dấu tiếng Việt: translate đổi từng ký tự có dấu sang chữ không dấu ở cùng vị trí
       lower(translate(n.ten || '.' || n.ho,
                       'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđĐ',
                       repeat('a', 17) || repeat('e', 11) || repeat('i', 5) || repeat('o', 17) || repeat('u', 11)
                           || repeat('y', 5) || 'dd'))
           || n.id || '@' || n.domain,
       n.ho || ' ' || n.dem || ' ' || n.ten,
       n.phone,
       0,
       n.created_at,
       n.created_at
FROM (
    SELECT r.id,
           -- width_bucket(r, mốc): vị trí mốc lớn nhất ≤ r, nên họ thứ k có xác suất = mốc k+1 − mốc k
           (ARRAY['Nguyễn', 'Trần', 'Lê', 'Phạm', 'Hoàng', 'Huỳnh', 'Phan', 'Vũ', 'Võ', 'Đặng', 'Bùi', 'Đỗ', 'Hồ',
                  'Ngô', 'Dương', 'Lý', 'Đào', 'Đinh', 'Mai', 'Trương', 'Lâm', 'Cao', 'Hà', 'Trịnh', 'Lương', 'Tạ'])
               [width_bucket(r.r_ho, ARRAY[0, .384, .494, .589, .660, .690, .711, .756, .776, .795, .816, .836, .850,
                                           .863, .876, .886, .891, .9019, .9128, .9237, .9346, .9455, .9564, .9673,
                                           .9782, .9891]::float8[])] AS ho,
           CASE WHEN r.r_nu < 0.5
                THEN (ARRAY['Thị', 'Ngọc', 'Thu', 'Thanh', 'Thùy', 'Phương', 'Mỹ', 'Bảo', 'Kim', 'Khánh'])
                         [1 + floor(r.r_dem * 10)::int]
                ELSE (ARRAY['Văn', 'Đức', 'Minh', 'Quốc', 'Hữu', 'Thành', 'Công', 'Quang', 'Anh', 'Gia'])
                         [1 + floor(r.r_dem * 10)::int]
           END AS dem,
           CASE WHEN r.r_nu < 0.5
                THEN (ARRAY['Anh', 'Châu', 'Chi', 'Dung', 'Giang', 'Hà', 'Hạnh', 'Hằng', 'Hoa', 'Hương', 'Lan', 'Linh',
                            'Loan', 'Mai', 'My', 'Nga', 'Ngân', 'Nhung', 'Oanh', 'Phương', 'Quỳnh', 'Thảo', 'Trang',
                            'Trinh', 'Uyên', 'Vân', 'Yến', 'Thư'])[1 + floor(r.r_ten * 28)::int]
                ELSE (ARRAY['An', 'Bình', 'Cường', 'Dũng', 'Đạt', 'Hải', 'Hiếu', 'Hoàng', 'Hùng', 'Huy', 'Khang',
                            'Khánh', 'Kiên', 'Long', 'Minh', 'Nam', 'Nghĩa', 'Phong', 'Phúc', 'Quân', 'Sơn', 'Tài',
                            'Thắng', 'Thịnh', 'Trung', 'Tuấn', 'Việt', 'Vinh'])[1 + floor(r.r_ten * 28)::int]
           END AS ten,
           (ARRAY['gmail.com', 'yahoo.com', 'outlook.com', 'icloud.com', 'hotmail.com', 'fpt.com.vn'])
               [width_bucket(r.r_domain, ARRAY[0, .75, .83, .89, .93, .96]::float8[])] AS domain,
           CASE WHEN r.r_phone < 0.7
                THEN (ARRAY['090', '091', '093', '094', '096', '097', '098', '086', '088', '089', '032', '033', '034',
                            '035', '036', '037', '038', '039', '070', '076', '077', '078', '079', '081', '082', '083',
                            '084', '085'])[1 + floor(random() * 28)::int]
                     || lpad(floor(random() * 10000000)::int::text, 7, '0')
           END AS phone,
           -- user id có khe thời gian riêng (3 năm chia đều cho N user), r_time rải trong khe: id tăng theo thời gian
           now() - interval '1095 days' * ((:users - r.id + r.r_time) / :users::float8) AS created_at
    FROM (
        SELECT g AS id, random() AS r_ho, random() AS r_nu, random() AS r_dem, random() AS r_ten,
               random() AS r_domain, random() AS r_phone, random() AS r_time
        FROM generate_series(1, :users) AS g
    ) r
) n;

\echo
\echo '== 4/7 accounts'
INSERT INTO accounts (id, user_id, username, password_hash, status, last_login_at, version, created_at, updated_at)
SELECT u.id,
       u.id,
       split_part(u.email, '@', 1),
       '$2a$10$Cf.OmxClZ8QowmR/X8V4IOXT/DaNmOQ3LN8tlR5ctCXmfeTCLmpS2',          -- BCrypt của "shoplab-seed"
       CASE WHEN u.r_status < 0.97 THEN 'ACTIVE' WHEN u.r_status < 0.99 THEN 'LOCKED' ELSE 'DISABLED' END,
       CASE WHEN u.r_login < 0.9 THEN u.created_at + (now() - u.created_at) * u.r_login / 0.9 END,
       0,
       u.created_at,
       u.created_at
FROM (SELECT id, email, created_at, random() AS r_status, random() AS r_login FROM users) u;

\echo
\echo '== 5/7 orders (bước lâu nhất: sinh đơn, gắn tên, email người đặt, xếp theo id)'
INSERT INTO orders (id, user_id, customer_name, customer_email, status, total_amount, version, created_at, updated_at)
SELECT o.id,
       o.user_id,
       u.full_name,
       u.email,
       o.status,
       o.total_amount,
       o.version,
       o.created_at,
       -- mỗi lần đổi trạng thái (version + 1) cách nhau tối đa 1 ngày, không vượt quá hiện tại
       LEAST(now(), o.created_at + o.version * random() * interval '1 day')
FROM (
    SELECT s.*,
           CASE s.status WHEN 'PENDING' THEN 0 WHEN 'PAID' THEN 1 WHEN 'SHIPPED' THEN 2 WHEN 'COMPLETED' THEN 3
                         ELSE 1 END AS version
    FROM (
        SELECT a.id,
               now() - a.age_days * interval '1 day' AS created_at,
               -- lúc đơn có tuổi age_days mới chỉ có các user id 1..N × (1 − age_days / 1095) đã đăng ký
               -- (user đăng ký rải đều trong 1095 ngày). power(r, 1.5) dồn về id nhỏ: user lâu năm đặt nhiều hơn.
               1 + floor(floor(:users * (1 - a.age_days / 1095)) * power(a.r_user, 1.5))::bigint AS user_id,
               CASE
                   WHEN a.age_days < 1 THEN
                       CASE WHEN a.r_status < 0.40 THEN 'PENDING' WHEN a.r_status < 0.80 THEN 'PAID'
                            WHEN a.r_status < 0.95 THEN 'SHIPPED' ELSE 'CANCELLED' END
                   WHEN a.age_days < 7 THEN
                       CASE WHEN a.r_status < 0.03 THEN 'PENDING' WHEN a.r_status < 0.13 THEN 'PAID'
                            WHEN a.r_status < 0.63 THEN 'SHIPPED' WHEN a.r_status < 0.93 THEN 'COMPLETED'
                            ELSE 'CANCELLED' END
                   ELSE
                       CASE WHEN a.r_status < 0.02 THEN 'PENDING' WHEN a.r_status < 0.92 THEN 'COMPLETED'
                            ELSE 'CANCELLED' END
               END AS status,
               -- 50.000 × 100^r: phân bố log, đơn nhỏ nhiều hơn đơn lớn; làm tròn nghìn đồng
               round(50000 * power(100, a.r_amount) / 1000) * 1000 AS total_amount
        FROM (
            -- đơn id có khe thời gian riêng (730 ngày chia đều cho M đơn): id tăng theo ngày đặt
            SELECT r.*, 730 * (:orders - r.id + r.r_time) / :orders::float8 AS age_days
            FROM (
                SELECT g AS id, random() AS r_time, random() AS r_user, random() AS r_status, random() AS r_amount
                FROM generate_series(1, :orders) AS g
            ) r
        ) a
    ) s
) o
JOIN users u ON u.id = o.user_id
ORDER BY o.id;          -- ghi xuống đĩa theo thứ tự id = thứ tự thời gian (phép nối có thể đảo thứ tự)

\echo
\echo '== 6/7 Dựng lại index phụ, chỉnh sequence, commit'
SELECT definition FROM seed_dropped_index ORDER BY name \gexec

-- lần nextval tiếp theo = id lớn nhất + 50: dải id Hibernate xin tiếp không trùng dữ liệu sinh (xem V8)
SELECT setval('users_id_seq', max(id)) AS users_id_seq FROM users;
SELECT setval('accounts_id_seq', max(id)) AS accounts_id_seq FROM accounts;
SELECT setval('orders_id_seq', max(id)) AS orders_id_seq FROM orders;

COMMIT;

\echo
\echo '== 7/7 VACUUM ANALYZE (kèm FREEZE)'
-- Phải chạy sau COMMIT (VACUUM không chạy trong transaction, và chỉ đánh dấu được dòng mọi transaction đều thấy).
--   ANALYZE  ghi thống kê từng cột (pg_stats: giá trị phổ biến, tần suất, histogram) → planner ước tính đúng số dòng.
--            Chưa có thì status = 'COMPLETED' bị đoán 0,5% thay vì 89%, chọn sai cách đọc.
--   VACUUM   đánh dấu trang all-visible trong visibility map → Index Only Scan lấy kết quả từ index, không ghé bảng.
--            Chưa có thì Index Only Scan vẫn ghé bảng cho từng dòng (Heap Fetches = số dòng).
--   FREEZE   đánh dấu sẵn mọi dòng: câu SELECT đầu tiên không phải tự ghi hint bit vào cả bảng, và sau này không có
--            lượt VACUUM chống wraparound nào phải ghi lại cả bảng giữa lúc đang đo.
-- Đo trước / sau từng lệnh: scripts/vacuum-analyze-lab.sql
VACUUM (FREEZE, ANALYZE) users, accounts, orders;

\timing off
\echo
\echo '== Kiểm tra VACUUM ANALYZE'
SELECT c.relname AS "Bảng",
       (SELECT count(*) FROM pg_stats s WHERE s.schemaname = 'public' AND s.tablename = c.relname)
           AS "Cột có thống kê (ANALYZE)",
       c.reltuples::bigint AS "Planner biết số dòng",
       round(100.0 * c.relallvisible / greatest(c.relpages, 1), 1) AS "% trang all-visible (VACUUM)"
FROM pg_class c
WHERE c.oid IN ('users'::regclass, 'accounts'::regclass, 'orders'::regclass)
ORDER BY c.relname;

-- Index Only Scan đọc được thẳng từ index: mong đợi Heap Fetches: 0
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) SELECT count(*) FROM orders WHERE status = 'PENDING';

SELECT bool_and(c.relallvisible >= 0.99 * c.relpages) AS all_visible
FROM pg_class c
WHERE c.oid IN ('users'::regclass, 'accounts'::regclass, 'orders'::regclass) \gset
\if :all_visible
\else
    -- Dòng vừa nạp chỉ được đánh dấu all-visible khi không còn transaction nào bắt đầu trước lúc COMMIT ở trên
    -- (transaction đó không được thấy chúng). Thường là một psql / công cụ DB đang BEGIN dở.
    \echo
    \echo 'CẢNH BÁO: VACUUM chưa đánh dấu được hết các trang: planner bỏ Index Only Scan, hoặc dùng mà vẫn phải ghé bảng (Heap Fetches > 0).'
    \echo 'Có transaction mở từ trước khi nạp xong (các phiên dưới đây). Đóng nó (COMMIT / ROLLBACK, hoặc tắt công cụ), rồi chạy:'
    \echo '    VACUUM users, accounts, orders;'
    SELECT pid, application_name, state, xact_start, left(query, 60) AS query
    FROM pg_stat_activity
    WHERE pid <> pg_backend_pid() AND backend_type = 'client backend'
      AND (backend_xmin IS NOT NULL OR backend_xid IS NOT NULL);
\endif
\echo
\echo '== Kết quả'
SELECT status AS "Trạng thái", count(*) AS "Số đơn",
       round(100.0 * count(*) / sum(count(*)) OVER (), 2) AS "%"
FROM orders
GROUP BY status
ORDER BY count(*) DESC;

-- Rải đều: số đơn mỗi ngày (bỏ ngày đầu và ngày cuối vì không trọn ngày)
WITH per_day AS (
    SELECT created_at::date AS day, count(*) AS n FROM orders GROUP BY 1
)
SELECT min(day) AS "Từ ngày", max(day) AS "Đến ngày",
       min(n) FILTER (WHERE day > first_day AND day < last_day) AS "Ít nhất / ngày",
       round(avg(n) FILTER (WHERE day > first_day AND day < last_day)) AS "Trung bình / ngày",
       max(n) FILTER (WHERE day > first_day AND day < last_day) AS "Nhiều nhất / ngày"
FROM per_day, (SELECT min(day) AS first_day, max(day) AS last_day FROM per_day) AS bounds;

-- Lệch theo người đặt
WITH per_user AS (
    SELECT user_id, count(*) AS n FROM orders GROUP BY user_id
)
SELECT (SELECT count(*) FROM users) - count(*) AS "User chưa có đơn",
       percentile_disc(0.5) WITHIN GROUP (ORDER BY n) AS "Trung vị đơn / user có đơn",
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n) AS "p99",
       max(n) AS "Nhiều nhất"
FROM per_user;

-- Bảng nằm trên đĩa theo thứ tự cột nào: 1 = đúng thứ tự (đọc theo index là đọc bảng tuần tự), ~0 = rải ngẫu nhiên
SELECT attname AS "Cột của orders", round(correlation::numeric, 3) AS "Tương quan với thứ tự trên đĩa"
FROM pg_stats
WHERE schemaname = 'public' AND tablename = 'orders' AND attname IN ('id', 'created_at', 'user_id', 'status')
ORDER BY abs(correlation) DESC;

SELECT coalesce(i.indrelid, c.oid)::regclass AS "Bảng",
       CASE WHEN c.relkind = 'i' THEN c.relname ELSE '(dữ liệu)' END AS "Phần",
       pg_size_pretty(pg_relation_size(c.oid)) AS "Kích thước"
FROM pg_class c
LEFT JOIN pg_index i ON i.indexrelid = c.oid
WHERE coalesce(i.indrelid, c.oid) IN ('users'::regclass, 'accounts'::regclass, 'orders'::regclass)
ORDER BY coalesce(i.indrelid, c.oid)::regclass::text, c.relkind DESC, c.relname;
