<#
.SYNOPSIS
  Bắn song song vào bộ đếm lượt xem sản phẩm của app đang chạy: N lần GET /api/products/{id} cùng lúc, rồi so số lượt
  xem tăng thêm (GET /api/products/{id}/views) với N.

.DESCRIPTION
  Gọi API thật (không chạy test, không đọc DB). Cần app đang chạy.
    1. Tạo qua API một sản phẩm (bỏ qua nếu có -ProductId), đọc số lượt xem hiện có.
    2. Gửi -Requests (mặc định 2000) request GET /api/products/{id} cùng lúc, tối đa -Connections (mặc định 200) kết nối.
    3. Đọc lại số lượt xem: phải tăng đúng bằng số request trả 200.
  App đếm bằng ConcurrentHashMap + LongAdder (ProductViewCounter). Bản đếm sai (HashMap trong bean singleton) chỉ có
  trong test và trong scripts/bugs/17-view-counter-hashmap.patch. Xem nó đếm sai:
    -Bug: script copy code sang thư mục tạm, áp patch, build, chạy bản lỗi ở cổng -BugPort (8081, app của bạn ở 8080
          cứ để chạy), bắn -Requests (mặc định 10000) request, rồi dừng và xoá bản lỗi. Cần git, JDK. Khoảng 1–2 phút.
    .\mvnw test "-Dtest=ProductViewCounterTests"       (dòng [race] trong log: HashMap đếm thiếu bao nhiêu)
    .\scripts\red-green.ps1 -Scenario view-counter     (test đỏ khi áp bản HashMap)
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đếm đúng (với -Bug: nếu thấy bản lỗi đếm sai); 1 nếu không; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\view-counter.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\view-counter.ps1 -ProductId 1 -Requests 10000

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\view-counter.ps1 -Bug
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [long]$ProductId = 0,
    [ValidateRange(1, 100000)][int]$Requests = 2000,
    [ValidateRange(1, 1000)][int]$Connections = 200,
    [switch]$Bug,
    [ValidateRange(1, 65535)][int]$BugPort = 8081
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http
# Windows PowerShell 5.1 (.NET Framework) mặc định chỉ mở 2 kết nối tới một máy chủ: request sẽ xếp hàng chứ không chạy
# cùng lúc
[System.Net.ServicePointManager]::DefaultConnectionLimit = $Connections

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$vi = [System.Globalization.CultureInfo]::GetCultureInfo('vi-VN')

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

# Giới hạn số kết nối cả trên PowerShell 7 (.NET mới mặc định không giới hạn): mở hàng nghìn kết nối cùng lúc thì
# hàng chờ kết nối của Tomcat (100) tràn. Request vượt số kết nối xếp hàng ở phía client, nên cho chờ lâu.
$handler = New-Object System.Net.Http.HttpClientHandler
try { $handler.MaxConnectionsPerServer = $Connections } catch { }
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromMinutes(5)
$client.DefaultRequestHeaders.ExpectContinue = $false

function Invoke-Api([string]$method, [string]$path, [string]$json, [int]$expected) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
    } catch {
        Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.InnerException.Message)). Chạy app trước: .\mvnw spring-boot:run"
    }
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ([int]$response.StatusCode -ne $expected) {
        Stop-Script "$method $path -> $([int]$response.StatusCode) (mong đợi $expected): $body"
    }
    return $body | ConvertFrom-Json
}

function Format-Count([double]$n) {
    return $n.ToString('#,0', $vi)
}

$root = Split-Path -Parent $PSScriptRoot
$bugBuild = $null
$bugApp = $null

Write-Host 'Bộ đếm lượt xem sản phẩm: bắn song song GET /api/products/{id}'
Write-Host ''
try {
if ($Bug) {
    . (Join-Path $PSScriptRoot 'lib/shoplab-app.ps1')
    if (-not $PSBoundParameters.ContainsKey('Requests')) { $Requests = 10000 }
    $tools = Find-Java
    Write-Host 'Bản lỗi: copy code sang thư mục tạm, áp scripts/bugs/17-view-counter-hashmap.patch, build (khoảng 1 phút)...'
    $bugBuild = New-BuggyBuild '17-view-counter-hashmap.patch'
    Write-Host "Chạy bản lỗi ở cổng $BugPort (log: $(Join-Path $bugBuild.Dir 'app.log'))..."
    $bugApp = Start-ShoplabApp $tools.Java $bugBuild.Jar $bugBuild.Dir $BugPort (Join-Path $bugBuild.Dir 'app.log') @() @()
    $BaseUrl = "http://localhost:$BugPort"
}


# ---------------------------------------------------------------------------------------------------------------
# 1. Sản phẩm, số lượt xem ban đầu

if ($ProductId -le 0) {
    $product = Invoke-Api 'POST' '/api/products' (@{
        sku = "VIEWS-$run"; name = 'Áo thun đếm lượt xem'; category = 'VIEWS'; price = 150000; stock = 10
    } | ConvertTo-Json -Compress) 201
    $ProductId = $product.id
    Write-Host "Tạo qua API: sản phẩm $ProductId"
}
$before = (Invoke-Api 'GET' "/api/products/$ProductId/views" $null 200).views
Write-Host "Lượt xem hiện có: $(Format-Count $before)"

# ---------------------------------------------------------------------------------------------------------------
# 2. Bắn song song: gửi hết rồi mới chờ

Write-Host "Gửi $(Format-Count $Requests) request GET /api/products/$ProductId cùng lúc (tối đa $Connections kết nối)..."
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task[System.Net.Http.HttpResponseMessage]]'
for ($i = 0; $i -lt $Requests; $i++) {
    $tasks.Add($client.GetAsync("$BaseUrl/api/products/$ProductId"))
}
try {
    [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
} catch {
    # lỗi từng request được đếm bên dưới
}
$watch.Stop()
$ok = 0
$failed = @{}
foreach ($t in $tasks) {
    if ($t.Status -eq 'RanToCompletion' -and [int]$t.Result.StatusCode -eq 200) {
        $ok++
    } else {
        $key = 'hết giờ chờ (5 phút)'
        if ($t.Status -eq 'Faulted') { $key = "lỗi kết nối: $($t.Exception.InnerException.GetType().Name) $($t.Exception.InnerException.Message)" }
        if ($t.Status -eq 'RanToCompletion') { $key = "HTTP $([int]$t.Result.StatusCode)" }
        $failed[$key] = 1 + [int]$failed[$key]
    }
    if ($t.Status -eq 'RanToCompletion') { $t.Result.Dispose() }
}
Write-Host ("Xong sau {0} ms: {1} request trả 200" -f [Math]::Round($watch.Elapsed.TotalMilliseconds), (Format-Count $ok))
foreach ($k in $failed.Keys) { Write-Host "  $k ×$($failed[$k])" -ForegroundColor Yellow }

# ---------------------------------------------------------------------------------------------------------------
# 3. So số lượt xem

$after = (Invoke-Api 'GET' "/api/products/$ProductId/views" $null 200).views
$counted = $after - $before
Write-Host ("Lượt xem: {0} → {1}, tăng {2}; số request trả 200: {3}" -f (Format-Count $before), (Format-Count $after),
            (Format-Count $counted), (Format-Count $ok))
Write-Host ''
if ($Bug) {
    if ($counted -lt $ok) {
        Write-Host ("Đúng như dự đoán: bản HashMap mất {0} / {1} lượt xem. Hai request cùng đọc một số rồi cùng ghi số + 1." -f
                    (Format-Count ($ok - $counted)), (Format-Count $ok)) -ForegroundColor Green
        exit 0
    }
    Write-Host ('Lần này bản HashMap không mất lượt nào: race chỉ xảy ra khi hai request rơi đúng vào khoảng giữa get và put. ' +
                'Chạy lại, hoặc tăng -Requests.') -ForegroundColor Yellow
    exit 1
}
if ($counted -eq $ok) {
    Write-Host ("ĐÚNG: đếm đủ {0} lượt xem gửi cùng lúc, không mất lượt nào (ConcurrentHashMap + LongAdder)" -f (Format-Count $ok)) -ForegroundColor Green
    exit 0
}
Write-Host ("SAI: mất {0} lượt xem: đọc rồi ghi không nguyên tử, request song song ghi đè lên nhau" -f (Format-Count ($ok - $counted))) -ForegroundColor Red
exit 1
} finally {
    if ($Bug) {
        Stop-ShoplabApp $bugApp
        if ($bugBuild) { Remove-Item -Recurse -Force $bugBuild.Dir -ErrorAction SilentlyContinue }
    }
}
