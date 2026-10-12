<#
.SYNOPSIS
  Gọi API danh sách đơn phân trang cursor (GET /api/orders) trên app đang chạy: so sánh trang 1 với trang 10.000, và
  so với cách cũ (OFFSET) đo trong DB.

.DESCRIPTION
  Gọi API thật (không chạy test). Cần app đang chạy và DB có dữ liệu lớn (ít nhất -Page x -Size đơn, mặc định 200.000:
  scripts/seed-big-data.ps1).
    1. Trang -Page (mặc định 10.000) là các đơn thứ 199.981 – 200.000 (mới nhất trước, 20 đơn mỗi trang). Muốn lấy nó
       phải có nextCursor của trang 9.999. Mặc định script lấy (created_at, id) của đơn thứ 199.980 trong DB rồi mã hoá
       như app (base64url của "createdAt,id"): đúng chuỗi API trả ở trang 9.999.
       -Walk: đi thật từ trang 1, theo nextCursor qua từng trang tới trang -Page (10.000 request, vài phút).
    2. Gọi trang 1 và trang -Page, mỗi trang -Runs lần (mặc định 20), lấy trung vị. Kiểm tra trang -Page đúng là các
       đơn của câu OFFSET 199980 LIMIT 20 trong DB.
    3. Trong DB (EXPLAIN ANALYZE, trung vị 5 lần): câu của app cho trang 1 và trang -Page, câu OFFSET của cách cũ, và
       câu count(*) mà Page của Spring Data chạy kèm mỗi trang. Đếm số dòng mỗi câu phải đọc.
  Đừng đặt đơn trong lúc chạy: đơn mới đứng đầu danh sách, đẩy đơn thứ 200.000 của câu OFFSET đi chỗ khác.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu trang -Page đúng đơn và câu của nó đọc không quá 50 dòng (như trang 1), 1 nếu không, 2 nếu không
  chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cursor-pagination.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cursor-pagination.ps1 -Walk
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(2, 1000000)][int]$Page = 10000,
    [ValidateRange(1, 100)][int]$Size = 20,
    [ValidateRange(1, 1000)][int]$Runs = 20,
    [switch]$Walk
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')
$maxRowsRead = 50
$skip = ($Page - 1) * $Size                  # số đơn đứng trước trang -Page
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
    return $result.Lines
}

function Format-Number($value, [string]$format = 'N0') {
    return ([double]$value).ToString($format, $vi)
}

function Get-Median([double[]]$values) {
    $sorted = @($values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)

# GET một trang, trả về @{ Ms = thời gian; Ids = id các đơn; Next = nextCursor }
function Get-OrderPage([string]$cursor) {
    $path = "/api/orders?size=$Size"
    if ($cursor) { $path += "&cursor=$cursor" }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $watch.Stop()
    if ([int]$response.StatusCode -ne 200) {
        Stop-Script "GET $path -> $([int]$response.StatusCode): $body"
    }
    $json = $body | ConvertFrom-Json
    return @{ Ms = $watch.Elapsed.TotalMilliseconds; Ids = @($json.content | ForEach-Object { [long]$_.id }); Next = $json.nextCursor }
}

Write-Host "Phân trang cursor: trang 1 và trang $(Format-Number $Page) của GET /api/orders, $Size đơn mỗi trang"
Write-Host ''

# ---------------------------------------------------------------------------------------------------------------
# 1. Kiểm tra app, dữ liệu

try {
    [void](Get-OrderPage $null)
} catch {
    Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.Message)). Chạy app trước: .\mvnw spring-boot:run"
}
$total = [long]((Invoke-Sql 'SELECT count(*) FROM orders') -join '')
Write-Host ("Bảng orders: {0} đơn, {1} trang" -f (Format-Number $total), (Format-Number ([Math]::Ceiling($total / $Size))))
if ($total -lt $skip + $Size) {
    Stop-Script ("Cần ít nhất {0} đơn để có trang {1}. Nạp dữ liệu lớn: scripts/seed-big-data.ps1" -f
                 (Format-Number ($skip + $Size)), (Format-Number $Page))
}

# Đơn cuối trang -Page - 1 (cursor của trang -Page) và các đơn của trang -Page theo OFFSET, trong cùng một câu SQL.
# created_at in theo giờ UTC đủ 6 chữ số micro giây, đúng dạng Instant của Java (app ghi Z, không ghi +00:00).
$order = 'ORDER BY created_at DESC, id DESC'
$rows = Invoke-Sql ("SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') || 'T' || " +
                    "to_char(created_at AT TIME ZONE 'UTC', 'HH24:MI:SS.US') || 'Z|' || id FROM orders " +
                    "$order OFFSET $($skip - 1) LIMIT $($Size + 1)")
$cursorRow = $rows[0] -split '\|'
$expectedIds = @($rows | Select-Object -Skip 1 | ForEach-Object { [long](($_ -split '\|')[1]) })
$cursorText = "$($cursorRow[0]),$($cursorRow[1])"
$jumpCursor = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($cursorText)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
Write-Host ("Trang {0}: các đơn thứ {1} – {2}. Cursor của nó = nextCursor của trang {3} = vị trí đơn thứ {4}:" -f
            (Format-Number $Page), (Format-Number ($skip + 1)), (Format-Number ($skip + $Size)),
            (Format-Number ($Page - 1)), (Format-Number $skip))
Write-Host "  `"$cursorText`" → base64url: $jumpCursor"

# ---------------------------------------------------------------------------------------------------------------
# 2. -Walk: đi từ trang 1 theo nextCursor

if ($Walk) {
    Write-Host ''
    Write-Host ("-Walk: đi từ trang 1 theo nextCursor tới trang {0} ({0} request)..." -f (Format-Number $Page))
    $seen = New-Object 'System.Collections.Generic.HashSet[long]'
    $times = New-Object 'System.Collections.Generic.List[double]'
    $cursor = $null
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    for ($n = 1; $n -le $Page; $n++) {
        $result = Get-OrderPage $cursor
        $times.Add($result.Ms)
        if ($result.Ids.Count -ne $Size) { Stop-Script "Trang $n có $($result.Ids.Count) đơn, mong đợi $Size" }
        foreach ($id in $result.Ids) {
            if (-not $seen.Add($id)) { Stop-Script "Đơn $id lặp lại ở trang $n" }
        }
        if ($n -eq $Page - 1 -and $result.Next -ne $jumpCursor) {
            Stop-Script "nextCursor của trang $n là $($result.Next), khác cursor tính từ DB $jumpCursor"
        }
        if ($n -eq $Page) { $walkIds = $result.Ids }
        $cursor = $result.Next
        if ($n % 1000 -eq 0) { Write-Host ("  trang {0}, {1} giây" -f (Format-Number $n), (Format-Number $watch.Elapsed.TotalSeconds)) }
    }
    $sameAsOffset = (($walkIds -join ',') -eq ($expectedIds -join ','))
    Write-Host (("Đã đi {0} trang trong {1} giây: {2} đơn, không đơn nào lặp; nextCursor trang {3} trùng cursor tính " +
                 "từ DB; trang {0} trùng câu OFFSET: {4}") -f (Format-Number $Page), (Format-Number $watch.Elapsed.TotalSeconds 'N1'),
                (Format-Number $seen.Count), (Format-Number ($Page - 1)), $(if ($sameAsOffset) { 'có' } else { 'KHÔNG' }))
    # Thời gian theo nhóm trang: 1, 2 – 10, 11 – 100, ... tới trang -Page
    $edges = @(@(1, 10, 100, 1000, 10000, 100000) | Where-Object { $_ -lt $Page }) + $Page
    $from = 1
    $groups = foreach ($edge in $edges) {
        $slice = @($times.GetRange($from - 1, $edge - $from + 1))
        [pscustomobject]@{
            'Các trang'      = $(if ($from -eq $edge) { Format-Number $edge } else { '{0} – {1}' -f (Format-Number $from), (Format-Number $edge) })
            'Trung vị (ms)'  = Format-Number (Get-Median $slice) 'N1'
            'Chậm nhất (ms)' = Format-Number (($slice | Measure-Object -Maximum).Maximum) 'N1'
        }
        $from = $edge + 1
    }
    $groups | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
    if (-not $sameAsOffset) { Write-Host 'SAI: trang cuối khi đi theo nextCursor khác câu OFFSET (có đơn mới trong lúc chạy?)' -ForegroundColor Red; exit 1 }
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Trang 1 và trang -Page qua API, xen kẽ, -Runs lần mỗi trang

Write-Host ''
Write-Host "Gọi API: trang 1 và trang $(Format-Number $Page), xen kẽ, mỗi trang $Runs lần (sau 3 lần chạy trước)"
for ($i = 0; $i -lt 3; $i++) { [void](Get-OrderPage $null); [void](Get-OrderPage $jumpCursor) }
$firstTimes = @(); $jumpTimes = @()
for ($i = 0; $i -lt $Runs; $i++) {
    $first = Get-OrderPage $null
    $jump = Get-OrderPage $jumpCursor
    $firstTimes += $first.Ms
    $jumpTimes += $jump.Ms
}
$apiFirst = Get-Median $firstTimes
$apiJump = Get-Median $jumpTimes
@(
    [pscustomobject]@{ 'Trang' = '1'; 'Request' = "GET /api/orders?size=$Size"; 'Trung vị (ms)' = Format-Number $apiFirst 'N1'
                       'Đơn đầu – cuối' = '{0} – {1}' -f $first.Ids[0], $first.Ids[-1] }
    [pscustomobject]@{ 'Trang' = Format-Number $Page; 'Request' = "GET /api/orders?size=$Size&cursor=..."
                       'Trung vị (ms)' = Format-Number $apiJump 'N1'; 'Đơn đầu – cuối' = '{0} – {1}' -f $jump.Ids[0], $jump.Ids[-1] }
) | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
$correct = (($jump.Ids -join ',') -eq ($expectedIds -join ','))
Write-Host ("Trang {0} qua API = các đơn của câu ... {1} OFFSET {2} LIMIT {3} trong DB: {4}" -f
            (Format-Number $Page), $order, $skip, $Size, $(if ($correct) { 'ĐÚNG' } else { 'SAI' }))

# ---------------------------------------------------------------------------------------------------------------
# 4. Trong DB: câu của app (cursor) và câu của cách cũ (OFFSET, count), EXPLAIN ANALYZE

# Hàm tạm (chỉ sống trong phiên psql này): chạy EXPLAIN ANALYZE 6 lần, bỏ lần đầu, trả "dòng đọc|trang 8 KB|ms trung vị".
# Dòng đọc = số dòng nút đọc index / bảng trả ra, cộng số dòng nó đọc rồi lọc bỏ.
$measureFunction = 'CREATE FUNCTION pg_temp.measure(q text) RETURNS text LANGUAGE plpgsql AS $f$ ' +
    'DECLARE p jsonb; n jsonb; t numeric[] := ''{}''; BEGIN ' +
    'FOR i IN 1..6 LOOP EXECUTE ''EXPLAIN (ANALYZE, TIMING OFF, BUFFERS, FORMAT JSON) '' || q INTO p; p := p -> 0; ' +
    'IF i > 1 THEN t := t || (p ->> ''Execution Time'')::numeric; END IF; END LOOP; ' +
    'n := p -> ''Plan''; WHILE (n ->> ''Node Type'') NOT LIKE ''%Scan'' LOOP n := n -> ''Plans'' -> 0; END LOOP; ' +
    'RETURN ((n ->> ''Actual Rows'')::numeric * (n ->> ''Actual Loops'')::numeric + coalesce((n ->> ''Rows Removed by Filter'')::numeric, 0))::bigint ' +
    '|| ''|'' || ((p -> ''Plan'' ->> ''Shared Hit Blocks'')::bigint + (p -> ''Plan'' ->> ''Shared Read Blocks'')::bigint) ' +
    '|| ''|'' || (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY x) FROM unnest(t) AS x); END $f$; '

function Measure-Query([string]$query) {
    # psql in cả "CREATE FUNCTION" trước kết quả: lấy dòng cuối
    $line = @(Invoke-Sql ($measureFunction + 'SELECT pg_temp.measure(' + "'" + $query.Replace("'", "''") + "'" + ')'))[-1]
    $parts = $line -split '\|'
    return @{ Rows = [long]$parts[0]; Pages = [long]$parts[1]; Ms = [double]$parts[2] }
}

Write-Host ''
Write-Host 'Trong DB: EXPLAIN ANALYZE, trung vị 5 lần (sau 1 lần chạy trước)'
$columns = 'SELECT id, user_id, status, total_amount, created_at FROM orders'
$cases = @(
    @{ Page = '1'; Way = 'cursor (app)'; Sql = "$columns $order LIMIT $($Size + 1)" }
    @{ Page = Format-Number $Page; Way = 'cursor (app)'
       Sql = "$columns WHERE (created_at, id) < ('$($cursorRow[0])', $($cursorRow[1])) $order LIMIT $($Size + 1)" }
    @{ Page = '1'; Way = 'OFFSET (cách cũ)'; Sql = "$columns $order OFFSET 0 LIMIT $Size" }
    @{ Page = Format-Number $Page; Way = 'OFFSET (cách cũ)'; Sql = "$columns $order OFFSET $skip LIMIT $Size" }
    @{ Page = 'mọi trang'; Way = 'count(*) của Page (cách cũ)'; Sql = 'SELECT count(*) FROM orders' }
)
$measured = foreach ($case in $cases) {
    $m = Measure-Query $case.Sql
    $case.Rows = $m.Rows
    $case.Ms = $m.Ms
    [pscustomobject]@{
        'Trang'      = $case.Page
        'Cách'       = $case.Way
        'Dòng đọc'   = Format-Number $m.Rows
        'Trang 8 KB' = Format-Number $m.Pages
        'ms'         = Format-Number $m.Ms 'N3'
    }
}
$measured | Format-Table -AutoSize | Out-String -Width 120 | Write-Host

# ---------------------------------------------------------------------------------------------------------------
# 5. Kết luận

$cursorFirst = $cases[0]; $cursorJump = $cases[1]; $offsetJump = $cases[3]; $count = $cases[4]
Write-Host ("Cursor: trang {0} đọc {1} dòng, trang 1 đọc {2}; qua API {3} ms so với {4} ms." -f
            (Format-Number $Page), (Format-Number $cursorJump.Rows), (Format-Number $cursorFirst.Rows),
            (Format-Number $apiJump 'N1'), (Format-Number $apiFirst 'N1'))
Write-Host ("Cách cũ: trang {0} đọc {1} dòng ({2} ms), cộng count(*) {3} ms mà Page chạy ở mọi trang, kể cả trang 1." -f
            (Format-Number $Page), (Format-Number $offsetJump.Rows), (Format-Number $offsetJump.Ms 'N1'),
            (Format-Number $count.Ms 'N1'))
Write-Host ''
if ($correct -and $cursorJump.Rows -le $maxRowsRead) {
    Write-Host ("ĐÚNG: trang {0} trả đúng {1} đơn và chỉ đọc {2} dòng như trang 1, không phụ thuộc số trang đứng trước" -f
                (Format-Number $Page), $Size, (Format-Number $cursorJump.Rows)) -ForegroundColor Green
    exit 0
}
if (-not $correct) {
    Write-Host "SAI: trang $(Format-Number $Page) qua API khác câu OFFSET (có đơn mới đặt trong lúc chạy?)" -ForegroundColor Red
} else {
    Write-Host ("SAI: câu cursor của trang {0} đọc {1} dòng, nhiều hơn {2}: không đi thẳng tới vị trí trong index" -f
                (Format-Number $Page), (Format-Number $cursorJump.Rows), $maxRowsRead) -ForegroundColor Red
}
exit 1
