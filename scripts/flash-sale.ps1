<#
.SYNOPSIS
  Bán chớp nhoáng: Buyers lượt mua cùng lúc một sản phẩm chỉ còn Stock cái, rồi đếm số đơn được tạo.
  Bản PowerShell của scripts/flash-sale.sh.

.DESCRIPTION
  Cần app đang chạy (.\mvnw spring-boot:run). Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Gửi request song song bằng System.Net.Http.HttpClient (tối đa Parallel kết nối cùng lúc),
  nên không cần ForEach-Object -Parallel của PowerShell 7.

  Thoát với mã 0 nếu bán đúng min(Stock, Buyers) cái và mọi lượt còn lại đều nhận "hết hàng" (insufficient-stock);
  1 nếu sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\flash-sale.ps1
  1000 lượt mua, kho 1, 200 lượt song song.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\flash-sale.ps1 -Buyers 300 -Stock 7 -Parallel 50
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Buyers = 1000,
    [int]$Stock = 1,
    [int]$Parallel = 200
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, SKU, Idempotency-Key không trùng lần trước

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = $Parallel          # tối đa Parallel request đang chạy cùng lúc
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

function New-JsonPost([string]$path, [string]$json) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl$path")
    $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    return $request
}

# Gửi một request, trả về @{ Code = mã HTTP; Body = nội dung }
function Send-Request($request) {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    return @{ Code = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
}

# POST JSON, trả về body đã đọc thành object. Mã HTTP không phải 2xx thì in lỗi và dừng.
function Invoke-Setup([string]$path, [string]$json) {
    $result = Send-Request (New-JsonPost $path $json)
    if ($result.Code -lt 200 -or $result.Code -ge 300) {
        Write-Host "POST $path -> $($result.Code): $($result.Body)" -ForegroundColor Red
        exit 2
    }
    return $result.Body | ConvertFrom-Json
}

function Get-Json([string]$path) {
    return (Send-Request (New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$BaseUrl$path"))).Body |
            ConvertFrom-Json
}

try {
    [void](Send-Request (New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$BaseUrl/api/products")))
} catch {
    Write-Host "Không gọi được $BaseUrl. App đã chạy chưa? (.\mvnw spring-boot:run)" -ForegroundColor Red
    exit 2
}

Write-Host '== Bước 2: tạo người mua'
$user = Invoke-Setup '/api/users' (@{
    email    = "flash$run@example.com"
    fullName = "Người mua $run"
    username = "flash$run"
    password = 'matkhau123'
} | ConvertTo-Json -Compress)
Write-Host "   userId = $($user.id)"

Write-Host "== Bước 3: tạo sản phẩm còn $Stock cái"
$product = Invoke-Setup '/api/products' (@{
    sku      = "FLASH-$run"
    name     = "Hàng hiếm $run"
    category = 'test'
    price    = 100000
    stock    = $Stock
} | ConvertTo-Json -Compress)
Write-Host "   productId = $($product.id)"

Write-Host "== Bước 4: $Buyers lượt mua, $Parallel lượt chạy song song"
# Mỗi lượt một Idempotency-Key riêng nên là một lần mua khác nhau, không phải gửi lại.
$orderJson = '{"userId":' + $user.id + ',"items":[{"productId":' + $product.id + ',"quantity":1}]}'
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = New-Object System.Collections.ArrayList
for ($i = 1; $i -le $Buyers; $i++) {
    $request = New-JsonPost '/api/orders' $orderJson
    $request.Headers.Add('Idempotency-Key', "flash-$run-$i")
    [void]$tasks.Add($client.SendAsync($request))   # gửi đi ngay, không chờ
}
try {
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks.ToArray())
} catch {
    # request lỗi kết nối: xem ở từng task bên dưới (mã 000)
}
$watch.Stop()
Write-Host ("   xong sau ~{0:N1} giây" -f $watch.Elapsed.TotalSeconds)

$results = foreach ($task in $tasks) {
    if ($task.IsFaulted) {
        [PSCustomObject]@{ Code = '000'; Type = $null }
        continue
    }
    $code = [int]$task.Result.StatusCode
    $type = $null
    if ($code -ge 400) {
        $body = $task.Result.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        try { $type = (($body | ConvertFrom-Json).type -split '/')[-1] } catch { $type = '(không đọc được body)' }
    }
    [PSCustomObject]@{ Code = [string]$code; Type = $type }
}

Write-Host '   Mã HTTP (000 = không kết nối được):'
$results | Group-Object Code | Sort-Object Name | ForEach-Object { Write-Host ('{0,12} {1}' -f $_.Count, $_.Name) }
Write-Host '   Loại lỗi:'
$results | Where-Object { $_.Type } | Group-Object Type | Sort-Object Name |
        ForEach-Object { Write-Host ('{0,12} {1}' -f $_.Count, $_.Name) }

Write-Host '== Bước 5: đếm đơn và xem kho'
$orders = (Get-Json "/api/orders?userId=$($user.id)&size=1").page.totalElements
$stockLeft = (Get-Json "/api/products/$($product.id)").stock
$created = @($results | Where-Object { $_.Code -eq '201' }).Count
$outOfStock = @($results | Where-Object { $_.Type -eq 'insufficient-stock' }).Count
$expected = [Math]::Min($Stock, $Buyers)
Write-Host "   số đơn trong DB : $orders (mong đợi $expected)"
Write-Host "   response 201    : $created (mong đợi $expected)"
Write-Host "   hết hàng        : $outOfStock (mong đợi $($Buyers - $expected))"
Write-Host "   kho còn         : $stockLeft (mong đợi $($Stock - $expected))"

$client.Dispose()

if ($orders -eq $expected -and $created -eq $expected -and $outOfStock -eq ($Buyers - $expected) `
        -and $stockLeft -eq ($Stock - $expected)) {
    Write-Host "ĐÚNG: bán đúng $expected cái, các lượt còn lại đều nhận `"hết hàng`"" -ForegroundColor Green
    exit 0
} elseif ($orders -gt $expected) {
    Write-Host "SAI: bán vượt, $orders đơn cho $Stock cái hàng" -ForegroundColor Red
    exit 1
} else {
    Write-Host "SAI: số đơn không vượt, nhưng có lượt mua nhận lỗi khác `"hết hàng`" (xem Loại lỗi ở trên)" -ForegroundColor Red
    exit 1
}
