-- =====================================================================================================================
-- Thí nghiệm: viết lại DATE(created_at) = <ngày> thành khoảng thời gian
--     created_at >= '<ngày> 00:00+07' AND created_at < '<ngày hôm sau> 00:00+07'
--
-- DATE(created_at) bọc cột trong một hàm. Index trên created_at xếp theo giá trị created_at, không theo DATE(created_at),
-- nên PostgreSQL không dùng index đó để tìm đúng chỗ được: phải đọc mọi dòng, tính DATE() cho từng dòng rồi so sánh.
-- Khoảng thời gian thì so thẳng trên cột, index đi thẳng tới đầu khoảng và đọc tới cuối khoảng.
-- Khoảng nửa mở: >= đầu ngày, < đầu ngày hôm sau. Không dùng BETWEEN ... AND '... 23:59:59' (bỏ sót 23:59:59.5,
-- còn BETWEEN tới 00:00 hôm sau thì lấy thừa đơn đúng nửa đêm).
--
-- created_at là timestamptz: "ngày" phụ thuộc múi giờ. DATE(created_at) lấy múi giờ của phiên làm việc (app và psql ở
-- đây là UTC), còn ngày của shop là ngày giờ Việt Nam (+07). Khoảng thời gian ghi rõ +07 nên không phụ thuộc phiên.
--
-- Chạy trên bảng thật (chỉ đọc, dùng các index sẵn có), cần dữ liệu lớn: scripts/seed-big-data.ps1. Chạy khoảng 1 phút.
--   docker cp scripts/date-range-lab.sql shoplab-postgres:/tmp/
--   docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/date-range-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8

\set QUIET on
SET client_min_messages = warning;
SET max_parallel_workers_per_gather = 0;   -- số dòng, số trang trong EXPLAIN là của cả câu, không bị chia theo worker
SET TimeZone = 'Asia/Ho_Chi_Minh';         -- phần đo: DATE() cũng tính theo ngày Việt Nam, hai cách chọn đúng cùng các đơn

DROP SCHEMA IF EXISTS date_lab CASCADE;
CREATE SCHEMA date_lab;

CREATE TABLE date_lab.result (
    id           serial PRIMARY KEY,
    label        text,
    form         text,     -- cách viết điều kiện
    plan         text,     -- các bước của plan, từ ngoài vào trong
    rows_matched bigint,   -- số dòng khớp điều kiện
    rows_removed bigint,   -- số dòng đã đọc rồi bỏ vì không khớp (Rows Removed by Filter)
    pages        bigint,   -- số trang 8 KB đã đọc (index + bảng)
    ms           numeric   -- trung vị 5 lần chạy, dữ liệu đã nằm sẵn trong bộ nhớ
);

-- Như scripts/composite-index-lab.sql: chạy câu một lần, rồi EXPLAIN (ANALYZE, BUFFERS) 5 lần, lấy trung vị thời gian
-- và plan của lần cuối. Nút đọc bảng = nút đầu tiên có tên kết thúc bằng "Scan".
CREATE FUNCTION date_lab.measure(p_label text, p_form text, p_query text) RETURNS void
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
    INSERT INTO date_lab.result (label, form, plan, rows_matched, rows_removed, pages, ms)
    SELECT p_label, p_form,
           (SELECT string_agg((n ->> 'Node Type')
                              || CASE WHEN n ->> 'Scan Direction' = 'Backward' THEN ' Backward' ELSE '' END
                              || CASE WHEN n ? 'Index Name' THEN ' ' || (n ->> 'Index Name') ELSE '' END,
                              ' → ' ORDER BY ord)
            FROM jsonb_path_query(plan -> 'Plan', 'strict $.** ? (exists (@."Node Type"))') WITH ORDINALITY AS t(n, ord)),
           (scan ->> 'Actual Rows')::bigint * (scan ->> 'Actual Loops')::bigint,
           coalesce((scan ->> 'Rows Removed by Filter')::bigint, 0),
           (plan -> 'Plan' ->> 'Shared Hit Blocks')::bigint + (plan -> 'Plan' ->> 'Shared Read Blocks')::bigint,
           round(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 3)
    FROM unnest(times) AS t;
END
$$;

-- Thử tạo index biểu thức; trả về 'tạo được' hoặc thông báo lỗi
CREATE FUNCTION date_lab.try_index(p_expression text) RETURNS text
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE INDEX ON date_lab.probe ((%s))', p_expression);
    RETURN 'tạo được';
EXCEPTION WHEN OTHERS THEN
    RETURN 'LỖI: ' || SQLERRM;
END
$$;
CREATE TABLE date_lab.probe (created_at timestamptz);   -- bảng rỗng, chỉ để thử định nghĩa index

-- Ngày đo: 30 ngày trước, theo giờ Việt Nam. Khoảng nửa mở của ngày đó, ghi rõ múi giờ +07.
SELECT d AS day,
       format('%s 00:00+07', d) AS day_start,
       format('%s 00:00+07', d + 1) AS day_end
FROM (SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 30 AS d) AS x \gset
SELECT count(*) AS user1_orders FROM orders WHERE user_id = 1 \gset
\set QUIET off

\echo 'Ngày đo:' :day '(giờ Việt Nam), khoảng [' :day_start ',' :day_end ')'
\echo

\echo '== 1. Đếm đơn trong ngày (vd báo cáo doanh số ngày)'
SELECT date_lab.measure('1. Đếm đơn trong ngày', 'DATE(created_at) = ngày',
                        format('SELECT count(*) FROM orders WHERE DATE(created_at) = %L', :'day'));
SELECT date_lab.measure('1. Đếm đơn trong ngày', 'khoảng [đầu ngày, đầu ngày sau)',
                        format('SELECT count(*) FROM orders WHERE created_at >= %L AND created_at < %L',
                               :'day_start', :'day_end'));

\echo '== 2. Đơn trong ngày của user 1 (' :user1_orders 'đơn tất cả)'
SELECT date_lab.measure(format('2. Đơn trong ngày của user 1 (%s đơn)', :user1_orders), 'DATE(created_at) = ngày',
                        format('SELECT * FROM orders WHERE user_id = 1 AND DATE(created_at) = %L '
                               'ORDER BY created_at DESC, id DESC', :'day'));
SELECT date_lab.measure(format('2. Đơn trong ngày của user 1 (%s đơn)', :user1_orders), 'khoảng [đầu ngày, đầu ngày sau)',
                        format('SELECT * FROM orders WHERE user_id = 1 AND created_at >= %L AND created_at < %L '
                               'ORDER BY created_at DESC, id DESC', :'day_start', :'day_end'));

\echo
\echo '== Kết quả đo (dữ liệu đã nằm sẵn trong bộ nhớ, thời gian là trung vị 5 lần)'
SELECT label AS "Câu", form AS "Cách viết", plan AS "Plan", rows_matched AS "Dòng khớp",
       rows_removed AS "Dòng bỏ", pages AS "Trang", ms AS "ms"
FROM date_lab.result
ORDER BY id;

\echo '== Cùng một câu DATE(created_at) = ngày, phiên khác múi giờ thì ra tập đơn khác'
-- Phiên của app và psql ở đây mặc định UTC (docker-compose đặt timezone=UTC). Ngày Việt Nam bắt đầu lúc 17:00 UTC
-- hôm trước, nên hai "ngày" chỉ trùng nhau 17 tiếng.
SET TimeZone = 'UTC';
SELECT 'DATE(created_at) = ngày, phiên UTC (mặc định của app)' AS "Cách viết",
       count(*) AS "Số đơn",
       count(*) FILTER (WHERE NOT (created_at >= :'day_start' AND created_at < :'day_end')) AS "Không thuộc ngày Việt Nam"
FROM orders WHERE DATE(created_at) = :'day'
UNION ALL
SELECT 'khoảng [' || :'day_start' || ', ' || :'day_end' || '), phiên UTC',
       count(*), 0
FROM orders WHERE created_at >= :'day_start' AND created_at < :'day_end';
SET TimeZone = 'Asia/Ho_Chi_Minh';

\echo '== Index trên DATE(created_at) thì sao?'
-- DATE() của timestamptz phụ thuộc múi giờ của phiên (hàm STABLE, không IMMUTABLE), nên không dùng làm index được.
-- Ghi rõ múi giờ thì biểu thức không còn phụ thuộc phiên, tạo index được, nhưng là thêm một index nữa cho bảng.
SELECT 'DATE(created_at)' AS "Index biểu thức", date_lab.try_index('DATE(created_at)') AS "Kết quả"
UNION ALL
SELECT '(created_at AT TIME ZONE ''Asia/Ho_Chi_Minh'')::date',
       date_lab.try_index('(created_at AT TIME ZONE ''Asia/Ho_Chi_Minh'')::date');

DROP SCHEMA date_lab CASCADE;
