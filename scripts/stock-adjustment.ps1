<#
.SYNOPSIS
  Điều chỉnh tồn kho (cộng / trừ) qua API: nhập hàng chạy cùng lúc với đơn hàng không mất lượt nào,
  gửi lại cùng Idempotency-Key chỉ cộng một lần, trừ quá tồn kho bị từ chối.

.DESCRIPTION
  Cùng kịch bản với các test stock-adjustments trong ProductApiIntegrationTests, nhưng gọi API của app đang chạy
  (.\mvnw spring-boot:run). Mỗi case dùng một sản phẩm mới, kho ban đầu 10:

    1. Restocks lượt nhập +1 (POST /api/products/{id}/stock-adjustments) và Orders đơn mua 1 cái (POST /api/orders),
       gửi CÙNG LÚC. Mong đợi: mọi request thành công, kho = 10 + Restocks - Orders (không mất lượt nào).
    2. Gửi cùng một lượt nhập +5 ba lần với CÙNG Idempotency-Key (như client gửi lại khi mạng chập chờn).
       Mong đợi: cả ba 200 với cùng nội dung, lần 2, 3 có header Idempotent-Replayed: true, kho chỉ cộng 5 một lần.
       Cùng key nhưng delta khác → 422 idempotency-key-reused.
    3. Trừ nhiều hơn số đang có. Mong đợi: 409 insufficient-stock, kho giữ nguyên.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu cả 3 case đều đúng; 1 nếu có case sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\stock-adjustment.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\stock-adjustment.ps1 -Restocks 200 -Orders 100
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Restocks = 50,
    [int]$Orders = 10
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, SKU, Idempotency-Key không trùng lần trước
$initialStock = 10

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = [Math]::Max($Restocks + $Orders, 2)   # case 1: mọi request đi ngay, không xếp hàng
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

function New-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    if ($idempotencyKey) { $request.Headers.Add('Idempotency-Key', $idempotencyKey) }
    return $request
}

# Đọc response thành @{ Code = mã HTTP; Text = body nguyên văn; Body = body đã đọc thành object; Replayed = có header Idempotent-Replayed }
function Read-Response($response) {
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body = if ($text) { $text | ConvertFrom-Json } else { $null }
    $replayed = $response.Headers.Contains('Idempotent-Replayed')
    return @{ Code = [int]$response.StatusCode; Text = $text; Body = $body; Replayed = $replayed }
}

function Send-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    return Read-Response ($client.SendAsync((New-Request $method $path $json $idempotencyKey)).GetAwaiter().GetResult())
}

function Get-ProblemType($result) {
    if ($result.Body -and $result.Body.type) { return ($result.Body.type -split '/')[-1] }
    return ''
}

function Format-Result($result) {
    if ($result.Code -ge 200 -and $result.Code -lt 300) {
        return '{0}, stock {1}, version {2}{3}' -f $result.Code, $result.Body.stock, $result.Body.version,
                $(if ($result.Replayed) { '   (Idempotent-Replayed: true)' } else { '' })
    }
    $text = '{0} {1}: {2}' -f $result.Code, (Get-ProblemType $result), $result.Body.detail
    if ($null -ne $result.Body.available) {
        $text += ' (requested {0}, available {1})' -f $result.Body.requested, $result.Body.available
    }
    return $text
}

# POST JSON; mã HTTP không phải 201 thì in lỗi và dừng
function Invoke-Setup([string]$path, [string]$json) {
    $result = Send-Request 'POST' $path $json
    if ($result.Code -ne 201) {
        Write-Host "POST $path -> $(Format-Result $result)" -ForegroundColor Red
        exit 2
    }
    return $result.Body
}

function New-Product([string]$sku) {
    return Invoke-Setup '/api/products' ('{"sku":"' + $sku + '","name":"Sản phẩm ' + $sku +
            '","category":"test","price":100000,"stock":' + $initialStock + '}')
}

function Get-Stock([long]$productId) {
    return (Send-Request 'GET' "/api/products/$productId").Body.stock
}

$failedCases = New-Object System.Collections.Generic.List[string]

function Write-Verdict([string]$name, $problems, [string]$okMessage) {
    if ($problems.Count -eq 0) {
        Write-Host "   ĐÚNG: $okMessage" -ForegroundColor Green
        return
    }
    foreach ($p in $problems) { Write-Host "   SAI: $p" -ForegroundColor Red }
    $failedCases.Add($name)
}

try {
    [void]$client.GetAsync("$BaseUrl/api/products").GetAwaiter().GetResult()
} catch {
    Write-Host "Không gọi được $BaseUrl. App đã chạy chưa? (.\mvnw spring-boot:run)" -ForegroundColor Red
    exit 2
}

# ---------------------------------------------------------------------------------------------------------------
$expectedStock = $initialStock + $Restocks - $Orders
Write-Host "== Case 1: $Restocks lượt nhập +1 và $Orders đơn mua 1 cái, gửi cùng lúc"
$buyer = Invoke-Setup '/api/users' ('{"email":"adj' + $run + '@example.com","fullName":"Khách ' + $run +
        '","username":"adj' + $run + '","password":"matkhau123"}')
$product = New-Product "ADJ-$run-1"
$path = "/api/products/$($product.id)"
Write-Host "   tạo sản phẩm id $($product.id), kho $initialStock; người mua id $($buyer.id)"
Write-Host "   POST $path/stock-adjustments {""delta"":1} × $Restocks   |   POST /api/orders (1 cái) × $Orders"

# Tạo sẵn mọi request (xen kẽ nhập / mua) rồi mới gửi, để chúng đi gần như cùng một lúc
$orderJson = '{"userId":' + $buyer.id + ',"items":[{"productId":' + $product.id + ',"quantity":1}]}'
$kinds = New-Object System.Collections.Generic.List[string]
$requests = New-Object System.Collections.Generic.List[object]
for ($i = 1; $i -le [Math]::Max($Restocks, $Orders); $i++) {
    if ($i -le $Restocks) {
        $kinds.Add('restock')
        $requests.Add((New-Request 'POST' "$path/stock-adjustments" '{"delta":1}' "adj-$run-r$i"))
    }
    if ($i -le $Orders) {
        $kinds.Add('order')
        $requests.Add((New-Request 'POST' '/api/orders' $orderJson "adj-$run-o$i"))
    }
}
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = @(foreach ($r in $requests) { $client.SendAsync($r) })
try {
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
} catch {
    # request lỗi kết nối: xem ở từng task bên dưới
}
$watch.Stop()
Write-Host "   xong sau $($watch.ElapsedMilliseconds) ms"

$restocked = 0; $ordered = 0
$others = @{}   # kết quả không mong đợi: "loại mã type" → số lần
for ($i = 0; $i -lt $tasks.Count; $i++) {
    if ($tasks[$i].IsFaulted -or $tasks[$i].IsCanceled) {
        $key = "$($kinds[$i]): lỗi kết nối / hết giờ"
        $others[$key] = 1 + [int]$others[$key]
        continue
    }
    $result = Read-Response $tasks[$i].Result
    if ($kinds[$i] -eq 'restock' -and $result.Code -eq 200) { $restocked++ }
    elseif ($kinds[$i] -eq 'order' -and $result.Code -eq 201) { $ordered++ }
    else {
        $key = "$($kinds[$i]): $($result.Code) $(Get-ProblemType $result)".Trim()
        $others[$key] = 1 + [int]$others[$key]
    }
}
$finalStock = Get-Stock $product.id

Write-Host "   nhập hàng 200: $restocked/$Restocks   đặt hàng 201: $ordered/$Orders"
foreach ($key in $others.Keys) { Write-Host "   khác: ${key}: $($others[$key])" -ForegroundColor Red }
Write-Host "   kho sau cùng: $finalStock (mong đợi $initialStock + $Restocks - $Orders = $expectedStock)" `
    -ForegroundColor $(if ($finalStock -eq $expectedStock) { 'Green' } else { 'Red' })

$problems = New-Object System.Collections.Generic.List[string]
if ($restocked -ne $Restocks -or $ordered -ne $Orders) {
    $problems.Add("có request không thành công: nhập $restocked/$Restocks, đặt hàng $ordered/$Orders")
}
if ($finalStock -ne $initialStock + $restocked - $ordered) {
    $problems.Add("kho là $finalStock, không khớp $initialStock + $restocked lượt nhập - $ordered đơn = " +
            "$($initialStock + $restocked - $ordered): có lượt cộng / trừ bị ghi đè (lost update)")
}
Write-Verdict 'case 1' $problems "không mất lượt nào: kho = $initialStock + $Restocks - $Orders = $expectedStock"

# ---------------------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host '== Case 2: gửi lại cùng một lượt nhập +5 với cùng Idempotency-Key'
$product = New-Product "ADJ-$run-2"
$path = "/api/products/$($product.id)"
$key = "adj-$run-retry"
Write-Host "   tạo sản phẩm id $($product.id), kho $initialStock; Idempotency-Key: $key"

$attempts = @(for ($i = 1; $i -le 3; $i++) {
    $result = Send-Request 'POST' "$path/stock-adjustments" '{"delta":5}' $key
    Write-Host "   lần $i POST {""delta"":5} → $(Format-Result $result)"
    $result
})
$reused = Send-Request 'POST' "$path/stock-adjustments" '{"delta":6}' $key
Write-Host "   cùng key, delta khác POST {""delta"":6} → $(Format-Result $reused)"
$finalStock = Get-Stock $product.id
Write-Host "   kho sau cùng: $finalStock (mong đợi $initialStock + 5 = $($initialStock + 5))"

$problems = New-Object System.Collections.Generic.List[string]
if (@($attempts | Where-Object { $_.Code -ne 200 }).Count -gt 0) {
    $problems.Add('có lần gửi không nhận 200')
}
if (@($attempts | Where-Object { $_.Text -ne $attempts[0].Text }).Count -gt 0) {
    $problems.Add('các lần gửi lại không nhận đúng response của lần đầu')
}
if ($attempts[0].Replayed -or -not ($attempts[1].Replayed -and $attempts[2].Replayed)) {
    $problems.Add('mong đợi lần 2, 3 có header Idempotent-Replayed: true, lần 1 thì không')
}
if ($reused.Code -ne 422 -or (Get-ProblemType $reused) -ne 'idempotency-key-reused') {
    $problems.Add("cùng key, delta khác: mong đợi 422 idempotency-key-reused, nhận $(Format-Result $reused)")
}
if ($finalStock -ne $initialStock + 5) {
    $problems.Add("kho là $finalStock, mong đợi $($initialStock + 5): lượt nhập bị cộng nhiều lần")
}
Write-Verdict 'case 2' $problems 'gửi 3 lần nhưng kho chỉ cộng 5 một lần; lần 2, 3 nhận lại nguyên văn response lần đầu'

# ---------------------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host '== Case 3: trừ nhiều hơn số đang có'
$product = New-Product "ADJ-$run-3"
$path = "/api/products/$($product.id)"
$json = '{"delta":' + (-($initialStock + 1)) + '}'
Write-Host "   tạo sản phẩm id $($product.id), kho $initialStock"
Write-Host "   POST $path/stock-adjustments $json"
$result = Send-Request 'POST' "$path/stock-adjustments" $json "adj-$run-over"
Write-Host "            → $(Format-Result $result)"
$finalStock = Get-Stock $product.id
Write-Host "   kho sau cùng: $finalStock"

$problems = New-Object System.Collections.Generic.List[string]
if ($result.Code -ne 409 -or (Get-ProblemType $result) -ne 'insufficient-stock') {
    $problems.Add("mong đợi 409 insufficient-stock, nhận $(Format-Result $result)")
}
if ($finalStock -ne $initialStock) {
    $problems.Add("kho là $finalStock, mong đợi giữ nguyên $initialStock")
}
Write-Verdict 'case 3' $problems "bị từ chối với 409, kho giữ nguyên $initialStock"

$client.Dispose()

Write-Host ''
if ($failedCases.Count -eq 0) {
    Write-Host 'ĐÚNG (3/3 case): điều chỉnh tồn kho không mất lượt nào, không cộng hai lần, không cho kho âm' `
        -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $($failedCases -join ', ') không như mong đợi" -ForegroundColor Red
exit 1
