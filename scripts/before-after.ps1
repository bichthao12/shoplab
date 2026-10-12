<#
.SYNOPSIS
  Đo các thẻ của sổ đo trước / sau: mỗi truy vấn chạy 3 lần bằng EXPLAIN (ANALYZE, BUFFERS), ghi lần cuối. In sẵn số
  để nhập vào sổ (dấu phẩy thập phân) và kiểm tra plan có đúng như thẻ ghi không.

.DESCRIPTION
  Chỉ chạy SQL qua docker exec psql (không gọi API, không chạy test). Không đổi DB. Thẻ cần trạng thái "chưa có index"
  thì index đó bị bỏ TRONG một transaction rồi ROLLBACK: index trở lại nguyên vẹn, không phải dựng lại; lỗi hay Ctrl+C
  giữa chừng thì kết nối đóng và PostgreSQL tự rollback. Trong mấy giây đo phần đó, bảng bị khoá (request của app phải
  chờ); nếu một transaction khác đang dùng bảng, script chờ tối đa 5 giây rồi báo lỗi thay vì treo.

  Các thẻ (-Case, mặc định all: đo mọi thẻ rồi in bảng tóm tắt):
    user-orders  Đơn mới nhất của một user
                 SELECT * FROM orders WHERE user_id = :uid ORDER BY created_at DESC LIMIT 20
                 Cách sửa: index ghép (user_id, created_at) = idx_orders_user_id_created_at. "Trước": bỏ index đó.
                 Kế hoạch: Seq Scan và Sort → Index Scan, không còn bước Sort
                 :uid mặc định là một user có 8–12 đơn (-UserId để chọn). User nhiều đơn (vd user 1) thì phần "trước"
                 không phải Seq Scan: PostgreSQL đi theo index created_at từ đơn mới nhất, sớm gặp đủ 20 đơn.
    day-orders   Đơn trong một ngày
                 trước: SELECT * FROM orders WHERE DATE(created_at) = :day
                 sau:   SELECT * FROM orders WHERE created_at >= :day AND created_at < :day + 1
                 Cách sửa: viết thành khoảng ngày, dùng index idx_orders_created_at có sẵn. Hai câu chạy trên cùng DB.
                 Kế hoạch: Seq Scan → Index Scan hoặc Bitmap Scan
                 :day mặc định 2026-10-01 (-Day để chọn). Script kiểm tra hai câu trả cùng số đơn. Ngày tính theo
                 TimeZone của phiên (UTC trong docker compose), cho cả DATE() lẫn khoảng ngày.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đo xong và mọi plan đúng như thẻ, 1 nếu có plan khác thẻ, 2 nếu không chạy được.

.PARAMETER Case
  Thẻ cần đo: all (mặc định), user-orders, day-orders.

.PARAMETER UserId
  :uid cho thẻ user-orders. Bỏ trống: tự chọn một user có 8–12 đơn.

.PARAMETER Day
  :day cho thẻ day-orders (yyyy-MM-dd, mặc định 2026-10-01).

.PARAMETER Runs
  Số lần chạy mỗi truy vấn (mặc định 3, sổ ghi lần cuối).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1 -Case day-orders -Day 2026-09-15
#>
param(
    [ValidateSet('all', 'user-orders', 'day-orders')][string]$Case = 'all',
    [long]$UserId = 0,
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$Day = '2026-10-01',
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

function Test-IndexExists([string]$name) {
    return @(Invoke-Sql "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '$name'")[0] -eq '1'
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Các thẻ. Before / After: câu đo; DropIndex: index bỏ (trong transaction) cho phần "trước", trống nếu hai câu
# chạy trên cùng DB; BeforeOk / AfterOk: plan mong đợi theo thẻ (nhận danh sách nút của plan).

function New-UserOrdersCard {
    $uid = $UserId
    if ($uid -le 0) {
        # User ở khoảng giữa dải id: thường có quanh 10 đơn (user lâu năm nhiều đơn hơn, user mới ít hơn)
        $picked = @(Invoke-Sql ('SELECT user_id FROM orders WHERE user_id >= (SELECT max(user_id) / 2 FROM orders) ' +
                                'GROUP BY user_id HAVING count(*) BETWEEN 8 AND 12 ORDER BY user_id LIMIT 1'))
        if ($picked.Count -eq 0) {
            Stop-Script 'Không tìm được user nào có 8–12 đơn. Nạp dữ liệu: scripts/seed-big-data.ps1, hoặc chọn -UserId.'
        }
        $uid = [long]$picked[0]
    }
    $orderCount = @(Invoke-Sql "SELECT count(*) FROM orders WHERE user_id = $uid")[0]
    $query = "SELECT * FROM orders WHERE user_id = $uid ORDER BY created_at DESC LIMIT 20"
    return @{
        Title     = 'Đơn mới nhất của một user'
        Note      = "user $uid có $orderCount đơn"
        Before    = $query
        After     = $query
        DropIndex = 'idx_orders_user_id_created_at'
        Fix       = 'index ghép (user_id, created_at) = idx_orders_user_id_created_at; "trước" = bỏ index đó'
        Expect    = 'Seq Scan và Sort → Index Scan, không còn bước Sort'
        BeforeOk  = { param($nodes) [bool]($nodes | Where-Object { $_.'Node Type' -like '*Seq Scan' }) -and
                                    [bool]($nodes | Where-Object { $_.'Node Type' -like '*Sort' }) }
        AfterOk   = { param($nodes) [bool]($nodes | Where-Object { $_.'Index Name' -eq 'idx_orders_user_id_created_at' }) -and
                                    -not ($nodes | Where-Object { $_.'Node Type' -like '*Sort' }) }
        Hint      = 'user nhiều đơn gần đây? chạy lại không kèm -UserId, hoặc chọn user khác'
    }
}

function New-DayOrdersCard {
    $next = ([datetime]::ParseExact($Day, 'yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture)).AddDays(1).ToString('yyyy-MM-dd')
    $before = "SELECT * FROM orders WHERE DATE(created_at) = '$Day'"
    $after = "SELECT * FROM orders WHERE created_at >= '$Day' AND created_at < '$next'"
    # Viết lại không được đổi kết quả: hai câu phải trả cùng số đơn
    $counts = @(Invoke-Sql ("SELECT (SELECT count(*) FROM ($before) b) || '|' || (SELECT count(*) FROM ($after) a)"))[0] -split '\|'
    if ($counts[0] -ne $counts[1]) {
        Stop-Script "SAI: DATE(created_at) = '$Day' trả $($counts[0]) đơn, khoảng ngày trả $($counts[1]) đơn."
    }
    if ($counts[0] -eq '0') {
        Write-Host "  Ngày $Day không có đơn nào: chọn ngày khác bằng -Day (dữ liệu sinh rải trong 2 năm gần nhất)." -ForegroundColor Yellow
    }
    return @{
        Title     = 'Đơn trong một ngày'
        Note      = "ngày $Day có $($counts[0]) đơn, hai cách viết trả cùng số đơn"
        Before    = $before
        After     = $after
        DropIndex = ''
        Fix       = 'viết thành khoảng ngày, dùng index idx_orders_created_at có sẵn'
        Expect    = 'Seq Scan → Index Scan hoặc Bitmap Scan'
        BeforeOk  = { param($nodes) [bool]($nodes | Where-Object { $_.'Node Type' -like '*Seq Scan' }) }
        AfterOk   = { param($nodes) [bool]($nodes | Where-Object { $_.'Index Name' -eq 'idx_orders_created_at' }) -and
                                    -not ($nodes | Where-Object { $_.'Node Type' -like '*Seq Scan' }) }
        Hint      = ''
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 2. Đo một thẻ trong MỘT phiên psql: phần "trước" (trong transaction có DROP INDEX rồi ROLLBACK nếu thẻ cần), rồi
# phần "sau". Hàm tạm trả kết quả EXPLAIN dạng JSON trên một dòng để đọc lại.

function Measure-Card($card) {
    Write-Host "== $($card.Title) ($($card.Note))" -ForegroundColor Cyan
    if ($card.Before -eq $card.After) {
        Write-Host "  $($card.Before)"
    } else {
        Write-Host "  trước: $($card.Before)"
        Write-Host "  sau:   $($card.After)"
    }
    Write-Host "  Cách sửa: $($card.Fix)"
    Write-Host "  Kế hoạch theo thẻ: $($card.Expect)"

    if ($card.DropIndex -and -not (Test-IndexExists $card.DropIndex)) {
        Stop-Script "Không thấy index $($card.DropIndex): chưa chạy app lần nào (Flyway tạo index), hoặc index đã bị xoá."
    }

    $explain = 'CREATE FUNCTION pg_temp.explain_json(q text) RETURNS text LANGUAGE plpgsql AS $f$ ' +
               'DECLARE p json; BEGIN EXECUTE ''EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '' || q INTO p; ' +
               'RETURN p::jsonb::text; END $f$'
    $beforeCall = "SELECT 'before|' || pg_temp.explain_json('$($card.Before.Replace("'", "''"))')"
    $afterCall = "SELECT 'after|' || pg_temp.explain_json('$($card.After.Replace("'", "''"))')"
    $sessionArgs = @('-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-c', $explain)
    if ($card.DropIndex) {
        $sessionArgs += @('-c', "SET lock_timeout = '5s'", '-c', 'BEGIN', '-c', "DROP INDEX public.$($card.DropIndex)")
    }
    for ($i = 0; $i -lt $Runs; $i++) { $sessionArgs += @('-c', $beforeCall) }
    if ($card.DropIndex) { $sessionArgs += @('-c', 'ROLLBACK') }
    for ($i = 0; $i -lt $Runs; $i++) { $sessionArgs += @('-c', $afterCall) }

    $result = Invoke-Docker ($psql + $sessionArgs)
    if ($result.ExitCode -ne 0) {
        Stop-Script ("Đo không được (DB không đổi: lỗi thì PostgreSQL tự rollback):`n  $($result.Lines -join "`n  ")" +
                     "`n  Gặp 'lock timeout': đang có transaction khác dùng bảng, tắt app hoặc chạy lại sau.")
    }
    if ($card.DropIndex -and -not (Test-IndexExists $card.DropIndex)) {
        Stop-Script "SAI: sau khi đo không còn thấy index $($card.DropIndex)!"
    }

    $measured = foreach ($line in ($result.Lines | Where-Object { $_ -match '^(before|after)\|' })) {
        $phase, $json = $line -split '\|', 2
        $explainResult = @($json | ConvertFrom-Json)[0]
        $nodes = @(Get-PlanNodes $explainResult.Plan)
        $scans = @($nodes | Where-Object { $_.'Node Type' -like '*Scan' -and $_.'Node Type' -ne 'Bitmap Index Scan' })
        [pscustomobject]@{
            Phase = $phase
            Ms    = [double]$explainResult.'Execution Time'
            Plan  = (($nodes | ForEach-Object {
                        $n = $_.'Node Type'
                        if ($_.'Parallel Aware' -and $n -like '*Scan') { $n = "Parallel $n" }
                        if ($_.'Index Name') { $n += " using $($_.'Index Name')" }
                        $n
                    }) -join ' → ')
            # Nút đọc bảng / index: dòng trả ra + dòng đọc rồi lọc bỏ. Nút chạy song song: số dòng là trung bình mỗi
            # tiến trình, nhân số lần chạy (loops) ra tổng
            Rows  = ($scans | ForEach-Object {
                        ([long]$_.'Actual Rows' + [long]$_.'Rows Removed by Filter' + [long]$_.'Rows Removed by Index Recheck') * [long]$_.'Actual Loops'
                    } | Measure-Object -Sum).Sum
            Pages = [long]$explainResult.Plan.'Shared Hit Blocks' + [long]$explainResult.Plan.'Shared Read Blocks'
            Nodes = $nodes
        }
    }
    $before = @($measured | Where-Object { $_.Phase -eq 'before' })
    $after = @($measured | Where-Object { $_.Phase -eq 'after' })
    if ($before.Count -ne $Runs -or $after.Count -ne $Runs) {
        Stop-Script "Không đọc được kết quả EXPLAIN:`n  $($result.Lines -join "`n  ")"
    }

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
    Write-Host "  Plan trước: $($before[$last].Plan)"
    Write-Host ("              đọc {0} dòng (kể cả dòng lọc bỏ), {1} trang 8 KB" -f $before[$last].Rows, $before[$last].Pages)
    Write-Host "  Plan sau:   $($after[$last].Plan)"
    Write-Host ("              đọc {0} dòng, {1} trang 8 KB" -f $after[$last].Rows, $after[$last].Pages)

    $beforeOk = & $card.BeforeOk $before[$last].Nodes
    $afterOk = & $card.AfterOk $after[$last].Nodes
    $verdict = 'khớp thẻ'
    if (-not ($beforeOk -and $afterOk)) {
        $verdict = 'KHÁC THẺ'
        $hint = ''
        if ($card.Hint) { $hint = " ($($card.Hint))" }
        if (-not $beforeOk) { Write-Host ('  KHÁC THẺ: plan "trước" không như thẻ ghi{0}.' -f $hint) -ForegroundColor Yellow }
        if (-not $afterOk) { Write-Host '  KHÁC THẺ: plan "sau" không như thẻ ghi.' -ForegroundColor Yellow }
    }
    Write-Host ("  Nhập vào sổ: TRƯỚC {0}   SAU {1}   ({2})" -f (Format-Ms $before[$last].Ms), (Format-Ms $after[$last].Ms), $verdict) -ForegroundColor Cyan
    Write-Host ''
    return [pscustomobject]@{
        'Thẻ'   = $card.Title
        'TRƯỚC' = Format-Ms $before[$last].Ms
        'SAU'   = Format-Ms $after[$last].Ms
        'Plan'  = $verdict
        Ok      = ($beforeOk -and $afterOk)
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Đo các thẻ được chọn

$health = Invoke-Docker @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}
Write-Host "Mỗi truy vấn chạy $Runs lần bằng EXPLAIN (ANALYZE, BUFFERS), sổ ghi lần cuối."
Write-Host ''

$summary = @()
if ($Case -in @('all', 'user-orders')) { $summary += Measure-Card (New-UserOrdersCard) }
if ($Case -in @('all', 'day-orders')) { $summary += Measure-Card (New-DayOrdersCard) }

Write-Host 'Nhập vào sổ đo (lần cuối, dấu phẩy thập phân):' -ForegroundColor Cyan
$summary | Select-Object 'Thẻ', 'TRƯỚC', 'SAU', 'Plan' | Format-Table -AutoSize | Out-String -Width 120 | Write-Host

if (@($summary | Where-Object { -not $_.Ok }).Count -eq 0) {
    Write-Host 'ĐÚNG: plan của mọi thẻ khớp thẻ' -ForegroundColor Green
    exit 0
}
Write-Host 'Có thẻ plan khác thẻ ghi: xem dòng KHÁC THẺ ở trên' -ForegroundColor Yellow
exit 1
