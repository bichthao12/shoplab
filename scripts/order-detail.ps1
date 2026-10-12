<#
.SYNOPSIS
  Gọi trang chi tiết đơn (GET /api/orders/{id}/detail) trên app đang chạy, đọc header Server-Timing để so: gọi 4 nguồn
  song song (thời gian thật của trang) với gọi lần lượt (tổng thời gian 4 nguồn).

.DESCRIPTION
  Gọi API thật (không chạy test, không đọc DB). Cần app đang chạy.
    1. Tạo qua API một sản phẩm, một người dùng và một đơn 2 cái (bỏ qua nếu có -OrderId).
    2. Gọi trang chi tiết một lần cho nóng máy, rồi -Runs lần (mặc định 5). Mỗi lần in thời gian từng nguồn (order,
       products, paymentHistory, recommendations), "total" (cả trang, phía server) và thời gian client chờ.
    3. So trung vị: total (song song) với tổng 4 nguồn (thời gian trang sẽ mất nếu gọi lần lượt).
    4. Gọi một đơn không tồn tại: phải 404 ngay, không chờ hai nguồn chậm.
  Hai nguồn chậm là giả lập (shoplab.order-detail.payment-history-delay = 300ms, recommendations-delay = 400ms).
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu trang mất khoảng thời gian nguồn chậm nhất (song song) và 404 trả ngay; 1 nếu không; 2 nếu không
  chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\order-detail.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\order-detail.ps1 -OrderId 2000001 -Runs 10
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [long]$OrderId = 0,
    [ValidateRange(1, 100)][int]$Runs = 5
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, username, SKU không trùng lần trước
$vi = [System.Globalization.CultureInfo]::GetCultureInfo('vi-VN')
$sources = @('order', 'products', 'paymentHistory', 'recommendations')

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

# Gửi một request, trả về @{ Code; Body; Ms (client chờ); Timing (Server-Timing: tên → ms) }
function Send-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    if ($idempotencyKey) { $request.Headers.Add('Idempotency-Key', $idempotencyKey) }
    try {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $watch.Stop()
        $timing = @{}
        $values = $null
        if ($response.Headers.TryGetValues('Server-Timing', [ref]$values)) {
            foreach ($m in [regex]::Matches(($values -join ', '), '(\w+);dur=([0-9.]+)')) {
                $timing[$m.Groups[1].Value] = [double]::Parse($m.Groups[2].Value, [System.Globalization.CultureInfo]::InvariantCulture)
            }
        }
        return @{ Code = [int]$response.StatusCode; Body = $body; Ms = $watch.Elapsed.TotalMilliseconds; Timing = $timing }
    } finally {
        $request.Dispose()
    }
}

function Invoke-Api([string]$method, [string]$path, [string]$json, [int]$expected, [string]$idempotencyKey) {
    $result = Send-Request $method $path $json $idempotencyKey
    if ($result.Code -ne $expected) {
        Stop-Script "$method $path -> $($result.Code) (mong đợi $expected): $($result.Body)"
    }
    return $result.Body | ConvertFrom-Json
}

function Format-Ms([double]$ms) {
    return $ms.ToString('0.0', $vi)
}

function Get-Median([double[]]$values) {
    $sorted = @($values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}

Write-Host 'Trang chi tiết đơn: GET /api/orders/{id}/detail, 4 nguồn chạy song song'
Write-Host ''

# ---------------------------------------------------------------------------------------------------------------
# 1. App, đơn để xem

try {
    [void](Send-Request 'GET' '/api/products?size=1' $null $null)
} catch {
    Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.Message)). Chạy app trước: .\mvnw spring-boot:run"
}

if ($OrderId -le 0) {
    $product = Invoke-Api 'POST' '/api/products' (@{
        sku = "DETAIL-$run"; name = 'Áo thun chi tiết'; category = 'DETAIL'; price = 150000; stock = 1000
    } | ConvertTo-Json -Compress) 201
    $user = Invoke-Api 'POST' '/api/users' (@{
        email = "detail.$run@example.com"; fullName = 'Khách xem chi tiết'; username = "detail$run"; password = 'Secret123!'
    } | ConvertTo-Json -Compress) 201
    $orderJson = '{{"userId":{0},"items":[{{"productId":{1},"quantity":2}}]}}' -f $user.id, $product.id
    $OrderId = (Invoke-Api 'POST' '/api/orders' $orderJson 201 ([guid]::NewGuid().ToString('N'))).id
    Write-Host "Tạo qua API: sản phẩm $($product.id), người dùng $($user.id), đơn $OrderId (2 cái)"
}

# ---------------------------------------------------------------------------------------------------------------
# 2. Gọi trang chi tiết

$path = "/api/orders/$OrderId/detail"
$warm = Send-Request 'GET' $path $null $null   # lần đầu: nạp class, JIT
if ($warm.Code -ne 200) {
    Stop-Script "GET $path -> $($warm.Code): $($warm.Body)"
}
$detail = $warm.Body | ConvertFrom-Json
Write-Host ("GET {0} -> 200: đơn {1} dòng hàng, {2} sản phẩm, {3} sự kiện thanh toán, {4} gợi ý, unavailable [{5}]" -f
            $path, @($detail.order.items).Count, @($detail.products).Count, @($detail.paymentHistory).Count,
            @($detail.recommendations).Count, (@($detail.unavailable) -join ', '))
Write-Host ''

$rows = for ($i = 1; $i -le $Runs; $i++) {
    $r = Send-Request 'GET' $path $null $null
    if ($r.Code -ne 200) { Stop-Script "GET $path -> $($r.Code): $($r.Body)" }
    $sum = ($sources | ForEach-Object { $r.Timing[$_] } | Measure-Object -Sum).Sum
    [pscustomobject]@{
        'Lần'                       = $i
        'order'                     = Format-Ms $r.Timing['order']
        'products'                  = Format-Ms $r.Timing['products']
        'paymentHistory'            = Format-Ms $r.Timing['paymentHistory']
        'recommendations'           = Format-Ms $r.Timing['recommendations']
        'Tổng 4 nguồn (lần lượt)'   = Format-Ms $sum
        'total (song song)'         = Format-Ms $r.Timing['total']
        'Client chờ'                = Format-Ms $r.Ms
        Sum                         = $sum
        Total                       = $r.Timing['total']
        Client                      = $r.Ms
    }
}
Write-Host 'Thời gian (ms) theo header Server-Timing; "Client chờ" tính cả HTTP:'
$rows | Select-Object 'Lần', 'order', 'products', 'paymentHistory', 'recommendations', 'Tổng 4 nguồn (lần lượt)',
        'total (song song)', 'Client chờ' | Format-Table -AutoSize | Out-String -Width 160 | Write-Host

$medianSum = Get-Median ($rows | ForEach-Object { $_.Sum })
$medianTotal = Get-Median ($rows | ForEach-Object { $_.Total })
$medianClient = Get-Median ($rows | ForEach-Object { $_.Client })
Write-Host ("Trung vị: gọi lần lượt sẽ mất {0} ms (tổng 4 nguồn); song song mất {1} ms (client chờ {2} ms), nhanh hơn {3} ms" -f
            (Format-Ms $medianSum), (Format-Ms $medianTotal), (Format-Ms $medianClient), (Format-Ms ($medianSum - $medianTotal)))

# ---------------------------------------------------------------------------------------------------------------
# 3. Đơn không tồn tại

$missing = Send-Request 'GET' '/api/orders/999999999/detail' $null $null
Write-Host ("GET /api/orders/999999999/detail -> {0} sau {1} ms (không chờ hai nguồn chậm 300 / 400 ms)" -f
            $missing.Code, (Format-Ms $missing.Ms))
Write-Host ''

$parallel = $medianTotal -lt $medianSum - 200
$fast404 = $missing.Code -eq 404 -and $missing.Ms -lt 250
if ($parallel -and $fast404) {
    Write-Host ("ĐÚNG: trang mất {0} ms, bằng nguồn chậm nhất chứ không phải tổng các nguồn ({1} ms); đơn không tồn tại trả 404 ngay" -f
                (Format-Ms $medianTotal), (Format-Ms $medianSum)) -ForegroundColor Green
    exit 0
}
if (-not $parallel) { Write-Host 'SAI: trang mất gần bằng tổng các nguồn: các nguồn đang chạy lần lượt' -ForegroundColor Red }
if (-not $fast404) { Write-Host "SAI: đơn không tồn tại trả $($missing.Code) sau $(Format-Ms $missing.Ms) ms" -ForegroundColor Red }
exit 1
