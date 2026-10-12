-- =====================================================================================================================
-- Thí nghiệm: phân trang OFFSET so với phân trang cursor (keyset) trên danh sách đơn, mới nhất trước, 20 đơn mỗi trang.
--
-- OFFSET (cách cũ, Pageable của Spring Data):
--     SELECT ... FROM orders ORDER BY created_at DESC, id DESC OFFSET (trang - 1) * 20 LIMIT 20
--   PostgreSQL không nhảy được tới dòng thứ 199.980: phải đọc lần lượt 199.980 dòng đầu rồi bỏ. Trang càng sau càng
--   chậm. Page của Spring Data còn chạy thêm SELECT count(*) để có tổng số đơn, số trang (mỗi request, kể cả trang 1).
-- Cursor (cách mới, GET /api/orders):
--     SELECT ... FROM orders WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 21
--   (?, ?) là created_at, id của đơn cuối trang trước. So sánh bộ là điều kiện trên đúng các cột của index nên
--   PostgreSQL đi thẳng tới vị trí đó trong index rồi đọc 21 dòng: trang 10.000 như trang 1.
-- Cách viết OR của cùng điều kiện:
--     WHERE created_at < ? OR (created_at = ? AND id < ?)
--   cùng kết quả, nhưng PostgreSQL không dùng OR làm điểm bắt đầu quét index: đọc từ đầu rồi lọc, chậm như OFFSET.
--
-- Chỉ đọc bảng orders (cần dữ liệu lớn: scripts/seed-big-data.ps1); kết quả đo ghi vào schema page_lab, xoá khi xong.
-- Chạy:   docker cp scripts/pagination-lab.sql shoplab-postgres:/tmp/
--         docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/pagination-lab.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8

\set QUIET on
SET client_min_messages = warning;

DROP SCHEMA IF EXISTS page_lab CASCADE;
CREATE SCHEMA page_lab;

CREATE TABLE page_lab.result (
    id        serial PRIMARY KEY,
    list      text,      -- danh sách: mọi đơn / đơn của một người dùng
    page      int,       -- trang số (20 đơn mỗi trang)
    method    text,      -- OFFSET / cursor (so sánh bộ) / cursor viết OR
    plan      text,      -- các bước của plan, từ ngoài vào trong
    rows_read bigint,    -- số dòng nút đọc index / bảng đã đọc (kể cả dòng bị lọc bỏ)
    pages     bigint,    -- số trang 8 KB đã đọc (index + bảng)
    ms        numeric    -- trung vị các lần đo
);

-- EXPLAIN (ANALYZE, BUFFERS) một lần (đưa dữ liệu vào bộ nhớ), rồi đo thêm 5 lần lấy trung vị (câu chậm hơn 1 giây chỉ
-- đo 1 lần). TIMING OFF: không đo từng nút, thời gian gần với khi chạy thật.
CREATE FUNCTION page_lab.measure(p_list text, p_page int, p_method text, p_query text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    plan  jsonb;
    scan  jsonb;
    times numeric[] := '{}';
BEGIN
    EXECUTE 'EXPLAIN (ANALYZE, TIMING OFF, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
    plan := plan -> 0;
    IF (plan ->> 'Execution Time')::numeric < 1000 THEN
        FOR i IN 1..5 LOOP
            EXECUTE 'EXPLAIN (ANALYZE, TIMING OFF, BUFFERS, FORMAT JSON) ' || p_query INTO plan;
            plan := plan -> 0;
            times := times || (plan ->> 'Execution Time')::numeric;
        END LOOP;
    ELSE
        times := ARRAY[(plan ->> 'Execution Time')::numeric];
    END IF;
    scan := jsonb_path_query_first(plan -> 'Plan', 'strict $.** ? (@."Node Type" like_regex "Scan$")');
    INSERT INTO page_lab.result (list, page, method, plan, rows_read, pages, ms)
    SELECT p_list, p_page, p_method,
           (SELECT string_agg((n ->> 'Node Type')
                              || CASE WHEN n ? 'Index Name' THEN ' ' || (n ->> 'Index Name') ELSE '' END,
                              ' → ' ORDER BY ord)
            FROM jsonb_path_query(plan -> 'Plan', 'strict $.** ? (exists (@."Node Type"))') WITH ORDINALITY AS t(n, ord)),
           ((scan ->> 'Actual Rows')::numeric * (scan ->> 'Actual Loops')::numeric)::bigint
               + coalesce((scan ->> 'Rows Removed by Filter')::bigint, 0),
           (plan -> 'Plan' ->> 'Shared Hit Blocks')::bigint + (plan -> 'Plan' ->> 'Shared Read Blocks')::bigint,
           round(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 3)
    FROM unnest(times) AS t;
END
$$;

-- Một trang của một danh sách (p_where: điều kiện lọc danh sách, vd 'user_id = 1'; 'true' là mọi đơn): đo OFFSET, cursor,
-- cursor viết OR. Cursor của trang N là (created_at, id) của đơn cuối trang N - 1, tức nextCursor mà API trả ở trang
-- N - 1; ở đây lấy bằng một câu OFFSET (không đo). Kiểm tra luôn: ba cách trả cùng 20 đơn.
CREATE FUNCTION page_lab.run_page(p_list text, p_where text, p_page int) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    cols     constant text := 'SELECT id, user_id, status, total_amount, created_at FROM orders WHERE ';
    ord      constant text := ' ORDER BY created_at DESC, id DESC ';
    c_at     timestamptz;
    c_id     bigint;
    q_offset text;
    q_cursor text;
    q_or     text;
    ids      bigint[];
BEGIN
    q_offset := format('%s%s%sOFFSET %s LIMIT 20', cols, p_where, ord, (p_page - 1) * 20);
    IF p_page = 1 THEN
        q_cursor := format('%s%s%sLIMIT 21', cols, p_where, ord);
        q_or := q_cursor;
    ELSE
        EXECUTE format('SELECT created_at, id FROM orders WHERE %s%sOFFSET %s LIMIT 1', p_where, ord, (p_page - 1) * 20 - 1)
            INTO c_at, c_id;
        q_cursor := format('%s%s AND (created_at, id) < (%L, %s)%sLIMIT 21', cols, p_where, c_at, c_id, ord);
        q_or := format('%s%s AND (created_at < %L OR (created_at = %L AND id < %s))%sLIMIT 21',
                       cols, p_where, c_at, c_at, c_id, ord);
    END IF;

    EXECUTE format('SELECT array_agg(id) FROM (%s) t', q_offset) INTO ids;
    IF ids IS DISTINCT FROM (SELECT array_agg(id) FROM (SELECT id FROM page_lab.ids(q_cursor)) t)
       OR ids IS DISTINCT FROM (SELECT array_agg(id) FROM (SELECT id FROM page_lab.ids(q_or)) t) THEN
        RAISE EXCEPTION 'Trang % (%): OFFSET và cursor trả về các đơn khác nhau', p_page, p_list;
    END IF;

    PERFORM page_lab.measure(p_list, p_page, 'OFFSET', q_offset);
    PERFORM page_lab.measure(p_list, p_page, 'cursor (so sánh bộ)', q_cursor);
    IF p_page > 1 THEN
        PERFORM page_lab.measure(p_list, p_page, 'cursor viết OR', q_or);
    END IF;
END
$$;

-- 20 id đầu của một câu cursor (câu lấy 21 dòng: dòng thứ 21 chỉ để biết còn trang sau)
CREATE FUNCTION page_lab.ids(p_query text) RETURNS TABLE (id bigint)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format('SELECT id FROM (%s) t LIMIT 20', p_query);
END
$$;
\set QUIET off

SELECT count(*) AS total FROM orders \gset
SELECT count(*) AS heavy_n FROM orders WHERE user_id = 1 \gset
\echo 'Bảng orders:' :total 'đơn. User 1 (nhiều đơn nhất):' :heavy_n 'đơn'
\echo 'Mỗi trang: đo OFFSET, cursor, cursor viết OR; kiểm tra ba cách trả cùng 20 đơn (khác nhau thì dừng với lỗi)'
SELECT :total >= 2000000 AS big_enough \gset
\if :big_enough
\else
    \echo 'Cần ít nhất 2 triệu đơn (trang 100.000). Nạp dữ liệu lớn trước: scripts/seed-big-data.ps1'
    \quit
\endif

\timing on
\echo
\echo '== A. Mọi đơn, trang 1, 10, 100, 1.000, 10.000, 100.000 (vài chục giây)'
SELECT page_lab.run_page('A. mọi đơn', 'true', p) FROM unnest(ARRAY[1, 10, 100, 1000, 10000, 100000]) AS p \g /dev/null

\echo '== B. Đơn của user 1, trang 1, 10, 50 và trang đủ 20 đơn cuối cùng'
SELECT page_lab.run_page('B. user 1', 'user_id = 1', p) FROM unnest(ARRAY[1, 10, 50, :heavy_n / 20]) AS p \g /dev/null

\echo '== C. Câu đếm Page của Spring Data chạy kèm mỗi trang OFFSET (cursor không cần)'
SELECT page_lab.measure('A. mọi đơn', NULL, 'count(*)', 'SELECT count(*) FROM orders') \g /dev/null
SELECT page_lab.measure('B. user 1', NULL, 'count(*)', 'SELECT count(*) FROM orders WHERE user_id = 1') \g /dev/null
\timing off

\echo
\echo '== Kết quả đo (20 đơn mỗi trang; trung vị 5 lần sau một lần chạy trước; câu chậm hơn 1 giây chỉ đo 1 lần)'
SELECT list AS "Danh sách", coalesce(replace(to_char(page, 'FM999G999'), ',', '.'), '') AS "Trang", method AS "Cách",
       plan AS "Plan", rows_read AS "Dòng đọc", pages AS "Trang 8 KB", ms AS "ms"
FROM page_lab.result ORDER BY list, page NULLS LAST, id;

\echo '== Trang N so với trang 1 (OFFSET cộng câu đếm: như Page của Spring Data)'
SELECT r.list AS "Danh sách", replace(to_char(r.page, 'FM999G999'), ',', '.') AS "Trang",
       o.ms AS "OFFSET ms", round(o.ms + c.ms, 1) AS "OFFSET + count(*) ms",
       r.ms AS "cursor ms", coalesce(w.ms::text, '') AS "cursor viết OR ms",
       round(o.ms / nullif(r.ms, 0)) AS "OFFSET / cursor"
FROM page_lab.result r
JOIN page_lab.result o ON o.list = r.list AND o.page = r.page AND o.method = 'OFFSET'
JOIN page_lab.result c ON c.list = r.list AND c.method = 'count(*)'
LEFT JOIN page_lab.result w ON w.list = r.list AND w.page = r.page AND w.method = 'cursor viết OR'
WHERE r.method = 'cursor (so sánh bộ)'
ORDER BY r.list, r.page;

DROP SCHEMA page_lab CASCADE;
