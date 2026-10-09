-- =====================================================================================================================
-- Thí nghiệm VACUUM ANALYZE: cùng một bảng 10 triệu đơn, đo các câu đếm đơn theo trạng thái trước và sau từng lệnh.
--   ANALYZE  ghi thống kê từng cột (pg_stats) → planner ước tính đúng số dòng, chọn đúng cách đọc.
--   VACUUM   dựng visibility map → Index Only Scan lấy kết quả từ index, không phải ghé bảng (Heap Fetches: 0).
--
-- Chép public.orders (đã sinh bằng scripts/seed-big-data.ps1) sang schema vacuum_lab, tắt autovacuum trên bản chép để
-- nó không tự ANALYZE / VACUUM giữa chừng, dựng một index trên status rồi đo. Không đụng bảng thật; xong thì xoá
-- schema (thêm -v keep=1 để giữ lại mà xem). Cần khoảng 1,5 GB đĩa tạm, chạy khoảng 1–2 phút.
--
-- Chạy:   docker cp scripts/vacuum-analyze-lab.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/vacuum-analyze-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8
\if :{?keep}
\else
    \set keep 0
\endif

\set QUIET on
SET client_min_messages = warning;
-- Tắt truy vấn song song: số dòng ước tính / thực tế trong EXPLAIN là của cả câu, không bị chia theo từng worker
SET max_parallel_workers_per_gather = 0;

DROP SCHEMA IF EXISTS vacuum_lab CASCADE;
CREATE SCHEMA vacuum_lab;

CREATE TABLE vacuum_lab.result (
    id           serial PRIMARY KEY,
    step         text,
    label        text,
    node         text,      -- cách đọc bảng (nút scan đầu tiên trong plan)
    estimated    bigint,    -- số dòng planner ước tính nút đó trả ra
    actual       bigint,    -- số dòng thực tế
    heap_fetches bigint,    -- Index Only Scan: số lần vẫn phải ghé bảng vì trang chưa chắc mọi transaction đều thấy
    pages        bigint,    -- số trang 8 KB đã đọc (từ shared buffers hoặc từ đĩa / cache của hệ điều hành)
    dirtied      bigint,    -- số trang câu SELECT tự sửa (ghi hint bit)
    ms           numeric
);

CREATE TABLE vacuum_lab.state (
    step          text,
    stats_columns int,      -- số cột có thống kê trong pg_stats
    status_mcv    text,     -- các giá trị phổ biến nhất của status mà planner biết
    all_visible   numeric   -- % trang của bảng được đánh dấu all-visible trong visibility map
);

-- Chạy EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) rồi ghi lại nút đọc bảng đầu tiên: Seq Scan, Bitmap Heap Scan,
-- Index Only Scan... (.** duyệt cả cây plan, nút cha đứng trước nút con).
-- p_warmup: chạy câu một lần trước khi đo, để lần đo không phải chờ đọc từ đĩa vào bộ nhớ (so thời gian công bằng).
CREATE FUNCTION vacuum_lab.measure(p_step text, p_label text, p_query text, p_warmup boolean DEFAULT true) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    plan jsonb;
    scan jsonb;
BEGIN
    IF p_warmup THEN
        EXECUTE p_query;
    END IF;
    EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
    plan := plan -> 0;
    scan := jsonb_path_query_first(plan -> 'Plan', 'strict $.** ? (@."Node Type" like_regex "Scan$")');
    INSERT INTO vacuum_lab.result (step, label, node, estimated, actual, heap_fetches, pages, dirtied, ms)
    VALUES (p_step, p_label,
            (scan ->> 'Node Type') || coalesce(' ' || (scan ->> 'Index Name'), ''),
            (scan ->> 'Plan Rows')::bigint,
            (scan ->> 'Actual Rows')::bigint * (scan ->> 'Actual Loops')::bigint,
            (scan ->> 'Heap Fetches')::bigint,
            (plan -> 'Plan' ->> 'Shared Hit Blocks')::bigint + (plan -> 'Plan' ->> 'Shared Read Blocks')::bigint,
            (plan -> 'Plan' ->> 'Shared Dirtied Blocks')::bigint,
            round((plan ->> 'Execution Time')::numeric));
END
$$;

CREATE FUNCTION vacuum_lab.snapshot(p_step text) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO vacuum_lab.state
    SELECT p_step,
           (SELECT count(*) FROM pg_stats WHERE schemaname = 'vacuum_lab' AND tablename = 'orders'),
           (SELECT most_common_vals::text FROM pg_stats
            WHERE schemaname = 'vacuum_lab' AND tablename = 'orders' AND attname = 'status'),
           (SELECT round(100.0 * relallvisible / greatest(relpages, 1), 1) FROM pg_class
            WHERE oid = to_regclass('vacuum_lab.orders'));
$$;

CREATE FUNCTION vacuum_lab.measure_all(p_step text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM vacuum_lab.snapshot(p_step);
    PERFORM vacuum_lab.measure(p_step, 'PENDING (2%)',
                               $q$SELECT count(*) FROM vacuum_lab.orders WHERE status = 'PENDING'$q$);
    PERFORM vacuum_lab.measure(p_step, 'COMPLETED (89%)',
                               $q$SELECT count(*) FROM vacuum_lab.orders WHERE status = 'COMPLETED'$q$);
END
$$;
\set QUIET off

\timing on
\echo '== Chép orders sang vacuum_lab.orders'
CREATE TABLE vacuum_lab.orders WITH (autovacuum_enabled = false) AS SELECT * FROM public.orders;
\timing off

-- Dòng vừa nạp chưa có hint bit "transaction tạo ra tôi đã commit": câu đọc đầu tiên phải tra trạng thái transaction
-- rồi tự ghi hint bit vào từng trang. Đo trước khi dựng index, vì dựng index cũng đọc hết bảng và ghi hint bit.
\echo '== 0. Đọc cả bảng hai lần ngay sau khi nạp'
SELECT vacuum_lab.measure('0. vừa nạp', 'cả bảng, lần 1', 'SELECT count(*) FROM vacuum_lab.orders', false);
SELECT vacuum_lab.measure('0. vừa nạp', 'cả bảng, lần 2', 'SELECT count(*) FROM vacuum_lab.orders', false);

\timing on
\echo '== Dựng index trên status'
CREATE INDEX vacuum_lab_orders_status ON vacuum_lab.orders (status);
\timing off

\echo '== 1. Chưa ANALYZE, chưa VACUUM'
SELECT vacuum_lab.measure_all('1. chưa gì');

\echo '== 2. ANALYZE'
ANALYZE vacuum_lab.orders;
SELECT vacuum_lab.measure_all('2. sau ANALYZE');

\echo '== 3. VACUUM'
VACUUM vacuum_lab.orders;
SELECT vacuum_lab.measure_all('3. sau VACUUM');

\echo
\echo '== Planner biết gì về bảng'
SELECT step AS "Bước", stats_columns AS "Cột có thống kê", coalesce(status_mcv, '(chưa có)') AS "Giá trị status phổ biến",
       all_visible AS "% trang all-visible"
FROM vacuum_lab.state;

\echo '== Kết quả đo'
SELECT step AS "Bước", label AS "Câu đếm", node AS "Cách đọc", estimated AS "Ước tính", actual AS "Thực tế",
       heap_fetches AS "Heap Fetches", pages AS "Trang đọc", dirtied AS "Trang tự ghi", ms AS "ms"
FROM vacuum_lab.result
ORDER BY id;

\if :keep
    \echo 'Giữ schema vacuum_lab (xoá: DROP SCHEMA vacuum_lab CASCADE;)'
\else
    DROP SCHEMA vacuum_lab CASCADE;
\endif
