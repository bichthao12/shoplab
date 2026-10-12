<#
.SYNOPSIS
  Đo một truy vấn trước và sau khi có index sửa nó, theo cách của sổ đo: EXPLAIN (ANALYZE, BUFFERS), chạy 3 lần, ghi
  lần cuối. In sẵn hai số để nhập vào sổ (dấu phẩy thập phân).

.DESCRIPTION
  Chỉ chạy SQL qua docker exec psql (không gọi API, không chạy test). Không đổi DB: trạng thái "trước" có được bằng cách
  bỏ index sửa truy vấn TRONG một transaction rồi ROLLBACK, nên index trở lại nguyên vẹn, không phải dựng lại. Lỗi
  hay Ctrl+C giữa chừng thì kết nối đóng và PostgreSQL tự rollback.
  Trong mấy giây đo phần "trước", bảng bị khoá: request của app (nếu đang chạy) phải chờ. Nếu một transaction khác
  đang dùng bảng, script chờ tối đa 5 giây rồi báo lỗi thay vì treo.

  Các thẻ (-Case):
    user-orders  Đơn mới nhất của một user
                 SELECT * FROM orders WHERE user_id = :uid ORDER BY created_at DESC LIMIT 20
                 Cách sửa: index ghép (user_id, created_at): idx_orders_user_id_created_at
                 Kế hoạch: Seq Scan và Sort → Index Scan, không còn bước Sort
                 :uid mặc định là một user có khoảng 10 đơn. User nhiều đơn (vd user 1) thì phần "trước" không phải
                 Seq Scan: PostgreSQL đi theo index created_at từ đơn mới nhất và lọc user_id, sớm gặp đủ 20 đơn.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đo xong và plan đúng như thẻ, 1 nếu plan khác thẻ, 2 nếu không chạy được.

.PARAMETER Case
  Thẻ cần đo (mặc định user-orders).

.PARAMETER UserId
  :uid cho thẻ user-orders. Bỏ trống: tự chọn một user có 8–12 đơn.

.PARAMETER Runs
  Số lần chạy mỗi truy vấn (mặc định 3, sổ ghi lần cuối).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1 -UserId 1
#>
param(
    [ValidateSet('user-orders')][string]$Case = 'user-orders',
    [long]$UserId = 0,
    [ValidateRange(1, 20)][int]$Runs = 3
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')
$vi = [System.Globalization.CultureInfo]::GetCultureInfo('vi-VN')

function Invoke-Docker([string[]]$dockerArgs) {
    # stderr của lệnh ngoài thành ErrorRecord khi gộp 2>&1; tạm đổi ErrorActionPreference để PS 5.1 không dừng script
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @dockerArgs 2>&1 | ForEach-Object { "$_" }
        return @{ ExitCode = $LASTEXITCODE; Lines = @($output) }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

function Invoke-Sql([string]$sql) {
    $result = Invoke-Docker ($psql + @('-tAc', $sql))
    if ($result.ExitCode -ne 0) {
        Stop-Script "Lỗi khi chạy SQL: $sql`n  $($result.Lines -join "`n  ")"
    }
    return @($result.Lines | Where-Object { $_ -ne '' })
}

# Số cho sổ: dấu phẩy thập phân, không phân cách hàng nghìn; càng nhỏ càng nhiều chữ số sau dấu phẩy
function Format-Ms([double]$ms) {
    if ($ms -ge 10) { return $ms.ToString('0.0', $vi) }
    if ($ms -ge 1) { return $ms.ToString('0.00', $vi) }
    return $ms.ToString('0.000', $vi)
}

# Các nút của plan, từ ngoài vào trong
function Get-PlanNodes($node) {
    $node
    foreach ($child in @($node.Plans)) {
        if ($child) { Get-PlanNodes $child }
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Thẻ cần đo

$health = Invoke-Docker @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}

if ($Case -eq 'user-orders') {
    if ($UserId -le 0) {
        # User ở khoảng giữa dải id: thường có quanh 10 đơn (user lâu năm nhiều đơn hơn, user mới ít hơn)
        $picked = @(Invoke-Sql ('SELECT user_id FROM orders WHERE user_id >= (SELECT max(user_id) / 2 FROM orders) ' +
                                'GROUP BY user_id HAVING count(*) BETWEEN 8 AND 12 ORDER BY user_id LIMIT 1'))
        if ($picked.Count -eq 0) {
            Stop-Script 'Không tìm được user nào có 8–12 đơn. Nạp dữ liệu: scripts/seed-big-data.ps1, hoặc chọn -UserId.'
        }
        $UserId = [long]$picked[0]
    }
    $orderCount = @(Invoke-Sql "SELECT count(*) FROM orders WHERE user_id = $UserId")[0]
    $card = @{
        Title = 'Đơn mới nhất của một user'
        Query = "SELECT * FROM orders WHERE user_id = $UserId ORDER BY created_at DESC LIMIT 20"
        Fix   = 'index ghép (user_id, created_at)'
        Index = 'idx_orders_user_id_created_at'
        Note  = "user $UserId có $orderCount đơn"
        # Plan mong đợi theo thẻ: trước Seq Scan + Sort; sau Index Scan bằng index ghép, không còn Sort
        BeforeOk = { param($nodes) [bool]($nodes | Where-Object { $_.'Node Type' -like '*Seq Scan' }) -and
                                   [bool]($nodes | Where-Object { $_.'Node Type' -like '*Sort' }) }
        AfterOk  = { param($nodes) [bool]($nodes | Where-Object { $_.'Index Name' -eq 'idx_orders_user_id_created_at' }) -and
                                   -not ($nodes | Where-Object { $_.'Node Type' -like '*Sort' }) }
    }
}

$exists = @(Invoke-Sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '$($card.Index)'")[0]
if ($exists -ne '1') {
    Stop-Script "Không thấy index $($card.Index) trong DB: chưa chạy app lần nào (Flyway tạo index), hoặc index đã bị xoá."
}

Write-Host "Thẻ: $($card.Title) ($($card.Note))"
Write-Host "  $($card.Query)"
Write-Host "  Cách sửa: $($card.Fix) = $($card.Index)"
Write-Host "  Mỗi truy vấn chạy $Runs lần bằng EXPLAIN (ANALYZE, BUFFERS), ghi lần cuối"
Write-Host ''

# ---------------------------------------------------------------------------------------------------------------
# 2. Đo trong MỘT phiên psql: "trước" trong transaction có DROP INDEX rồi ROLLBACK, "sau" ngoài transaction.
# Hàm tạm trả kết quả EXPLAIN dạng JSON trên một dòng để đọc lại.

$explain = 'CREATE FUNCTION pg_temp.explain_json(q text) RETURNS text LANGUAGE plpgsql AS $f$ ' +
           'DECLARE p json; BEGIN EXECUTE ''EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '' || q INTO p; ' +
           'RETURN p::jsonb::text; END $f$'
$call = "SELECT 'before|' || pg_temp.explain_json('$($card.Query)')"
$sessionArgs = @('-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-c', $explain, '-c', "SET lock_timeout = '5s'", '-c', 'BEGIN',
                 '-c', "DROP INDEX public.$($card.Index)")
for ($i = 0; $i -lt $Runs; $i++) { $sessionArgs += @('-c', $call) }
$sessionArgs += @('-c', 'ROLLBACK')
for ($i = 0; $i -lt $Runs; $i++) { $sessionArgs += @('-c', $call.Replace("'before|'", "'after|'")) }

$result = Invoke-Docker ($psql + $sessionArgs)
if ($result.ExitCode -ne 0) {
    Stop-Script ("Đo không được (index vẫn nguyên: lỗi thì PostgreSQL tự rollback):`n  $($result.Lines -join "`n  ")" +
                 "`n  Gặp 'lock timeout': đang có transaction khác dùng bảng, tắt app hoặc chạy lại sau.")
}
if (@(Invoke-Sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '$($card.Index)'")[0] -ne '1') {
    Stop-Script "SAI: sau khi đo không còn thấy index $($card.Index)!"
}

$measured = foreach ($line in ($result.Lines | Where-Object { $_ -match '^(before|after)\|' })) {
    $phase, $json = $line -split '\|', 2
    $explainResult = @($json | ConvertFrom-Json)[0]
    $nodes = @(Get-PlanNodes $explainResult.Plan)
    $scan = $nodes | Where-Object { $_.'Node Type' -like '*Scan' } | Select-Object -First 1
    [pscustomobject]@{
        Phase = $phase
        Ms    = [double]$explainResult.'Execution Time'
        Plan  = (($nodes | ForEach-Object {
                    $n = $_.'Node Type'
                    if ($_.'Parallel Aware' -and $n -like '*Scan') { $n = "Parallel $n" }
                    if ($_.'Index Name') { $n += " using $($_.'Index Name')" }
                    $n
                }) -join ' → ')
        # Nút chạy song song: số dòng là trung bình mỗi tiến trình, nhân số lần chạy (loops) ra tổng
        Rows  = ([long]$scan.'Actual Rows' + [long]$scan.'Rows Removed by Filter') * [long]$scan.'Actual Loops'
        Pages = [long]$explainResult.Plan.'Shared Hit Blocks' + [long]$explainResult.Plan.'Shared Read Blocks'
        Nodes = $nodes
    }
}
$before = @($measured | Where-Object { $_.Phase -eq 'before' })
$after = @($measured | Where-Object { $_.Phase -eq 'after' })
if ($before.Count -ne $Runs -or $after.Count -ne $Runs) {
    Stop-Script "Không đọc được kết quả EXPLAIN:`n  $($result.Lines -join "`n  ")"
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Kết quả

$table = for ($i = 0; $i -lt $Runs; $i++) {
    [pscustomobject]@{
        'Lần'          = $i + 1
        'Trước (ms)'   = Format-Ms $before[$i].Ms
        'Trước: trang' = $before[$i].Pages
        'Sau (ms)'     = Format-Ms $after[$i].Ms
        'Sau: trang'   = $after[$i].Pages
    }
}
$table | Format-Table -AutoSize | Out-String -Width 120 | Write-Host

$last = $Runs - 1
Write-Host "Plan trước: $($before[$last].Plan)"
Write-Host ("            đọc {0} dòng (kể cả dòng lọc bỏ), {1} trang 8 KB" -f $before[$last].Rows, $before[$last].Pages)
Write-Host "Plan sau:   $($after[$last].Plan)"
Write-Host ("            đọc {0} dòng, {1} trang 8 KB" -f $after[$last].Rows, $after[$last].Pages)
Write-Host ''
Write-Host 'Nhập vào sổ đo (lần cuối, dấu phẩy thập phân):' -ForegroundColor Cyan
Write-Host ("  TRƯỚC: {0}" -f (Format-Ms $before[$last].Ms)) -ForegroundColor Cyan
Write-Host ("  SAU:   {0}" -f (Format-Ms $after[$last].Ms)) -ForegroundColor Cyan
Write-Host ''

$beforeOk = & $card.BeforeOk $before[$last].Nodes
$afterOk = & $card.AfterOk $after[$last].Nodes
if ($beforeOk -and $afterOk) {
    Write-Host ("ĐÚNG: plan khớp thẻ, nhanh hơn {0} lần" -f [Math]::Round($before[$last].Ms / [Math]::Max($after[$last].Ms, 0.001))) -ForegroundColor Green
    exit 0
}
if (-not $beforeOk) {
    Write-Host 'KHÁC THẺ: plan "trước" không phải Seq Scan + Sort như thẻ ghi (user nhiều đơn? thử -UserId khác).' -ForegroundColor Yellow
}
if (-not $afterOk) {
    Write-Host ('KHÁC THẺ: plan "sau" không phải Index Scan bằng {0}, không có Sort.' -f $card.Index) -ForegroundColor Yellow
}
exit 1
