-- =====================================================================================================================
-- Thí nghiệm: index ghép (user_id, created_at) so với index đơn (user_id), cho câu "đơn của tôi" của app
--     SELECT ... FROM orders WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 20      (GET /api/orders?userId=)
-- và vài câu liên quan. Đo thêm index app đang dùng, (user_id, created_at DESC, id DESC) của migration V11, làm mốc.
--
-- Chép public.orders (đã sinh bằng scripts/seed-big-data.ps1) sang schema index_lab rồi VACUUM ANALYZE bản chép. Sau đó
-- với từng index: dựng index → đo mọi câu → bỏ index. Mỗi lúc bản chép chỉ có đúng một index, nên planner chỉ có thể
-- dùng index đó hoặc đọc cả bảng. Không đụng bảng thật, app vẫn chạy bình thường. Cần khoảng 1,5 GB đĩa tạm, chạy
-- khoảng 1–2 phút; xong thì xoá schema (thêm -v keep=1 để giữ lại mà xem).
--
-- Chạy:   docker cp scripts/composite-index-lab.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/composite-index-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8
\if :{?keep}
\else
    \set keep 0
\endif

\set QUIET on
SET client_min_messages = warning;
SET max_parallel_workers_per_gather = 0;   -- số dòng, số trang trong EXPLAIN là của cả câu, không bị chia theo worker
SET work_mem = '256MB';                    -- đếm đơn của từng user (để chọn user đo) bằng bảng băm trong RAM

DROP SCHEMA IF EXISTS index_lab CASCADE;
CREATE SCHEMA index_lab;

CREATE TABLE index_lab.variant (
    id         serial PRIMARY KEY,
    name       text,
    size       text,       -- kích thước index
    build_ms   numeric     -- thời gian dựng index trên 10 triệu dòng
);

CREATE TABLE index_lab.result (
    id           serial PRIMARY KEY,
    label        text,
    variant      text,
    plan         text,     -- các bước của plan, từ ngoài vào trong
    rows_read    bigint,   -- số dòng nút đọc bảng trả ra (Index Scan dừng sớm thì chỉ đọc đủ cho LIMIT)
    rows_removed bigint,   -- số dòng đã đọc rồi bỏ vì không khớp điều kiện (Rows Removed by Filter)
    pages        bigint,   -- số trang 8 KB đã đọc (index + bảng)
    ms           numeric   -- trung vị 5 lần chạy, dữ liệu đã nằm sẵn trong bộ nhớ
);

-- Chạy câu một lần cho dữ liệu vào bộ nhớ, rồi EXPLAIN (ANALYZE, BUFFERS) 5 lần: lấy trung vị thời gian chạy, plan của
-- lần cuối. Nút đọc bảng = nút đầu tiên có tên kết thúc bằng "Scan" (.** duyệt cây plan, nút cha trước nút con).
CREATE FUNCTION index_lab.measure(p_label text, p_variant text, p_query text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    plan  jsonb;
    scan  jsonb;
    times numeric[] := '{}';
BEGIN
    EXECUTE p_query;
    FOR i IN 1..5 LOOP
        EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
        plan := plan -> 0;
        times := times || (plan ->> 'Execution Time')::numeric;
    END LOOP;
    scan := jsonb_path_query_first(plan -> 'Plan', 'strict $.** ? (@."Node Type" like_regex "Scan$")');
    INSERT INTO index_lab.result (label, variant, plan, rows_read, rows_removed, pages, ms)
    SELECT p_label, p_variant,
           (SELECT string_agg((n ->> 'Node Type')
                              || CASE WHEN n ->> 'Scan Direction' = 'Backward' THEN ' Backward' ELSE '' END
                              || CASE WHEN n ? 'Sort Method' THEN ' (' || (n ->> 'Sort Method') || ')' ELSE '' END,
                              ' → ' ORDER BY ord)
            FROM jsonb_path_query(plan -> 'Plan', 'strict $.** ? (exists (@."Node Type"))') WITH ORDINALITY AS t(n, ord)),
           (scan ->> 'Actual Rows')::bigint * (scan ->> 'Actual Loops')::bigint,
           coalesce((scan ->> 'Rows Removed by Filter')::bigint, 0),
           (plan -> 'Plan' ->> 'Shared Hit Blocks')::bigint + (plan -> 'Plan' ->> 'Shared Read Blocks')::bigint,
           round(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 3)
    FROM unnest(times) AS t;
END
$$;

-- Dựng một index, đo 4 câu, bỏ index
CREATE FUNCTION index_lab.run_variant(p_name text, p_columns text, p_typical bigint, p_typical_n bigint,
                                      p_heavy bigint, p_heavy_n bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    started timestamptz := clock_timestamp();
    my_orders constant text := 'SELECT * FROM index_lab.orders WHERE user_id = %s '
                               'ORDER BY created_at DESC, id DESC LIMIT 20';
BEGIN
    EXECUTE format('CREATE INDEX lab_idx ON index_lab.orders (%s)', p_columns);
    INSERT INTO index_lab.variant (name, size, build_ms)
    VALUES (p_name, pg_size_pretty(pg_relation_size(to_regclass('index_lab.lab_idx'))),
            round(extract(epoch FROM clock_timestamp() - started)::numeric * 1000));

    PERFORM index_lab.measure(format('1. Đơn của tôi, trang đầu: user %s đơn', p_typical_n), p_name,
                              format(my_orders, p_typical));
    PERFORM index_lab.measure(format('2. Đơn của tôi, trang đầu: user %s đơn', p_heavy_n), p_name,
                              format(my_orders, p_heavy));
    PERFORM index_lab.measure(format('3. Đơn 30 ngày gần nhất: user %s đơn', p_heavy_n), p_name,
                              format('SELECT * FROM index_lab.orders WHERE user_id = %s '
                                     'AND created_at >= now() - interval ''30 days'' '
                                     'ORDER BY created_at DESC, id DESC', p_heavy));
    PERFORM index_lab.measure(format('4. Đếm đơn (phân trang): user %s đơn', p_heavy_n), p_name,
                              format('SELECT count(*) FROM index_lab.orders WHERE user_id = %s', p_heavy));

    EXECUTE 'DROP INDEX index_lab.lab_idx';
END
$$;
\set QUIET off

\timing on
\echo '== Chép orders sang index_lab.orders, VACUUM ANALYZE bản chép'
CREATE TABLE index_lab.orders AS SELECT * FROM public.orders;
VACUUM ANALYZE index_lab.orders;

\echo '== Chọn user: một user 10 đơn (gần trung vị) và user nhiều đơn nhất'
WITH per_user AS (
    SELECT user_id, count(*) AS n FROM index_lab.orders GROUP BY user_id
)
SELECT (SELECT user_id FROM per_user WHERE n = 10 ORDER BY user_id LIMIT 1) AS typical,
       10 AS typical_n,
       heavy.user_id AS heavy,
       heavy.n AS heavy_n
FROM (SELECT user_id, n FROM per_user ORDER BY n DESC, user_id LIMIT 1) AS heavy \gset
\echo 'user thường:' :typical '(' :typical_n 'đơn), user nhiều đơn nhất:' :heavy '(' :heavy_n 'đơn)'

\echo '== A. Index đơn (user_id)'
SELECT index_lab.run_variant('A. (user_id)', 'user_id', :typical, :typical_n, :heavy, :heavy_n);
\echo '== B. Index ghép (user_id, created_at)'
SELECT index_lab.run_variant('B. (user_id, created_at)', 'user_id, created_at', :typical, :typical_n, :heavy, :heavy_n);
\echo '== C. Index app đang dùng (user_id, created_at DESC, id DESC)'
SELECT index_lab.run_variant('C. (user_id, created_at DESC, id DESC)', 'user_id, created_at DESC, id DESC',
                             :typical, :typical_n, :heavy, :heavy_n);
\timing off

\echo
\echo '== Index'
SELECT name AS "Index", size AS "Kích thước", round(build_ms / 1000, 1) AS "Dựng (giây)"
FROM index_lab.variant ORDER BY id;

\echo '== Kết quả đo (dữ liệu đã nằm sẵn trong bộ nhớ, thời gian là trung vị 5 lần)'
SELECT label AS "Câu", variant AS "Index", plan AS "Plan", rows_read AS "Dòng đọc",
       rows_removed AS "Dòng bỏ", pages AS "Trang", ms AS "ms"
FROM index_lab.result
ORDER BY label, variant;

\if :keep
    \echo 'Giữ schema index_lab (xoá: DROP SCHEMA index_lab CASCADE;)'
\else
    DROP SCHEMA index_lab CASCADE;
\endif
