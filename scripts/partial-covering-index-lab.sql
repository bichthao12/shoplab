-- =====================================================================================================================
-- Thí nghiệm: partial index cho đơn PENDING, covering index cho tổng tiền theo user.
--
-- Partial index: chỉ đưa vào index những dòng thoả điều kiện WHERE của index. Đơn PENDING chỉ khoảng 2% bảng, nên
--   index (created_at) WHERE status = 'PENDING' nhỏ khoảng 50 lần so với index thường (status, created_at).
--   Câu phục vụ: job tự huỷ đơn chưa thanh toán quá 1 ngày, và đếm số đơn đang treo.
-- Covering index: index chứa sẵn mọi cột câu truy vấn cần (INCLUDE), PostgreSQL trả kết quả từ index
--   (Index Only Scan) mà không phải ghé bảng. Câu phục vụ: tổng tiền đơn COMPLETED của một user.
--
-- Chạy trên bảng orders thật (cần dữ liệu lớn: scripts/seed-big-data.ps1). Mỗi index thử được dựng, đo rồi bỏ ngay
-- trong CÙNG một câu lệnh: lỗi hay Ctrl+C giữa chừng thì cả câu rollback, bảng thật không giữ lại index nào.
-- Trong lúc dựng một index, bảng orders không ghi được (đặt đơn sẽ chờ) khoảng 10–30 giây: nên tắt app hoặc đừng
-- chạy tải cùng lúc. Chạy vài phút (câu "đếm đơn treo" với index sẵn có cố ý chậm).
--
-- Chạy:   docker cp scripts/partial-covering-index-lab.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/partial-covering-index-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8

\set QUIET on
SET client_min_messages = warning;
SET max_parallel_workers_per_gather = 0;   -- số dòng, số trang trong EXPLAIN là của cả câu, không bị chia theo worker
SET lock_timeout = '30s';                  -- dựng index phải chờ các lệnh ghi đang chạy xong; không chờ mãi

DROP SCHEMA IF EXISTS pc_lab CASCADE;
CREATE SCHEMA pc_lab;

CREATE TABLE pc_lab.variant (
    id       serial PRIMARY KEY,
    part     text,
    name     text,
    size     text,      -- kích thước index thử (trống: chỉ dùng các index sẵn có)
    build_s  numeric    -- thời gian dựng trên 10 triệu dòng
);

CREATE TABLE pc_lab.result (
    id           serial PRIMARY KEY,
    label        text,
    variant      text,
    plan         text,     -- các bước của plan, từ ngoài vào trong
    rows_read    bigint,   -- số dòng nút đọc bảng / index trả ra
    rows_removed bigint,   -- số dòng đã đọc rồi bỏ vì không khớp điều kiện (Rows Removed by Filter)
    heap_fetches bigint,   -- Index Only Scan: số lần vẫn phải ghé bảng
    pages        bigint,   -- số trang 8 KB đã đọc (index + bảng)
    runs         int,      -- số lần đo
    ms           numeric   -- trung vị các lần đo
);

-- EXPLAIN (ANALYZE, BUFFERS) một lần (cũng là lần đưa dữ liệu vào bộ nhớ). Nếu lần đó dưới 1 giây thì đo thêm 5 lần,
-- lấy trung vị; chậm hơn thì giữ kết quả một lần (câu đọc cả trăm nghìn trang, đo lại chỉ tốn thời gian).
CREATE FUNCTION pc_lab.measure(p_label text, p_variant text, p_query text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    plan  jsonb;
    scan  jsonb;
    times numeric[] := '{}';
BEGIN
    EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
    plan := plan -> 0;
    IF (plan ->> 'Execution Time')::numeric < 1000 THEN
        FOR i IN 1..5 LOOP
            EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
            plan := plan -> 0;
            times := times || (plan ->> 'Execution Time')::numeric;
        END LOOP;
    ELSE
        times := ARRAY[(plan ->> 'Execution Time')::numeric];
    END IF;
    scan := jsonb_path_query_first(plan -> 'Plan', 'strict $.** ? (@."Node Type" like_regex "Scan$")');
    INSERT INTO pc_lab.result (label, variant, plan, rows_read, rows_removed, heap_fetches, pages, runs, ms)
    SELECT p_label, p_variant,
           (SELECT string_agg((n ->> 'Node Type')
                              || CASE WHEN n ->> 'Scan Direction' = 'Backward' THEN ' Backward' ELSE '' END
                              || CASE WHEN n ? 'Index Name' THEN ' ' || (n ->> 'Index Name') ELSE '' END,
                              ' → ' ORDER BY ord)
            FROM jsonb_path_query(plan -> 'Plan', 'strict $.** ? (exists (@."Node Type"))') WITH ORDINALITY AS t(n, ord)),
           (scan ->> 'Actual Rows')::bigint * (scan ->> 'Actual Loops')::bigint,
           coalesce((scan ->> 'Rows Removed by Filter')::bigint, 0),
           (scan ->> 'Heap Fetches')::bigint,
           (plan -> 'Plan' ->> 'Shared Hit Blocks')::bigint + (plan -> 'Plan' ->> 'Shared Read Blocks')::bigint,
           cardinality(times),
           round(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 3)
    FROM unnest(times) AS t;
END
$$;

-- Một phương án: dựng index thử (p_index NULL = chỉ dùng các index sẵn có), đo các câu, bỏ index. Cả hàm chạy trong
-- một câu lệnh nên là một transaction: index thử không bao giờ còn lại trên bảng thật.
CREATE FUNCTION pc_lab.run_variant(p_part text, p_name text, p_index text, p_queries text[][]) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    started timestamptz := clock_timestamp();
BEGIN
    IF p_index IS NOT NULL THEN
        EXECUTE 'CREATE INDEX pc_lab_idx ON public.orders ' || p_index;
    END IF;
    INSERT INTO pc_lab.variant (part, name, size, build_s)
    VALUES (p_part, p_name,
            CASE WHEN p_index IS NOT NULL THEN pg_size_pretty(pg_relation_size(to_regclass('public.pc_lab_idx'))) END,
            CASE WHEN p_index IS NOT NULL THEN round(extract(epoch FROM clock_timestamp() - started)::numeric, 1) END);
    FOR i IN 1..array_length(p_queries, 1) LOOP
        PERFORM pc_lab.measure(p_queries[i][1], p_name, p_queries[i][2]);
    END LOOP;
    IF p_index IS NOT NULL THEN
        EXECUTE 'DROP INDEX public.pc_lab_idx';
    END IF;
END
$$;
\set QUIET off

\timing on
\echo '== VACUUM ANALYZE orders: đơn tạo qua API sau lần VACUUM cuối chưa được đánh dấu all-visible, Index Only Scan'
\echo '   sẽ phải ghé bảng cho chúng (xem mục VACUUM ANALYZE trong README)'
VACUUM (ANALYZE) orders;

-- User nhiều đơn nhất (user 1 với dữ liệu sinh) và một user 10 đơn, gần trung vị
SELECT user_id AS typical FROM orders WHERE user_id >= 40000
GROUP BY user_id HAVING count(*) = 10 ORDER BY user_id LIMIT 1 \gset
SELECT count(*) AS heavy_n FROM orders WHERE user_id = 1 \gset
\echo 'User nhiều đơn: 1 (' :heavy_n 'đơn), user thường:' :typical '(10 đơn)'

\echo
\echo '== A. Partial index cho đơn PENDING'
\set qa '{{"A1. Job: 1.000 đơn PENDING cũ nhất, quá 1 ngày", "SELECT id FROM orders WHERE status = ''PENDING'' AND created_at < now() - interval ''1 day'' ORDER BY created_at LIMIT 1000"}, {"A2. Đếm đơn PENDING quá 1 ngày", "SELECT count(*) FROM orders WHERE status = ''PENDING'' AND created_at < now() - interval ''1 day''"}}'
SELECT pc_lab.run_variant('A', 'A0. chỉ các index sẵn có', NULL, :'qa');
SELECT pc_lab.run_variant('A', 'A1. index thường (status, created_at)', '(status, created_at)', :'qa');
SELECT pc_lab.run_variant('A', 'A2. partial (created_at) WHERE status = ''PENDING''',
                          '(created_at) WHERE status = ''PENDING''', :'qa');

\echo
\echo '== B. Covering index cho tổng tiền theo user'
SELECT format('{{"B1. Tổng tiền đơn COMPLETED, user %s đơn", "SELECT count(*), sum(total_amount) FROM orders WHERE user_id = 1 AND status = ''COMPLETED''"}, {"B2. Tổng tiền đơn COMPLETED, user 10 đơn", "SELECT count(*), sum(total_amount) FROM orders WHERE user_id = %s AND status = ''COMPLETED''"}}',
              :heavy_n, :typical) AS qb \gset
SELECT pc_lab.run_variant('B', 'B0. chỉ index sẵn có (user_id, created_at DESC, id DESC)', NULL, :'qb');
SELECT pc_lab.run_variant('B', 'B1. covering (user_id) INCLUDE (status, total_amount)',
                          '(user_id) INCLUDE (status, total_amount)', :'qb');
SELECT pc_lab.run_variant('B', 'B2. index hiện có thêm INCLUDE (status, total_amount)',
                          '(user_id, created_at DESC, id DESC) INCLUDE (status, total_amount)', :'qb');
\timing off

\echo
\echo '== Index thử (index sẵn có để so: idx_orders_status 67 MB, idx_orders_user_id_created_at 388 MB)'
SELECT name AS "Phương án", coalesce(size, '') AS "Kích thước", build_s AS "Dựng (giây)"
FROM pc_lab.variant ORDER BY id;

\echo '== Kết quả đo (trung vị 5 lần sau một lần chạy trước; câu chậm hơn 1 giây chỉ đo 1 lần)'
SELECT label AS "Câu", variant AS "Phương án", plan AS "Plan", rows_read AS "Dòng đọc", rows_removed AS "Dòng bỏ",
       heap_fetches AS "Heap Fetches", pages AS "Trang", runs AS "Lần đo", ms AS "ms"
FROM pc_lab.result ORDER BY label, id;

\echo
\echo '== Partial index chỉ dùng được khi câu truy vấn chắc chắn nằm trong điều kiện WHERE của index'
BEGIN;
CREATE INDEX pc_lab_pending ON orders (created_at) WHERE status = 'PENDING';
\echo '-- status = PAID: không khớp điều kiện của index'
EXPLAIN (COSTS OFF)
SELECT id FROM orders WHERE status = 'PAID' AND created_at < now() - interval '1 day' ORDER BY created_at LIMIT 1000;
-- JDBC / Hibernate gửi giá trị bằng tham số ($1). PostgreSQL có hai loại plan cho câu có tham số:
PREPARE pending_job(text) AS
    SELECT id FROM orders WHERE status = $1 AND created_at < now() - interval '1 day' ORDER BY created_at LIMIT 1000;
\echo '-- Tham số $1 = PENDING, plan lập riêng cho giá trị này (custom plan): dùng được'
SET LOCAL plan_cache_mode = force_custom_plan;
EXPLAIN (COSTS OFF) EXECUTE pending_job('PENDING');
\echo '-- Cùng câu đó, plan lập một lần cho mọi giá trị (generic plan, PostgreSQL có thể chuyển sang sau 5 lần chạy):'
\echo '-- lúc lập plan chưa biết $1 là PENDING, nên không dùng được'
SET LOCAL plan_cache_mode = force_generic_plan;
EXPLAIN (COSTS OFF) EXECUTE pending_job('PENDING');
DEALLOCATE pending_job;
ROLLBACK;

DROP SCHEMA pc_lab CASCADE;
