<#
.SYNOPSIS
  Kiểm tra nhanh API danh sách đơn phân trang cursor (GET /api/orders) trên app đang chạy: trang 1, trang 2 theo
  nextCursor, danh sách của một người dùng, và các trường hợp lỗi 400.

.DESCRIPTION
  Chỉ gọi API (không chạy test, không đọc DB). Cần app đang chạy và có đơn trong DB.
    1. GET /api/orders?size=3: 3 đơn mới nhất, có nextCursor.
    2. GET /api/orders?size=3&cursor=<nextCursor trang 1>: 3 đơn kế tiếp, không trùng trang 1, và trang 1 + trang 2
       đúng bằng 6 đơn đầu của GET /api/orders?size=6 (không sót đơn nào giữa hai trang).
    3. GET /api/orders?userId=<UserId>&size=3: chỉ đơn của người dùng đó. Rồi đi hết danh sách của người dùng đó
       theo nextCursor (100 đơn mỗi trang): không đơn nào lặp, đúng thứ tự, trang cuối nextCursor null.
    4. Người dùng không tồn tại → danh sách rỗng, nextCursor null.
    5. page=1, sort=id, cursor=abc → 400 invalid-cursor; size=0, size=101 → 400 validation.
  "Mới nhất trước" nghĩa là theo (createdAt giảm dần, id giảm dần): cùng createdAt thì id lớn đứng trước.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu mọi mục đúng, 1 nếu có mục sai, 2 nếu không gọi được app hoặc DB chưa có đơn.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cursor-api-check.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cursor-api-check.ps1 -UserId 42
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 50)][int]$Size = 3,
    [long]$UserId = 1
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$checks = New-Object 'System.Collections.Generic.List[object]'

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

# GET, trả về @{ Code = mã HTTP; Body = nội dung }. Không ném lỗi với 4xx.
function Send-Get([string]$path) {
    $response = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
    return @{ Code = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
}

# Một trang: @{ Orders = các đơn; Next = nextCursor }. createdAt lấy nguyên chuỗi trong JSON (PowerShell 7 tự đổi
# chuỗi ngày thành DateTime theo giờ máy), Ticks dùng để so thứ tự tới micro giây.
function Get-Page([string]$path) {
    $result = Send-Get $path
    if ($result.Code -ne 200) {
        Stop-Script "GET $path -> $($result.Code) (mong đợi 200): $($result.Body)"
    }
    $json = $result.Body | ConvertFrom-Json
    $createdAt = @([regex]::Matches($result.Body, '"createdAt"\s*:\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
    $items = @($json.content)
    $orders = New-Object 'System.Collections.Generic.List[object]'
    for ($i = 0; $i -lt $items.Count; $i++) {
        $orders.Add([pscustomobject]@{
            id        = [long]$items[$i].id
            userId    = $items[$i].userId
            status    = $items[$i].status
            createdAt = $createdAt[$i]
            Ticks     = [DateTimeOffset]::Parse($createdAt[$i], [System.Globalization.CultureInfo]::InvariantCulture).UtcTicks
        })
    }
    return @{ Orders = $orders.ToArray(); Next = $json.nextCursor }
}

# Đơn a đứng trước đơn b trong danh sách mới nhất trước
function Test-Before($a, $b) {
    return ($a.Ticks -gt $b.Ticks) -or ($a.Ticks -eq $b.Ticks -and $a.id -gt $b.id)
}

function Test-NewestFirst([object[]]$orders) {
    for ($i = 1; $i -lt $orders.Count; $i++) {
        if (-not (Test-Before $orders[$i - 1] $orders[$i])) { return $false }
    }
    return $true
}

function Add-Check([string]$name, [bool]$ok, [string]$detail) {
    $checks.Add([pscustomobject]@{ Name = $name; Ok = $ok })
    $color = 'Green'; $verdict = 'ĐÚNG'
    if (-not $ok) { $color = 'Red'; $verdict = 'SAI ' }
    Write-Host ("  {0}  {1}: {2}" -f $verdict, $name, $detail) -ForegroundColor $color
}

function Show-Orders([object[]]$orders) {
    $orders | Select-Object id, userId, status, createdAt | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
}

# ---------------------------------------------------------------------------------------------------------------
# 0. App

try {
    [void](Send-Get '/api/products?size=1')
} catch {
    Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.Message)). Chạy app trước: .\mvnw spring-boot:run"
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Trang 1

Write-Host "== 1. Trang 1: GET /api/orders?size=$Size"
$p1 = Get-Page "/api/orders?size=$Size"
if ($p1.Orders.Count -eq 0) {
    Stop-Script 'DB chưa có đơn nào. Nạp dữ liệu: scripts/seed-big-data.ps1'
}
Show-Orders $p1.Orders
Write-Host "  nextCursor: $($p1.Next)"
Add-Check 'Trang 1' (($p1.Orders.Count -eq $Size) -and (Test-NewestFirst $p1.Orders) -and [bool]$p1.Next) `
    ("{0} đơn, mới nhất trước, có nextCursor" -f $p1.Orders.Count)

# ---------------------------------------------------------------------------------------------------------------
# 2. Trang 2 theo nextCursor

Write-Host ''
Write-Host "== 2. Trang 2: GET /api/orders?size=$Size&cursor=<nextCursor của trang 1>"
$p2 = Get-Page "/api/orders?size=$Size&cursor=$($p1.Next)"
Show-Orders $p2.Orders
$overlap = @($p2.Orders | Where-Object { @($p1.Orders.id) -contains $_.id })
# Trang 1 + trang 2 phải đúng bằng 2 x Size đơn đầu lấy trong một lần gọi: không trùng, không sót đơn nào ở giữa
$both = Get-Page "/api/orders?size=$(2 * $Size)"
$joined = (@($p1.Orders.id) + @($p2.Orders.id)) -join ','
$expected = @($both.Orders.id) -join ','
Add-Check 'Trang 2' (($p2.Orders.Count -gt 0) -and ($overlap.Count -eq 0) -and ($joined -eq $expected)) `
    ("{0} đơn, trùng trang 1: {1}; trang 1 + trang 2 = {2} đơn đầu của ?size={2}: {3}" -f
     $p2.Orders.Count, $overlap.Count, (2 * $Size), $(if ($joined -eq $expected) { 'có' } else { "KHÔNG ($expected)" }))

# ---------------------------------------------------------------------------------------------------------------
# 3. Danh sách của một người dùng

Write-Host ''
Write-Host "== 3. Người dùng ${UserId}: GET /api/orders?userId=$UserId&size=$Size"
$u = Get-Page "/api/orders?userId=$UserId&size=$Size"
Show-Orders $u.Orders
$others = @($u.Orders | Where-Object { [long]$_.userId -ne $UserId })
Add-Check "userId=$UserId" (($others.Count -eq 0) -and (Test-NewestFirst $u.Orders)) `
    ("{0} đơn, đơn của người khác: {1}" -f $u.Orders.Count, $others.Count)

# Đi hết danh sách của người dùng, 100 đơn mỗi trang
$all = New-Object 'System.Collections.Generic.List[object]'
$path = "/api/orders?userId=$UserId&size=100"
$page = Get-Page $path
$pages = 1
foreach ($o in $page.Orders) { $all.Add($o) }
while ($page.Next) {
    if ($pages -ge 1000) { break }
    $page = Get-Page "$path&cursor=$($page.Next)"
    $pages++
    foreach ($o in $page.Orders) { $all.Add($o) }
}
$repeated = $all.Count - @($all | ForEach-Object { $_.id } | Select-Object -Unique).Count
$notMine = @($all | Where-Object { [long]$_.userId -ne $UserId }).Count
$ordered = Test-NewestFirst $all.ToArray()
Add-Check "Đi hết danh sách người dùng $UserId" `
    (($repeated -eq 0) -and ($notMine -eq 0) -and $ordered -and -not $page.Next) `
    ("{0} đơn qua {1} trang; đơn lặp: {2}, đơn của người khác: {3}, mới nhất trước: {4}, trang cuối nextCursor null: {5}" -f
     $all.Count, $pages, $repeated, $notMine, $(if ($ordered) { 'có' } else { 'KHÔNG' }),
     $(if ($page.Next) { 'KHÔNG' } else { 'có' }))

# ---------------------------------------------------------------------------------------------------------------
# 4. Người dùng không tồn tại

Write-Host ''
Write-Host '== 4. Người dùng không tồn tại: GET /api/orders?userId=999999999'
$none = Get-Page '/api/orders?userId=999999999'
Add-Check 'userId=999999999' (($none.Orders.Count -eq 0) -and -not $none.Next) `
    ("{0} đơn, nextCursor {1}" -f $none.Orders.Count, $(if ($none.Next) { $none.Next } else { 'null' }))

# ---------------------------------------------------------------------------------------------------------------
# 5. Lỗi 400

Write-Host ''
Write-Host '== 5. Tham số sai → 400'
$cases = @(
    @{ Query = 'page=1'; Type = 'invalid-cursor' }
    @{ Query = 'sort=id'; Type = 'invalid-cursor' }
    @{ Query = 'cursor=abc'; Type = 'invalid-cursor' }
    @{ Query = 'size=0'; Type = 'validation' }
    @{ Query = 'size=101'; Type = 'validation' }
)
foreach ($case in $cases) {
    $result = Send-Get "/api/orders?$($case.Query)"
    $type = ''
    try { $type = (($result.Body | ConvertFrom-Json).type -split '/')[-1] } catch { }
    Add-Check "?$($case.Query)" (($result.Code -eq 400) -and ($type -eq $case.Type)) `
        ("{0} {1} (mong đợi 400 {2})" -f $result.Code, $type, $case.Type)
}

# ---------------------------------------------------------------------------------------------------------------
# Kết luận

$failed = @($checks | Where-Object { -not $_.Ok })
Write-Host ''
if ($failed.Count -eq 0) {
    Write-Host ("ĐÚNG ({0}/{0}): danh sách đơn phân trang cursor hoạt động như mong đợi" -f $checks.Count) -ForegroundColor Green
    exit 0
}
Write-Host ("SAI ({0}/{1} mục sai): {2}" -f $failed.Count, $checks.Count, (($failed | ForEach-Object { $_.Name }) -join '; ')) -ForegroundColor Red
exit 1
