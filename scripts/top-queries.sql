-- =====================================================================================================================
-- Các câu SQL tốn TỔNG thời gian chạy nhiều nhất trong DB này, theo pg_stat_statements: cộng dồn từ lần
-- pg_stat_statements_reset() gần nhất (scripts/top-queries.ps1 reset ngay trước khi gọi API).
-- pg_stat_statements gộp các câu chỉ khác tham số thành một dòng ($1, $2... thay cho giá trị).
--
-- Chạy riêng (vd sau khi tự gọi API bằng Postman):
--   docker cp scripts/top-queries.sql shoplab-postgres:/tmp/
--   docker exec shoplab-postgres psql -U shoplab -d shoplab -v top=3 -f /tmp/top-queries.sql
-- =====================================================================================================================

\set ON_ERROR_STOP on
\encoding UTF8
\if :{?top}
\else
    \set top 3
\endif

SELECT count(*) AS "Số câu SQL khác nhau",
       sum(calls) AS "Tổng số lần chạy",
       round(sum(total_exec_time)::numeric) AS "Tổng thời gian chạy (ms)"
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT LIKE '%pg_stat_statements%';  -- bỏ câu reset và chính các câu báo cáo này

\x on
SELECT row_number() OVER (ORDER BY total_exec_time DESC) AS "Hạng",
       round(total_exec_time::numeric) AS "Tổng thời gian (ms)",
       round((100 * total_exec_time / sum(total_exec_time) OVER ())::numeric, 1) AS "% tổng thời gian DB",
       calls AS "Số lần chạy",
       round(mean_exec_time::numeric, 3) AS "Trung bình (ms)",
       round(max_exec_time::numeric, 1) AS "Lâu nhất (ms)",
       round(rows::numeric / calls, 1) AS "Dòng / lần",
       round((shared_blks_hit + shared_blks_read)::numeric / calls, 1) AS "Trang đọc / lần",
       shared_blks_read AS "Trang phải đọc từ đĩa",
       round(shared_blk_read_time::numeric) AS "Chờ đọc đĩa (ms)",
       query AS "Câu SQL"
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT LIKE '%pg_stat_statements%'   -- bỏ câu reset và chính các câu báo cáo này
ORDER BY total_exec_time DESC
LIMIT :top;
\x off
