<#
.SYNOPSIS
  Chạy lại test case lost update khi sửa sản phẩm qua API: client gửi version cũ phải nhận 409, không ghi đè ai.

.DESCRIPTION
  Cùng kịch bản với các test PATCH trong ProductApiIntegrationTests, nhưng gọi API của app đang chạy
  (.\mvnw spring-boot:run). PATCH /api/products/{id} phải kèm "version" mà client đã đọc.
  Mỗi case dùng một sản phẩm mới:

    1. Admin sửa sản phẩm sau khi có đơn, theo version đọc TRƯỚC khi có đơn:
       Admin GET sản phẩm (stock 10, version 0). Khách đặt 3 cái (stock 7, version vẫn 0: đơn không đổi version).
       Admin đổi giá với version 0 → mong đợi 200, kho vẫn 7.
       Admin gửi PATCH có stock → mong đợi 400 (tồn kho không sửa qua PATCH).
       Admin nhập thêm 5 cái qua POST /api/products/{id}/stock-adjustments {"delta":5} → mong đợi 200, kho 12.
    2. Concurrent request PATCH cùng gửi version 0, gửi CÙNG LÚC:
       Mong đợi: đúng 1 request 200, còn lại 409; sản phẩm mang giá của request thắng, version 1.
    3. PATCH không có version: mong đợi 400 validation, có errors.version.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu cả 3 case đều đúng; 1 nếu có case sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\product-lost-update.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\product-lost-update.ps1 -Concurrent 50
  Case 2 gửi 50 PATCH cùng lúc.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Concurrent = 20
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, SKU, Idempotency-Key không trùng lần trước

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = [Math]::Max($Concurrent, 2)   # case 2: mọi PATCH đi ngay, không xếp hàng chờ kết nối
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi request có body

function New-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    $httpMethod = New-Object System.Net.Http.HttpMethod($method)   # .NET Framework không có HttpMethod.Patch
    $request = New-Object System.Net.Http.HttpRequestMessage($httpMethod, "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    if ($idempotencyKey) { $request.Headers.Add('Idempotency-Key', $idempotencyKey) }
    return $request
}

# Đọc response thành @{ Code = mã HTTP; Body = body đã đọc thành object }
function Read-Response($response) {
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body = if ($text) { $text | ConvertFrom-Json } else { $null }
    return @{ Code = [int]$response.StatusCode; Body = $body }
}

function Send-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    return Read-Response ($client.SendAsync((New-Request $method $path $json $idempotencyKey)).GetAwaiter().GetResult())
}

function Get-ProblemType($result) {
    if ($result.Body -and $result.Body.type) { return ($result.Body.type -split '/')[-1] }
    return ''
}

function Format-Product($product) {
    return 'version {0}, stock {1}, price {2}' -f $product.version, $product.stock, $product.price
}

function Format-Result($result) {
    if ($result.Code -ge 200 -and $result.Code -lt 300) { return "$($result.Code), $(Format-Product $result.Body)" }
    $text = '{0} {1}: {2}' -f $result.Code, (Get-ProblemType $result), $result.Body.detail
    if ($null -ne $result.Body.currentVersion) {
        $text += ' (expectedVersion {0}, currentVersion {1})' -f $result.Body.expectedVersion, $result.Body.currentVersion
    }
    if ($result.Body.errors) {
        $fields = foreach ($p in $result.Body.errors.PSObject.Properties) { '{0} = {1}' -f $p.Name, $p.Value }
        $text += ' [errors: ' + ($fields -join '; ') + ']'
    }
    return $text
}

# POST JSON; mã HTTP không phải 201 thì in lỗi và dừng
function Invoke-Setup([string]$path, [string]$json, [string]$idempotencyKey) {
    $result = Send-Request 'POST' $path $json $idempotencyKey
    if ($result.Code -ne 201) {
        Write-Host "POST $path -> $(Format-Result $result)" -ForegroundColor Red
        exit 2
    }
    return $result.Body
}

function New-Product([string]$sku, [int]$stock) {
    return Invoke-Setup '/api/products' ('{"sku":"' + $sku + '","name":"Sản phẩm ' + $sku +
            '","category":"test","price":100000,"stock":' + $stock + '}')
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
Write-Host '== Case 1: admin sửa sản phẩm sau khi có đơn, theo version đọc trước khi có đơn'
$buyer = Invoke-Setup '/api/users' ('{"email":"plu' + $run + '@example.com","fullName":"Khách ' + $run +
        '","username":"plu' + $run + '","password":"matkhau123"}')
$product = New-Product "PLU-$run-1" 10
$path = "/api/products/$($product.id)"
Write-Host "   tạo sản phẩm id $($product.id): $(Format-Product $product); người mua id $($buyer.id)"

$adminView = (Send-Request 'GET' $path).Body
Write-Host "   1. Admin GET          → $(Format-Product $adminView)"

$orderJson = '{"userId":' + $buyer.id + ',"items":[{"productId":' + $product.id + ',"quantity":3}]}'
Write-Host "   2. Khách POST /api/orders $orderJson"
$order = Send-Request 'POST' '/api/orders' $orderJson "plu-$run"
$afterOrder = (Send-Request 'GET' $path).Body
Write-Host "            → $($order.Code), đơn id $($order.Body.id); sản phẩm lúc này: $(Format-Product $afterOrder)"

# Admin đổi giá trên form mở từ bước 1 (version lúc đó)
$priceJson = '{"price":90000,"version":' + $adminView.version + '}'
Write-Host "   3. Admin đổi giá, version đọc ở bước 1: PATCH $priceJson"
$repriced = Send-Request 'PATCH' $path $priceJson
Write-Host "            → $(Format-Result $repriced)"

$current = (Send-Request 'GET' $path).Body
$stockJson = '{"stock":15,"version":' + $current.version + '}'
Write-Host "   4. Admin sửa tồn kho bằng PATCH: $stockJson"
$stockPatch = Send-Request 'PATCH' $path $stockJson
Write-Host "            → $(Format-Result $stockPatch)"

$restockJson = '{"delta":5}'
Write-Host "   5. Admin nhập thêm 5 cái: POST $path/stock-adjustments $restockJson"
$restock = Send-Request 'POST' "$path/stock-adjustments" $restockJson "plu-$run-restock"
Write-Host "            → $(Format-Result $restock)"

$problems = New-Object System.Collections.Generic.List[string]
if ($order.Code -ne 201) {
    $problems.Add("đặt hàng nhận $($order.Code), mong đợi 201")
}
if ($afterOrder.version -ne $adminView.version) {
    $problems.Add("đơn hàng làm đổi version sản phẩm ($($adminView.version) → $($afterOrder.version)): PATCH theo version đọc trước đơn sẽ bị 409")
}
if ($repriced.Code -ne 200 -or $repriced.Body.stock -ne 7) {
    $problems.Add("admin đổi giá với version đọc trước đơn, mong đợi 200 và kho vẫn 7, nhận $(Format-Result $repriced)")
}
if ($stockPatch.Code -ne 400 -or -not $stockPatch.Body.errors.stock) {
    $problems.Add("PATCH có stock, mong đợi 400 với errors.stock, nhận $(Format-Result $stockPatch)")
}
if ($restock.Code -ne 200 -or $restock.Body.stock -ne 12) {
    $problems.Add("nhập thêm 5 cái, mong đợi 200 với stock 12 (7 + 5), nhận $(Format-Result $restock)")
}
Write-Verdict 'case 1' $problems 'đơn hàng không làm admin bị 409; tồn kho chỉ đổi qua stock-adjustments, kho đúng 7 + 5 = 12'

# ---------------------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host "== Case 2: $Concurrent PATCH cùng gửi version 0, gửi cùng lúc"
$product = New-Product "PLU-$run-2" 10
$path = "/api/products/$($product.id)"
Write-Host "   tạo sản phẩm id $($product.id): $(Format-Product $product)"
Write-Host "   PATCH {""price"":<1000 + i>,""version"":0}, i = 1..$Concurrent"

# Tạo sẵn mọi request rồi mới gửi, để chúng đi gần như cùng một lúc
$requests = @(for ($i = 1; $i -le $Concurrent; $i++) {
    New-Request 'PATCH' $path ('{"price":' + (1000 + $i) + ',"version":0}')
})
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = @(foreach ($r in $requests) { $client.SendAsync($r) })
try {
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
} catch {
    # request lỗi kết nối: xem ở từng task bên dưới
}
$watch.Stop()
Write-Host "   xong sau $($watch.ElapsedMilliseconds) ms"

$winners = New-Object System.Collections.Generic.List[object]
$staleRejected = 0     # 409 ở bước so version: lúc request đọc sản phẩm thì đã có request khác sửa xong
$versionRejected = 0   # 409 do @Version khi ghi: đọc lúc còn version 0, nhưng có request khác ghi xen vào
$others = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $tasks.Count; $i++) {
    if ($tasks[$i].IsFaulted -or $tasks[$i].IsCanceled) {
        $others.Add("request $($i + 1): lỗi kết nối / hết giờ")
        continue
    }
    $result = Read-Response $tasks[$i].Result
    if ($result.Code -eq 200) {
        $winners.Add($result.Body)
    } elseif ($result.Code -eq 409 -and (Get-ProblemType $result) -eq 'concurrent-modification') {
        if ($null -ne $result.Body.currentVersion) { $staleRejected++ } else { $versionRejected++ }
    } else {
        $others.Add("request $($i + 1): $(Format-Result $result)")
    }
}
$final = (Send-Request 'GET' $path).Body

$winnerPrices = ($winners | ForEach-Object { "price $($_.price)" }) -join ', '
Write-Host ('   200: {0} ({1})' -f $winners.Count, $winnerPrices) -ForegroundColor $(if ($winners.Count -eq 1) { 'Green' } else { 'Red' })
Write-Host ('   409 concurrent-modification: {0}' -f ($staleRejected + $versionRejected))
Write-Host ('       {0,3} chặn ở bước so version (lúc đọc thì sản phẩm đã lên version mới)' -f $staleRejected)
Write-Host ('       {0,3} chặn bởi @Version khi ghi (đọc lúc còn version 0, có request khác ghi xen vào)' -f $versionRejected)
foreach ($o in $others) { Write-Host "   khác: $o" -ForegroundColor Red }
Write-Host "   sản phẩm sau cùng: $(Format-Product $final)"

$problems = New-Object System.Collections.Generic.List[string]
if ($winners.Count -ne 1) {
    $problems.Add("$($winners.Count) request cùng gửi version 0 đều thành công, mong đợi đúng 1: request sau ghi đè request trước")
}
if ($others.Count -gt 0) {
    $problems.Add("$($others.Count) request nhận kết quả khác 200 / 409 concurrent-modification")
}
if ($final.version -ne 1 -or ($winners.Count -eq 1 -and [decimal]$final.price -ne [decimal]$winners[0].price)) {
    $problems.Add("sản phẩm sau cùng là $(Format-Product $final), mong đợi version 1 và giá của request thắng")
}
Write-Verdict 'case 2' $problems "chỉ 1 request thành công ($winnerPrices), $($Concurrent - 1) request còn lại nhận 409, không ai bị ghi đè"

# ---------------------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host '== Case 3: PATCH không có version'
$before = (Send-Request 'GET' $path).Body
$json = '{"price":999}'
Write-Host "   PATCH $json"
$result = Send-Request 'PATCH' $path $json
Write-Host "            → $(Format-Result $result)"

$problems = New-Object System.Collections.Generic.List[string]
if ($result.Code -ne 400 -or (Get-ProblemType $result) -ne 'validation' -or -not $result.Body.errors.version) {
    $problems.Add('mong đợi 400 validation có errors.version: không gửi version thì server không biết client đọc từ lúc nào')
}
if ((Send-Request 'GET' $path).Body.version -ne $before.version) {
    $problems.Add('sản phẩm đã bị sửa dù request không có version')
}
Write-Verdict 'case 3' $problems 'bị từ chối với 400, sản phẩm giữ nguyên'

$client.Dispose()

Write-Host ''
if ($failedCases.Count -eq 0) {
    Write-Host 'ĐÚNG (3/3 case): client gửi version cũ nhận 409, không ai ghi đè thay đổi của người khác' `
        -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $($failedCases -join ', ') không như mong đợi" -ForegroundColor Red
exit 1
