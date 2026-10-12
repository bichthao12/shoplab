<#
.SYNOPSIS
  Đo các thẻ của sổ đo trước / sau: mỗi truy vấn chạy 3 lần bằng EXPLAIN (ANALYZE, BUFFERS), ghi lần cuối. In sẵn số
  để nhập vào sổ (dấu phẩy thập phân) và kiểm tra plan có đúng như thẻ ghi không.

.DESCRIPTION
  Chỉ chạy SQL qua docker exec psql (không gọi API, không chạy test). Không đổi DB. Trạng thái nào cần bỏ hay thêm
  index thì làm việc đó TRONG một transaction rồi ROLLBACK: index trở lại đúng như cũ, không phải dựng lại; lỗi hay
  Ctrl+C giữa chừng thì kết nối đóng và PostgreSQL tự rollback. Trong mấy giây đo phần đó, bảng bị khoá (request của
  app phải chờ); nếu một transaction khác đang dùng bảng, script chờ tối đa 5 giây rồi báo lỗi thay vì treo.

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
    pending      Đơn đang chờ xử lý
                 SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at LIMIT 50
                 Cách sửa: partial index ON orders (created_at) WHERE status = 'PENDING'. "Sau": tạo index đó.
                 Kế hoạch: Seq Scan → Index Scan trên index nhỏ chỉ chứa đơn PENDING
                 "Trước" như thẻ: không index nào dùng được cho câu này, tức bỏ idx_orders_created_at và
                 idx_orders_status. Script đo thêm "DB hiện tại" (còn hai index đó, chưa có partial index) để tham khảo:
                 dữ liệu sinh rải đơn PENDING đều trong 2 năm, nên đi theo idx_orders_created_at từ đơn cũ nhất sớm gặp
                 đủ 50 đơn PENDING; đơn PENDING thật thường dồn về gần đây, cách đó sẽ phải đọc gần hết đơn cũ.
    user-total   Tổng tiền đã mua của một user
                 SELECT sum(total_amount) FROM orders WHERE user_id = :uid   (thẻ ghi cột total, bảng của app tên
                 total_amount)
                 Cách sửa: thêm INCLUDE (total_amount) vào index của user_id. "Sau": dựng lại
                 idx_orders_user_id_created_at với cùng các cột khoá, thêm INCLUDE (total_amount) (trong transaction).
                 Kế hoạch: Index Scan → Index Only Scan, Heap Fetches gần 0
                 :uid mặc định là user nhiều đơn nhất: lợi ích nằm ở đó, vì mỗi đơn của một user nằm ở một trang khác
                 nhau của bảng. User vài đơn thì trước / sau gần như bằng nhau. "Trước" với user nhiều đơn thường là
                 Bitmap Heap Scan thay vì Index Scan: cũng là đọc index rồi ghé bảng, script chấp nhận cả hai. Heap
                 Fetches chỉ gần 0 khi VACUUM đã đánh dấu các trang all-visible; nhiều thì chạy VACUUM orders.
    page-10000   Trang 10.000 của danh sách đơn (20 đơn mỗi trang)
                 trước: SELECT * FROM orders ORDER BY created_at DESC, id DESC OFFSET 199980 LIMIT 20
                 sau:   SELECT * FROM orders WHERE (created_at, id) < (:lastCreatedAt, :lastId)
                        ORDER BY created_at DESC, id DESC LIMIT 20
                 Cách sửa: phân trang cursor (GET /api/orders), index (created_at, id)
                 Kế hoạch: đọc rồi bỏ gần 200.000 dòng → đọc đúng 20 dòng, nhanh như trang 1
                 Cursor = (created_at, id) của đơn thứ 199.980 (đơn cuối trang 9.999, đúng nextCursor API trả ở trang
                 9.999). Script kiểm tra hai câu trả cùng 20 đơn. "Sau" chạy trên DB hiện tại, như app: app không có
                 index (created_at, id), dùng idx_orders_created_at (created_at DESC) có sẵn. Script đo thêm, để tham
                 khảo, với index (created_at DESC, id DESC) như thẻ ghi (tạo trong transaction).

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đo xong và mọi plan đúng như thẻ, 1 nếu có plan khác thẻ, 2 nếu không chạy được.

.PARAMETER Case
  Thẻ cần đo: all (mặc định), user-orders, day-orders, pending, user-total, page-10000.

.PARAMETER UserId
  :uid cho thẻ user-orders và user-total. Bỏ trống: mỗi thẻ tự chọn (user-orders: một user có 8–12 đơn; user-total:
  user nhiều đơn nhất).

.PARAMETER Day
  :day cho thẻ day-orders (yyyy-MM-dd, mặc định 2026-10-01).

.PARAMETER Runs
  Số lần chạy mỗi truy vấn (mặc định 3, sổ ghi lần cuối).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\before-after.ps1 -Case pending
#>
param(
    [ValidateSet('all', 'user-orders', 'day-orders', 'pending', 'user-total', 'page-10000')][string]$Case = 'all',
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

function Test-HasNode($nodes, [string]$pattern) {
    return [bool]($nodes | Where-Object { $_.'Node Type' -like $pattern })
}

function Test-UsesIndex($nodes, [string]$index) {
    return [bool]($nodes | Where-Object { $_.'Index Name' -eq $index })
}

# Số dòng các nút đọc bảng / index đã đọc: dòng trả ra + dòng đọc rồi lọc bỏ. Nút chạy song song: số dòng là trung bình
# mỗi tiến trình, nhân số lần chạy (loops) ra tổng
function Get-RowsRead($nodes) {
    $scans = @($nodes | Where-Object { $_.'Node Type' -like '*Scan' -and $_.'Node Type' -ne 'Bitmap Index Scan' })
    return [long](($scans | ForEach-Object {
        ([long]$_.'Actual Rows' + [long]$_.'Rows Removed by Filter' + [long]$_.'Rows Removed by Index Recheck') * [long]$_.'Actual Loops'
    } | Measure-Object -Sum).Sum)
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Các thẻ. Mỗi thẻ có các pha: before (số "trước" của sổ), after (số "sau"), và có thể thêm extra (đo thêm để tham
# khảo, không nhập vào sổ). Pha có Setup thì chạy trong transaction: Setup, đo, ROLLBACK. Info: câu SQL in thêm thông tin, chạy
# cuối pha (trước ROLLBACK). MustExist / MustNotExist: trạng thái index sau khi đo, kiểm tra DB không đổi.

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
        Title        = 'Đơn mới nhất của một user'
        Note         = "user $uid có $orderCount đơn"
        Fix          = 'index ghép (user_id, created_at) = idx_orders_user_id_created_at'
        Expect       = 'Seq Scan và Sort → Index Scan, không còn bước Sort'
        Phases       = @(
            @{ Key = 'before'; Label = 'Trước'; Query = $query; Setup = @('DROP INDEX public.idx_orders_user_id_created_at')
               Describe = 'bỏ idx_orders_user_id_created_at (trong transaction)' }
            @{ Key = 'after'; Label = 'Sau'; Query = $query; Setup = @(); Describe = 'DB hiện tại, có index ghép' }
        )
        MustExist    = @('idx_orders_user_id_created_at')
        MustNotExist = @()
        BeforeOk     = { param($n) (Test-HasNode $n '*Seq Scan') -and (Test-HasNode $n '*Sort') }
        AfterOk      = { param($n) (Test-UsesIndex $n 'idx_orders_user_id_created_at') -and -not (Test-HasNode $n '*Sort') }
        Hint         = 'user nhiều đơn gần đây? chạy lại không kèm -UserId, hoặc chọn user khác'
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
        Title        = 'Đơn trong một ngày'
        Note         = "ngày $Day có $($counts[0]) đơn, hai cách viết trả cùng số đơn"
        Fix          = 'viết thành khoảng ngày, dùng index idx_orders_created_at có sẵn'
        Expect       = 'Seq Scan → Index Scan hoặc Bitmap Scan'
        Phases       = @(
            @{ Key = 'before'; Label = 'Trước'; Query = $before; Setup = @(); Describe = 'DATE(created_at) = ngày' }
            @{ Key = 'after'; Label = 'Sau'; Query = $after; Setup = @(); Describe = 'khoảng ngày nửa mở' }
        )
        MustExist    = @()
        MustNotExist = @()
        BeforeOk     = { param($n) Test-HasNode $n '*Seq Scan' }
        AfterOk      = { param($n) (Test-UsesIndex $n 'idx_orders_created_at') -and -not (Test-HasNode $n '*Seq Scan') }
        Hint         = ''
    }
}

function New-PendingCard {
    $partial = 'idx_orders_pending_created_at'
    if (Test-IndexExists $partial) {
        Stop-Script "Index $partial đã có trong DB: bỏ nó trước (DROP INDEX $partial) để đo được trạng thái trước."
    }
    $counts = @(Invoke-Sql "SELECT count(*) FILTER (WHERE status = 'PENDING') || '|' || count(*) FROM orders")[0] -split '\|'
    $query = "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at LIMIT 50"
    return @{
        Title        = 'Đơn đang chờ xử lý'
        Note         = "$($counts[0]) đơn PENDING trên $($counts[1]) đơn"
        Fix          = "partial index ON orders (created_at) WHERE status = 'PENDING' (tạm đặt tên $partial)"
        Expect       = 'Seq Scan → Index Scan trên index nhỏ chỉ chứa đơn PENDING'
        Phases       = @(
            @{ Key = 'before'; Label = 'Trước'; Query = $query
               Setup = @('DROP INDEX public.idx_orders_created_at', 'DROP INDEX public.idx_orders_status')
               Describe = 'như thẻ: không index nào dùng được (bỏ idx_orders_created_at, idx_orders_status trong transaction)' }
            @{ Key = 'after'; Label = 'Sau'; Query = $query
               Setup = @("CREATE INDEX $partial ON orders (created_at) WHERE status = 'PENDING'")
               Info = "SELECT 'info|Partial index: ' || pg_size_pretty(pg_relation_size('$partial')) || ', chỉ chứa đơn PENDING'"
               Describe = 'tạo partial index (trong transaction)' }
            @{ Key = 'extra'; Label = 'DB hiện tại'; Query = $query; Setup = @()
               Describe = 'để tham khảo: còn idx_orders_created_at, idx_orders_status, chưa có partial index' }
        )
        MustExist    = @('idx_orders_created_at', 'idx_orders_status')
        MustNotExist = @($partial)
        BeforeOk     = { param($n) (Test-HasNode $n '*Seq Scan') -and (Test-HasNode $n '*Sort') }
        AfterOk      = { param($n) (Test-UsesIndex $n 'idx_orders_pending_created_at') -and -not (Test-HasNode $n '*Sort') -and
                                   -not (Test-HasNode $n '*Seq Scan') }
        Hint         = ''
    }
}

function New-UserTotalCard {
    $uid = $UserId
    if ($uid -le 0) {
        # Dữ liệu sinh dồn đơn về các user id nhỏ (user lâu năm): user nhiều đơn nhất nằm trong 100 user đầu
        $picked = @(Invoke-Sql ('SELECT user_id FROM orders WHERE user_id <= 100 GROUP BY user_id ' +
                                'ORDER BY count(*) DESC, user_id LIMIT 1'))
        if ($picked.Count -eq 0) {
            $picked = @(Invoke-Sql 'SELECT user_id FROM orders WHERE user_id IS NOT NULL GROUP BY user_id ORDER BY count(*) DESC LIMIT 1')
        }
        if ($picked.Count -eq 0) { Stop-Script 'Bảng orders chưa có đơn nào. Nạp dữ liệu: scripts/seed-big-data.ps1' }
        $uid = [long]$picked[0]
    }
    $index = 'idx_orders_user_id_created_at'
    if (-not (Test-IndexExists $index)) {
        Stop-Script "Không thấy index ${index}: chưa chạy app lần nào (Flyway tạo index), hoặc index đã bị xoá."
    }
    $def = @(Invoke-Sql "SELECT pg_get_indexdef('public.$index'::regclass)")[0]
    if ($def -match 'INCLUDE') {
        Stop-Script "Index $index đã có INCLUDE ($def): trả nó về như migration V11 rồi đo lại."
    }
    $info = @(Invoke-Sql ("SELECT count(*) || '|' || pg_size_pretty(pg_relation_size('public.$index')) " +
                          "FROM orders WHERE user_id = $uid"))[0] -split '\|'
    return @{
        Title        = 'Tổng tiền đã mua của một user'
        Note         = "user $uid có $($info[0]) đơn; thẻ ghi cột total, bảng của app tên total_amount"
        Fix          = "thêm INCLUDE (total_amount) vào $index (dựng lại cùng các cột khoá)"
        Expect       = 'Index Scan → Index Only Scan, Heap Fetches gần 0'
        Phases       = @(
            @{ Key = 'before'; Label = 'Trước'; Query = "SELECT sum(total_amount) FROM orders WHERE user_id = $uid"; Setup = @()
               Describe = "DB hiện tại: $index ($($info[1])), không có total_amount" }
            @{ Key = 'after'; Label = 'Sau'; Query = "SELECT sum(total_amount) FROM orders WHERE user_id = $uid"
               Setup = @("DROP INDEX public.$index", "$def INCLUDE (total_amount)")
               Info = "SELECT 'info|Index $index với INCLUDE (total_amount): ' || pg_size_pretty(pg_relation_size('public.$index')) || ' (trước đó $($info[1]))'"
               Describe = 'dựng lại index với INCLUDE (total_amount) (trong transaction, vài giây)' }
        )
        MustExist    = @($index)
        MustNotExist = @()
        SameDef      = @{ $index = $def }
        # Trước: đọc index rồi ghé bảng (Index Scan hoặc Bitmap Heap Scan); sau: Index Only Scan, ghé bảng gần như 0 lần
        BeforeOk     = { param($n) ((Test-UsesIndex $n 'idx_orders_user_id_created_at') -or (Test-HasNode $n 'Bitmap Heap Scan')) -and
                                   -not (Test-HasNode $n 'Index Only Scan') }
        AfterOk      = { param($n)
                         $only = @($n | Where-Object { $_.'Node Type' -eq 'Index Only Scan' -and $_.'Index Name' -eq 'idx_orders_user_id_created_at' })
                         $only.Count -gt 0 -and [long]$only[0].'Heap Fetches' -le [Math]::Max(10, [long]$only[0].'Actual Rows' / 20) }
        Hint         = ''
        AfterHint    = 'Heap Fetches nhiều: VACUUM chưa đánh dấu all-visible các trang có đơn mới, chạy VACUUM orders rồi đo lại'
    }
}

function New-Page10000Card {
    $size = 20
    $offset = (10000 - 1) * $size
    $order = 'ORDER BY created_at DESC, id DESC'
    $total = [long]@(Invoke-Sql 'SELECT count(*) FROM orders')[0]
    if ($total -lt $offset + $size) {
        Stop-Script "Cần ít nhất $($offset + $size) đơn cho trang 10.000 (đang có $total). Nạp dữ liệu: scripts/seed-big-data.ps1"
    }
    $extraIndex = 'idx_orders_created_at_id'
    if (Test-IndexExists $extraIndex) {
        Stop-Script "Index $extraIndex đã có trong DB: bỏ nó trước (DROP INDEX $extraIndex) để đo như app."
    }
    # Cursor của trang 10.000 = vị trí đơn cuối trang 9.999 (đúng nextCursor API trả ở trang 9.999)
    $cursor = @(Invoke-Sql "SELECT created_at::text || '|' || id FROM orders $order OFFSET $($offset - 1) LIMIT 1")[0] -split '\|'
    $before = "SELECT * FROM orders $order OFFSET $offset LIMIT $size"
    $after = "SELECT * FROM orders WHERE (created_at, id) < ('$($cursor[0])', $($cursor[1])) $order LIMIT $size"
    # Hai cách phải trả cùng 20 đơn, cùng thứ tự
    $same = @(Invoke-Sql ("SELECT (SELECT array_agg(id) FROM ($before) b) = (SELECT array_agg(id) FROM ($after) a)"))[0]
    if ($same -ne 't') {
        Stop-Script 'SAI: OFFSET và cursor trả các đơn khác nhau (có đơn mới đặt giữa hai câu? chạy lại).'
    }
    return @{
        Title        = 'Trang 10.000 của danh sách đơn'
        Note         = "$total đơn; cursor = đơn thứ ${offset}: ($($cursor[0]), $($cursor[1])); hai cách trả cùng 20 đơn"
        Fix          = 'phân trang cursor (GET /api/orders), index (created_at, id)'
        Expect       = 'đọc rồi bỏ gần 200.000 dòng → đọc đúng 20 dòng, nhanh như trang 1'
        Phases       = @(
            @{ Key = 'before'; Label = 'Trước'; Query = $before; Setup = @(); Describe = 'OFFSET' }
            @{ Key = 'after'; Label = 'Sau'; Query = $after; Setup = @()
               Describe = 'cursor, như app: dùng idx_orders_created_at (created_at DESC) có sẵn' }
            @{ Key = 'extra'; Label = 'Index (created_at, id)'; Query = $after
               Setup = @("CREATE INDEX $extraIndex ON orders (created_at DESC, id DESC)")
               Describe = 'cursor, thêm index (created_at DESC, id DESC) như thẻ ghi (trong transaction, vài giây)' }
        )
        MustExist    = @('idx_orders_created_at')
        MustNotExist = @($extraIndex)
        # Trước: đọc gần hết các dòng đứng trước trang; sau: chỉ khoảng 20 dòng
        BeforeOk     = { param($n) (Get-RowsRead $n) -ge 199980 }
        AfterOk      = { param($n) (Get-RowsRead $n) -le 50 }
        Hint         = ''
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 2. Đo một thẻ trong MỘT phiên psql. Hàm tạm trả kết quả EXPLAIN dạng JSON trên một dòng để đọc lại.

function Measure-Card($card) {
    Write-Host "== $($card.Title) ($($card.Note))" -ForegroundColor Cyan
    foreach ($phase in $card.Phases) {
        Write-Host ("  {0}: {1}" -f $phase.Label, $phase.Describe)
        Write-Host "    $($phase.Query)"
    }
    Write-Host "  Cách sửa: $($card.Fix)"
    Write-Host "  Kế hoạch theo thẻ: $($card.Expect)"

    foreach ($name in $card.MustExist) {
        if (-not (Test-IndexExists $name)) {
            Stop-Script "Không thấy index ${name}: chưa chạy app lần nào (Flyway tạo index), hoặc index đã bị xoá."
        }
    }

    $explain = 'CREATE FUNCTION pg_temp.explain_json(q text) RETURNS text LANGUAGE plpgsql AS $f$ ' +
               'DECLARE p json; BEGIN EXECUTE ''EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '' || q INTO p; ' +
               'RETURN p::jsonb::text; END $f$'
    $sessionArgs = @('-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-c', $explain, '-c', "SET lock_timeout = '5s'")
    foreach ($phase in $card.Phases) {
        $call = "SELECT '$($phase.Key)|' || pg_temp.explain_json('$($phase.Query.Replace("'", "''"))')"
        $inTransaction = @($phase.Setup).Count -gt 0
        if ($inTransaction) {
            $sessionArgs += @('-c', 'BEGIN')
            foreach ($sql in $phase.Setup) { $sessionArgs += @('-c', $sql) }
        }
        for ($i = 0; $i -lt $Runs; $i++) { $sessionArgs += @('-c', $call) }
        if ($phase.Info) { $sessionArgs += @('-c', $phase.Info) }
        if ($inTransaction) { $sessionArgs += @('-c', 'ROLLBACK') }
    }

    $result = Invoke-Docker ($psql + $sessionArgs)
    if ($result.ExitCode -ne 0) {
        Stop-Script ("Đo không được (DB không đổi: lỗi thì PostgreSQL tự rollback):`n  $($result.Lines -join "`n  ")" +
                     "`n  Gặp 'lock timeout': đang có transaction khác dùng bảng, tắt app hoặc chạy lại sau.")
    }
    foreach ($name in $card.MustExist) {
        if (-not (Test-IndexExists $name)) { Stop-Script "SAI: sau khi đo không còn thấy index $name!" }
    }
    foreach ($name in $card.MustNotExist) {
        if (Test-IndexExists $name) { Stop-Script "SAI: sau khi đo vẫn còn index $name!" }
    }
    if ($card.SameDef) {
        foreach ($name in $card.SameDef.Keys) {
            $now = @(Invoke-Sql "SELECT pg_get_indexdef('public.$name'::regclass)")[0]
            if ($now -ne $card.SameDef[$name]) { Stop-Script "SAI: index $name đổi định nghĩa sau khi đo: $now" }
        }
    }

    $measured = foreach ($line in ($result.Lines | Where-Object { $_ -match '^[a-z]+\|\[' })) {
        $key, $json = $line -split '\|', 2
        $explainResult = @($json | ConvertFrom-Json)[0]
        $nodes = @(Get-PlanNodes $explainResult.Plan)
        [pscustomobject]@{
            Key   = $key
            Ms    = [double]$explainResult.'Execution Time'
            Plan  = (($nodes | ForEach-Object {
                        $n = $_.'Node Type'
                        if ($_.'Parallel Aware' -and $n -like '*Scan') { $n = "Parallel $n" }
                        if ($_.'Scan Direction' -eq 'Backward') { $n += ' Backward' }
                        if ($_.'Index Name') { $n += " using $($_.'Index Name')" }
                        $n
                    }) -join ' → ')
            Rows  = Get-RowsRead $nodes
            Pages = [long]$explainResult.Plan.'Shared Hit Blocks' + [long]$explainResult.Plan.'Shared Read Blocks'
            # Index Only Scan: số lần vẫn phải ghé bảng (trang chưa all-visible)
            HeapFetches = ($nodes | Where-Object { $_.'Node Type' -eq 'Index Only Scan' } |
                           ForEach-Object { [long]$_.'Heap Fetches' * [long]$_.'Actual Loops' } | Measure-Object -Sum).Sum
            OnlyScan = [bool]($nodes | Where-Object { $_.'Node Type' -eq 'Index Only Scan' })
            Nodes = $nodes
        }
    }
    $byPhase = @{}
    foreach ($phase in $card.Phases) {
        $byPhase[$phase.Key] = @($measured | Where-Object { $_.Key -eq $phase.Key })
        if ($byPhase[$phase.Key].Count -ne $Runs) {
            Stop-Script "Không đọc được kết quả EXPLAIN:`n  $($result.Lines -join "`n  ")"
        }
    }

    $table = for ($i = 0; $i -lt $Runs; $i++) {
        $row = [ordered]@{ 'Lần' = $i + 1 }
        foreach ($phase in $card.Phases) {
            $row["$($phase.Label) (ms)"] = Format-Ms $byPhase[$phase.Key][$i].Ms
            $row["$($phase.Label): trang"] = $byPhase[$phase.Key][$i].Pages
        }
        [pscustomobject]$row
    }
    $table | Format-Table -AutoSize | Out-String -Width 160 | Write-Host

    $last = $Runs - 1
    foreach ($phase in $card.Phases) {
        $m = $byPhase[$phase.Key][$last]
        Write-Host ("  Plan ({0}): {1}" -f $phase.Label, $m.Plan)
        $fetches = ''
        if ($m.OnlyScan) { $fetches = ", Heap Fetches $($m.HeapFetches)" }
        Write-Host ("      đọc {0} dòng (kể cả dòng lọc bỏ), {1} trang 8 KB{2}" -f $m.Rows, $m.Pages, $fetches)
    }
    foreach ($line in ($result.Lines | Where-Object { $_ -like 'info|*' })) {
        Write-Host "  $($line.Substring(5))"
    }

    $before = $byPhase['before'][$last]
    $after = $byPhase['after'][$last]
    $beforeOk = & $card.BeforeOk $before.Nodes
    $afterOk = & $card.AfterOk $after.Nodes
    $verdict = 'khớp thẻ'
    if (-not ($beforeOk -and $afterOk)) {
        $verdict = 'KHÁC THẺ'
        $hint = ''
        if ($card.Hint) { $hint = " ($($card.Hint))" }
        if (-not $beforeOk) { Write-Host ('  KHÁC THẺ: plan "trước" không như thẻ ghi{0}.' -f $hint) -ForegroundColor Yellow }
        $afterHint = ''
        if ($card.AfterHint) { $afterHint = " ($($card.AfterHint))" }
        if (-not $afterOk) { Write-Host ('  KHÁC THẺ: plan "sau" không như thẻ ghi{0}.' -f $afterHint) -ForegroundColor Yellow }
    }
    Write-Host ("  Nhập vào sổ: TRƯỚC {0}   SAU {1}   ({2})" -f (Format-Ms $before.Ms), (Format-Ms $after.Ms), $verdict) -ForegroundColor Cyan
    $extra = ''
    $extraPhase = $card.Phases | Where-Object { $_.Key -eq 'extra' } | Select-Object -First 1
    if ($extraPhase) {
        $extra = "$($extraPhase.Label): $(Format-Ms $byPhase['extra'][$last].Ms)"
        Write-Host "  (Tham khảo, không nhập vào sổ: $extra)"
    }
    Write-Host ''
    return [pscustomobject]@{
        'Thẻ'                  = $card.Title
        'TRƯỚC'                = Format-Ms $before.Ms
        'SAU'                  = Format-Ms $after.Ms
        'Plan'                 = $verdict
        'Tham khảo (không nhập)' = $extra
        Ok                     = ($beforeOk -and $afterOk)
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
if ($Case -in @('all', 'pending')) { $summary += Measure-Card (New-PendingCard) }
if ($Case -in @('all', 'user-total')) { $summary += Measure-Card (New-UserTotalCard) }
if ($Case -in @('all', 'page-10000')) { $summary += Measure-Card (New-Page10000Card) }

Write-Host 'Nhập vào sổ đo (lần cuối, dấu phẩy thập phân):' -ForegroundColor Cyan
$summary | Select-Object 'Thẻ', 'TRƯỚC', 'SAU', 'Plan', 'Tham khảo (không nhập)' | Format-Table -AutoSize |
    Out-String -Width 160 | Write-Host

if (@($summary | Where-Object { -not $_.Ok }).Count -eq 0) {
    Write-Host 'ĐÚNG: plan của mọi thẻ khớp thẻ' -ForegroundColor Green
    exit 0
}
Write-Host 'Có thẻ plan khác thẻ ghi: xem dòng KHÁC THẺ ở trên' -ForegroundColor Yellow
exit 1
