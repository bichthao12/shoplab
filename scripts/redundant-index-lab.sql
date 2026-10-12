-- =====================================================================================================================
-- Thí nghiệm: index thừa làm chậm việc ghi. Chèn 100.000 đơn trước và sau khi thêm 5 index thừa.
--
-- Mỗi index của bảng là một cấu trúc phải cập nhật mỗi khi chèn một dòng: tìm đúng trang lá, ghi khoá vào đó (đầy thì
-- tách trang), ghi WAL cho thay đổi đó. Index giúp đọc, nhưng câu nào cũng ghi phải trả giá cho MỌI index của bảng,
-- kể cả index không câu đọc nào dùng.
--
-- Ba phương án, cùng một bản chép của bảng orders (cùng dữ liệu, index dựng mới cho từng phương án):
--   V0  chỉ khoá chính (id)
--   V1  index hiện có của orders (trước)
--   V2  index hiện có + 5 index thừa (sau):
--         (user_id)                            trùng phần đầu của idx_orders_user_id_created_at
--         (user_id, created_at DESC, id DESC)  trùng hệt idx_orders_user_id_created_at
--         (created_at)                         trùng idx_orders_created_at: B-tree đọc được cả hai chiều
--         (customer_email)                     không câu nào của app lọc đơn theo email
--         (total_amount)                       không câu nào của app lọc đơn theo tổng tiền
-- Mỗi phương án chèn 100.000 đơn theo hai cách:
--   - một câu INSERT 100.000 dòng (nạp hàng loạt);
--   - 100.000 transaction, mỗi transaction một đơn, 8 kết nối song song (pgbench): giống app, mỗi POST /api/orders
--     là một transaction riêng.
-- Mỗi cách đo 3 lần, lấy trung vị.
-- Trước mỗi lần đo chạy CHECKPOINT: lần đầu sửa một trang sau checkpoint, PostgreSQL ghi cả trang 8 KB vào WAL
-- (full page write), nên phải bắt đầu các lần đo từ cùng một trạng thái.
--
-- Chỉ đọc bảng thật (orders, users) một lần để chép; mọi thứ khác nằm trong schema write_lab, xoá khi xong. App chạy
-- cùng lúc cũng được, nhưng đừng gọi API trong lúc đo: WAL đo được tính cả câu ghi của app.
-- Với 2 triệu đơn: khoảng 4 phút, cần khoảng 1 GB đĩa trống (bản chép và index của nó). 10 triệu đơn lâu hơn nhiều
-- (chép và dựng index trên 10 triệu dòng).
--
-- Chạy:   docker cp scripts/redundant-index-lab.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/redundant-index-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8

\set QUIET on
SET client_min_messages = warning;

DROP SCHEMA IF EXISTS write_lab CASCADE;
CREATE SCHEMA write_lab;

CREATE TABLE write_lab.variant (
    variant    text PRIMARY KEY,
    name       text,
    indexes    int,       -- số index
    index_size bigint,    -- tổng kích thước index ngay sau khi dựng
    build_s    numeric    -- thời gian dựng các index
);

CREATE TABLE write_lab.result (
    id        serial PRIMARY KEY,
    variant   text,
    method    text,       -- 'insert' (một câu INSERT 100.000 dòng) / 'pgbench' (100.000 transaction)
    run       int,
    ms        numeric,    -- thời gian, tính cả COMMIT
    wal_bytes numeric     -- WAL sinh ra
);

CREATE TABLE write_lab.index_size (
    variant    text,
    index_name text,
    definition text,
    built      bigint,    -- kích thước ngay sau khi dựng
    after      bigint     -- sau khi chèn xong 600.000 đơn
);

-- 5 index thừa của V2
CREATE TABLE write_lab.redundant (
    name    text PRIMARY KEY,
    columns text,
    why     text
);
INSERT INTO write_lab.redundant VALUES
    ('extra_user_id',            '(user_id)',                           'trùng phần đầu của idx_orders_user_id_created_at'),
    ('extra_user_id_created_at', '(user_id, created_at DESC, id DESC)', 'trùng hệt idx_orders_user_id_created_at'),
    ('extra_created_at',         '(created_at)',                        'trùng idx_orders_created_at (khác chiều, B-tree đọc được cả hai chiều)'),
    ('extra_customer_email',     '(customer_email)',                    'không câu nào lọc đơn theo email'),
    ('extra_total_amount',       '(total_amount)',                      'không câu nào lọc đơn theo tổng tiền');

-- Dựng index cho một phương án trên write_lab.orders. Index hiện có lấy đúng định nghĩa của public.orders (thêm index
-- bằng migration sau này thì thí nghiệm tự có theo).
CREATE FUNCTION write_lab.build(p_variant text, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    started timestamptz := clock_timestamp();
    d       record;
BEGIN
    FOR d IN SELECT pg_get_indexdef(i.indexrelid) AS def, i.indisprimary
             FROM pg_index i WHERE i.indrelid = 'public.orders'::regclass ORDER BY i.indexrelid LOOP
        IF p_variant = 'V0' AND NOT d.indisprimary THEN
            CONTINUE;
        END IF;
        EXECUTE replace(d.def, ' ON public.orders ', ' ON write_lab.orders ');
    END LOOP;
    IF p_variant = 'V2' THEN
        FOR d IN SELECT name, columns FROM write_lab.redundant ORDER BY name LOOP
            EXECUTE format('CREATE INDEX %I ON write_lab.orders %s', d.name, d.columns);
        END LOOP;
    END IF;
    INSERT INTO write_lab.index_size (variant, index_name, definition, built)
    SELECT p_variant, c.relname, regexp_replace(pg_get_indexdef(i.indexrelid), '^.* USING btree ', ''), pg_relation_size(c.oid)
    FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
    WHERE i.indrelid = 'write_lab.orders'::regclass;
    INSERT INTO write_lab.variant
    SELECT p_variant, p_name, count(*), sum(built), round(extract(epoch FROM clock_timestamp() - started)::numeric, 1)
    FROM write_lab.index_size WHERE variant = p_variant;
END
$$;

-- Bỏ hết index và các đơn đã chèn: bảng về đúng dữ liệu ban đầu (VACUUM ngay sau đó cắt các trang cuối đã trống)
CREATE PROCEDURE write_lab.reset()
LANGUAGE plpgsql AS $$
DECLARE
    d record;
BEGIN
    FOR d IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
             WHERE i.indrelid = 'write_lab.orders'::regclass LOOP
        EXECUTE format('DROP INDEX write_lab.%I', d.relname);
    END LOOP;
    DELETE FROM write_lab.orders WHERE id > (SELECT base_max FROM write_lab.config);
    PERFORM setval('write_lab.orders_id_seq', (SELECT base_max FROM write_lab.config));
END
$$;

-- Một câu INSERT 100.000 đơn (khách lấy từ write_lab.customers), tính cả COMMIT. created_at tăng dần như đơn thật.
CREATE PROCEDURE write_lab.insert_bulk(p_variant text, p_run int)
LANGUAGE plpgsql AS $$
DECLARE
    t0 timestamptz;
    l0 pg_lsn;
BEGIN
    CHECKPOINT;
    COMMIT;
    l0 := pg_current_wal_insert_lsn();
    t0 := clock_timestamp();
    INSERT INTO write_lab.orders (customer_name, customer_email, status, total_amount, version, created_at, updated_at, user_id)
    SELECT c.full_name, c.email, 'PENDING', c.amount, 0,
           now() + c.n * interval '1 microsecond', now() + c.n * interval '1 microsecond', c.user_id
    FROM write_lab.customers c ORDER BY c.n;
    COMMIT;
    INSERT INTO write_lab.result (variant, method, run, ms, wal_bytes)
    VALUES (p_variant, 'insert', p_run, extract(epoch FROM clock_timestamp() - t0)::numeric * 1000,
            pg_wal_lsn_diff(pg_current_wal_insert_lsn(), l0));
    COMMIT;
END
$$;

-- 100.000 transaction, mỗi transaction một đơn, 8 kết nối song song: chạy pgbench (COPY ... FROM PROGRAM chạy nó trong
-- container, giữ lại output để kiểm tra). Thời gian tính từ lúc gọi tới khi pgbench xong (gồm cả lúc mở 8 kết nối).
CREATE TABLE write_lab.pgbench_output (line text);
CREATE PROCEDURE write_lab.insert_pgbench(p_variant text, p_run int)
LANGUAGE plpgsql AS $$
DECLARE
    t0 timestamptz;
    l0 pg_lsn;
BEGIN
    CHECKPOINT;
    TRUNCATE write_lab.pgbench_output;
    COMMIT;
    l0 := pg_current_wal_insert_lsn();
    t0 := clock_timestamp();
    COPY write_lab.pgbench_output FROM PROGRAM
        'pgbench -n -c 8 -j 4 -t 12500 -f /tmp/write_lab_one_order.sql -U shoplab shoplab 2>&1'
        WITH (FORMAT csv, DELIMITER E'\x01', QUOTE E'\x02');
    INSERT INTO write_lab.result (variant, method, run, ms, wal_bytes)
    VALUES (p_variant, 'pgbench', p_run, extract(epoch FROM clock_timestamp() - t0)::numeric * 1000,
            pg_wal_lsn_diff(pg_current_wal_insert_lsn(), l0));
    IF NOT EXISTS (SELECT 1 FROM write_lab.pgbench_output
                   WHERE line LIKE 'number of transactions actually processed: 100000/100000%')
       OR NOT EXISTS (SELECT 1 FROM write_lab.pgbench_output WHERE line LIKE 'number of failed transactions: 0 %') THEN
        RAISE EXCEPTION 'pgbench không chạy đủ 100.000 transaction:%', E'\n' || (SELECT string_agg(line, E'\n') FROM write_lab.pgbench_output);
    END IF;
    COMMIT;
END
$$;

-- Đo một phương án: 3 lần một câu INSERT 100.000 đơn, rồi 3 lần pgbench 100.000 transaction
CREATE PROCEDURE write_lab.measure(p_variant text)
LANGUAGE plpgsql AS $$
BEGIN
    FOR run IN 1..3 LOOP
        CALL write_lab.insert_bulk(p_variant, run);
    END LOOP;
    FOR run IN 1..3 LOOP
        CALL write_lab.insert_pgbench(p_variant, run);
    END LOOP;
END
$$;

-- Sau khi đo xong một phương án: kiểm tra đủ 600.000 đơn đã chèn, ghi kích thước từng index
CREATE FUNCTION write_lab.finish(p_variant text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    inserted bigint;
BEGIN
    SELECT count(*) INTO inserted FROM write_lab.orders WHERE id > (SELECT base_max FROM write_lab.config);
    IF inserted <> 600000 THEN
        RAISE EXCEPTION 'Phương án %: chèn được % đơn, mong đợi 600.000', p_variant, inserted;
    END IF;
    UPDATE write_lab.index_size s SET after = pg_relation_size(to_regclass('write_lab.' || quote_ident(s.index_name)))
    WHERE s.variant = p_variant;
END
$$;
\set QUIET off

-- ---------------------------------------------------------------------------------------------------------------------
\echo '== Chép bảng orders sang write_lab.orders (chỉ dữ liệu, chưa có index)'
SELECT count(*) AS total FROM public.orders \gset
SELECT count(*) AS users_n FROM public.users \gset
\echo 'public.orders:' :total 'đơn, public.users:' :users_n 'người dùng'
SELECT :total > 0 AND :users_n > 0 AS has_data \gset
\if :has_data
\else
    \echo 'Chưa có dữ liệu. Nạp trước: scripts/seed-big-data.ps1'
    \quit
\endif

\timing on
CREATE TABLE write_lab.orders (LIKE public.orders INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE SEQUENCE write_lab.orders_id_seq;
ALTER TABLE write_lab.orders ALTER id SET DEFAULT nextval('write_lab.orders_id_seq');
INSERT INTO write_lab.orders SELECT * FROM public.orders ORDER BY id;
CREATE TABLE write_lab.config AS SELECT max(id) AS base_max FROM write_lab.orders;
SELECT setval('write_lab.orders_id_seq', base_max) FROM write_lab.config \g /dev/null
VACUUM (FREEZE, ANALYZE) write_lab.orders;

-- 100.000 khách đặt đơn: người dùng thật chọn ngẫu nhiên (có thể lặp), tổng tiền ngẫu nhiên. Cùng một danh sách cho
-- mọi phương án (setseed). pgbench chọn ngẫu nhiên một dòng theo n.
SELECT setseed(0.42) \g /dev/null
CREATE TABLE write_lab.users AS SELECT row_number() OVER (ORDER BY id) AS rn, id, full_name, email FROM public.users;
CREATE UNIQUE INDEX ON write_lab.users (rn);
CREATE TABLE write_lab.customers AS
SELECT s.n, u.id AS user_id, u.full_name, u.email, (1 + floor(random() * 500)) * 10000 AS amount
FROM (SELECT n, 1 + floor(random() * :users_n)::bigint AS rn FROM generate_series(1, 100000) AS n) s
JOIN write_lab.users u ON u.rn = s.rn;
ALTER TABLE write_lab.customers ADD PRIMARY KEY (n);
VACUUM ANALYZE write_lab.customers;
\timing off
\set QUIET on

-- Kịch bản pgbench: một đơn mỗi transaction
\o /tmp/write_lab_one_order.sql
\qecho '\\set n random(1, 100000)'
\qecho 'INSERT INTO write_lab.orders (customer_name, customer_email, status, total_amount, version, created_at, updated_at, user_id)'
\qecho 'SELECT c.full_name, c.email, ''PENDING'', c.amount, 0, clock_timestamp(), clock_timestamp(), c.user_id'
\qecho 'FROM write_lab.customers c WHERE c.n = :n;'
\o

-- ---------------------------------------------------------------------------------------------------------------------
\echo
\echo '== V0. Chỉ khoá chính'
SELECT write_lab.build('V0', 'V0. chỉ khoá chính') \g /dev/null
SELECT format('   %s index, %s, dựng trong %s giây', indexes, pg_size_pretty(index_size), build_s) FROM write_lab.variant WHERE variant = 'V0' \g (tuples_only=on)
\echo '   Một câu INSERT 100.000 đơn × 3, rồi 100.000 transaction (pgbench, 8 kết nối) × 3'
CALL write_lab.measure('V0');
SELECT write_lab.finish('V0') \g /dev/null
CALL write_lab.reset();
VACUUM write_lab.orders;

\echo
\echo '== V1. Index hiện có của orders (trước)'
SELECT write_lab.build('V1', 'V1. index hiện có (trước)') \g /dev/null
SELECT format('   %s index, %s, dựng trong %s giây', indexes, pg_size_pretty(index_size), build_s) FROM write_lab.variant WHERE variant = 'V1' \g (tuples_only=on)
\echo '   Một câu INSERT 100.000 đơn × 3, rồi 100.000 transaction (pgbench, 8 kết nối) × 3'
CALL write_lab.measure('V1');
SELECT write_lab.finish('V1') \g /dev/null
CALL write_lab.reset();
VACUUM write_lab.orders;

\echo
\echo '== V2. Index hiện có + 5 index thừa (sau)'
SELECT write_lab.build('V2', 'V2. thêm 5 index thừa (sau)') \g /dev/null
SELECT format('   %s index, %s, dựng trong %s giây', indexes, pg_size_pretty(index_size), build_s) FROM write_lab.variant WHERE variant = 'V2' \g (tuples_only=on)
\echo '   Một câu INSERT 100.000 đơn × 3, rồi 100.000 transaction (pgbench, 8 kết nối) × 3'
CALL write_lab.measure('V2');
SELECT write_lab.finish('V2') \g /dev/null
\! rm -f /tmp/write_lab_one_order.sql
\set QUIET off

-- ---------------------------------------------------------------------------------------------------------------------
\echo
\echo '== Kết quả: chèn 100.000 đơn (bản chép của' :total 'đơn)'
\echo '   Trung vị 3 lần. pgbench: 100.000 transaction, mỗi transaction một đơn, 8 kết nối. "so với V1": thời gian / thời gian của V1'
WITH r AS (
    SELECT variant, method,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric AS ms,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY wal_bytes)::numeric AS wal
    FROM write_lab.result GROUP BY variant, method
)
SELECT v.name AS "Phương án", v.indexes AS "Số index", pg_size_pretty(v.index_size) AS "Kích thước index",
       round(i.ms / 1000, 2) AS "INSERT: giây", round(i.wal / 1048576) AS "INSERT: WAL MB",
       round(i.ms / i1.ms, 2) AS "INSERT: so với V1",
       round(p.ms / 1000, 1) AS "pgbench: giây", round(100000 / (p.ms / 1000)) AS "pgbench: đơn/giây",
       round(p.wal / 1048576) AS "pgbench: WAL MB", round(p.ms / p1.ms, 2) AS "pgbench: so với V1"
FROM write_lab.variant v
JOIN r i ON i.variant = v.variant AND i.method = 'insert'
JOIN r p ON p.variant = v.variant AND p.method = 'pgbench'
JOIN r i1 ON i1.variant = 'V1' AND i1.method = 'insert'
JOIN r p1 ON p1.variant = 'V1' AND p1.method = 'pgbench'
ORDER BY v.variant;

\echo '== Từng lần đo'
SELECT i.variant AS "Phương án", i.run AS "Lần", round(i.ms / 1000, 2) AS "INSERT: giây", round(i.wal_bytes / 1048576) AS "INSERT: WAL MB",
       round(p.ms / 1000, 1) AS "pgbench: giây", round(100000 / (p.ms / 1000)) AS "pgbench: đơn/giây", round(p.wal_bytes / 1048576) AS "pgbench: WAL MB"
FROM write_lab.result i JOIN write_lab.result p ON p.variant = i.variant AND p.run = i.run AND p.method = 'pgbench'
WHERE i.method = 'insert' ORDER BY i.variant, i.run;

\echo '== V2: từng index, trước và sau khi chèn 600.000 đơn (bảng từ' :total 'lên thêm 600.000 đơn)'
SELECT s.index_name AS "Index", s.definition AS "Cột", coalesce(r.why, 'có sẵn') AS "Ghi chú",
       pg_size_pretty(s.built) AS "Sau khi dựng", pg_size_pretty(s.after) AS "Sau khi chèn",
       pg_size_pretty(s.after - s.built) AS "Tăng thêm"
FROM write_lab.index_size s LEFT JOIN write_lab.redundant r ON r.name = s.index_name
WHERE s.variant = 'V2' ORDER BY s.after - s.built DESC;

-- ---------------------------------------------------------------------------------------------------------------------
\echo
\echo '== Tìm index thừa: index B-tree có các cột là phần đầu (hoặc trùng hệt) các cột của một index khác cùng bảng'
\echo '   (bỏ qua index UNIQUE vì nó giữ ràng buộc; index có biểu thức hay WHERE thì phải xem tay)'
\echo '-- Trên bản chép ở V2:'
SELECT a.indexrelid::regclass AS "Có thể bỏ", pg_size_pretty(pg_relation_size(a.indexrelid)) AS "Kích thước",
       b.indexrelid::regclass AS "Vì đã có", regexp_replace(pg_get_indexdef(b.indexrelid), '^.* USING btree ', '') AS "Cột của index đó"
FROM pg_index a
JOIN pg_index b ON b.indrelid = a.indrelid AND b.indexrelid <> a.indexrelid
JOIN pg_class ca ON ca.oid = a.indexrelid
JOIN pg_class cb ON cb.oid = b.indexrelid
JOIN pg_am am ON am.oid = ca.relam AND am.amname = 'btree' AND cb.relam = ca.relam
WHERE a.indrelid = 'write_lab.orders'::regclass
  AND NOT a.indisunique
  AND a.indexprs IS NULL AND b.indexprs IS NULL AND a.indpred IS NULL AND b.indpred IS NULL
  AND (b.indkey::text || ' ') LIKE (a.indkey::text || ' %')                    -- cột của a là phần đầu cột của b
  AND (a.indkey::text <> b.indkey::text OR b.indisunique OR a.indexrelid > b.indexrelid)  -- trùng hệt: chỉ báo một cái
ORDER BY 1::text;
\echo '-- Trên DB thật (schema public):'
SELECT a.indrelid::regclass AS "Bảng", a.indexrelid::regclass AS "Có thể bỏ", b.indexrelid::regclass AS "Vì đã có"
FROM pg_index a
JOIN pg_index b ON b.indrelid = a.indrelid AND b.indexrelid <> a.indexrelid
JOIN pg_class ca ON ca.oid = a.indexrelid
JOIN pg_class cb ON cb.oid = b.indexrelid
JOIN pg_namespace n ON n.oid = ca.relnamespace AND n.nspname = 'public'
JOIN pg_am am ON am.oid = ca.relam AND am.amname = 'btree' AND cb.relam = ca.relam
WHERE NOT a.indisunique
  AND a.indexprs IS NULL AND b.indexprs IS NULL AND a.indpred IS NULL AND b.indpred IS NULL
  AND (b.indkey::text || ' ') LIKE (a.indkey::text || ' %')
  AND (a.indkey::text <> b.indkey::text OR b.indisunique OR a.indexrelid > b.indexrelid)
ORDER BY 1::text, 2::text;

\echo '== Index chưa được câu nào dùng (idx_scan = 0) trên DB thật, không tính UNIQUE / khoá chính.'
\echo '   Chưa đủ để bỏ: idx_scan chỉ đếm từ khi thống kê bắt đầu (reset bằng pg_stat_reset(), mất khi PostgreSQL tắt'
\echo '   không sạch, và index vừa dựng lại như sau seed-big-data.ps1 đếm lại từ 0). Chạy app một thời gian rồi mới xem;'
\echo '   câu hiếm khi chạy (báo cáo cuối tháng) vẫn cần index của nó.'
SELECT '   Thống kê reset lần cuối: ' || coalesce(stats_reset::text, 'chưa reset lần nào')
FROM pg_stat_database WHERE datname = current_database() \g (tuples_only=on)
SELECT s.relname AS "Bảng", s.indexrelname AS "Index", s.idx_scan AS "idx_scan",
       pg_size_pretty(pg_relation_size(s.indexrelid)) AS "Kích thước"
FROM pg_stat_user_indexes s JOIN pg_index i ON i.indexrelid = s.indexrelid
WHERE s.schemaname = 'public' AND NOT i.indisunique AND s.idx_scan = 0
ORDER BY pg_relation_size(s.indexrelid) DESC;

DROP SCHEMA write_lab CASCADE;
