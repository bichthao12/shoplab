<#
.SYNOPSIS
  traceId trong log của tác vụ song song: gọi trang chi tiết đơn kèm header X-Trace-Id, rồi in mọi dòng log của request
  đó theo luồng, để thấy log của các luồng order-detail-N (đơn, sản phẩm, hai nguồn chậm) có traceId hay không.

.DESCRIPTION
  Gọi API và đọc log (không chạy test, không đọc DB). Script tự build và chạy app (java -jar) ở cổng -Port (mặc định
  8082, app của bạn ở 8080 cứ để chạy), log ghi ra file để đọc lại, có log SQL và BEGIN / COMMIT; xong thì dừng app.
    1. Lấy một đơn qua GET /api/orders?size=1 (DB chưa có đơn thì tạo qua API).
    2. GET /api/orders/{id}/detail với X-Trace-Id: demo-<số>, kiểm tra header trả về.
    3. Đọc log của app: các dòng có traceId đó, và các dòng của luồng order-detail-N trong cùng khoảng thời gian.
       In bảng: giờ, luồng, traceId trên dòng, nội dung.
  -Bug: chạy bản lỗi (scripts/bugs/18-trace-id-lost.patch: giao việc cho executor mà không chép MDC) để thấy log của
  luồng order-detail-N mất traceId. Script copy code sang thư mục tạm, áp patch, build, chạy, rồi xoá.
  Cần JDK 21, Docker (PostgreSQL của docker compose), git nếu dùng -Bug.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu mọi dòng của luồng order-detail-N có traceId (với -Bug: nếu thấy dòng mất traceId); 1 nếu không;
  2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\trace-id.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\trace-id.ps1 -Bug
#>
param(
    [ValidateRange(1, 65535)][int]$Port = 8082,
    [switch]$Bug,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://localhost:$Port"
$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

. (Join-Path $PSScriptRoot 'lib/shoplab-app.ps1')

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.DefaultRequestHeaders.ExpectContinue = $false

function Send-Request([string]$method, [string]$path, [string]$json, [hashtable]$headers) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$baseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    if ($headers) { foreach ($k in $headers.Keys) { $request.Headers.Add($k, $headers[$k]) } }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $values = $null
        $traceHeader = $null
        if ($response.Headers.TryGetValues('X-Trace-Id', [ref]$values)) { $traceHeader = @($values)[0] }
        return @{ Code = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                  TraceId = $traceHeader }
    } finally {
        $request.Dispose()
    }
}

function Invoke-Api([string]$method, [string]$path, [string]$json, [int]$expected, [hashtable]$headers) {
    $r = Send-Request $method $path $json $headers
    if ($r.Code -ne $expected) { Stop-Script "$method $path -> $($r.Code) (mong đợi $expected): $($r.Body)" }
    return $r.Body | ConvertFrom-Json
}

Write-Host 'traceId trong log của tác vụ song song (trang chi tiết đơn)'
Write-Host ''

$health = Invoke-Native 'docker' @('inspect', '-f', '{{.State.Health.Status}}', 'shoplab-postgres')
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script 'Container shoplab-postgres chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d'
}

$tools = Find-Java
$build = $null
$app = $null
try {
    # ---------------------------------------------------------------------------------------------------------------
    # 1. Build, chạy app

    if ($Bug) {
        Write-Host 'Bản lỗi: copy code sang thư mục tạm, áp scripts/bugs/18-trace-id-lost.patch, build (khoảng 1 phút)...'
        $build = New-BuggyBuild '18-trace-id-lost.patch'
        $dir = $build.Dir
        $jar = $build.Jar
        $log = Join-Path $dir 'app.log'
    } else {
        $dir = $root
        $existing = Get-ChildItem (Join-Path $root 'target') -Filter 'shoplab-*.jar' -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
        if ($SkipBuild -and $existing) {
            $jar = $existing.FullName
        } else {
            Write-Host 'Build app: mvnw -DskipTests package (khoảng 1 phút)...'
            $jar = Build-Jar $root
        }
        New-Item -ItemType Directory -Force -Path (Join-Path $root 'target/trace-id') | Out-Null
        $log = Join-Path $root 'target/trace-id/app.log'
    }
    Write-Host "Chạy app ở cổng $Port, log SQL bật, log ghi ra $log ..."
    $app = Start-ShoplabApp $tools.Java $jar $dir $Port $log @() @() $true

    # ---------------------------------------------------------------------------------------------------------------
    # 2. Một đơn, gọi trang chi tiết với X-Trace-Id

    $orders = Invoke-Api 'GET' '/api/orders?size=1' $null 200
    if (@($orders.content).Count -gt 0) {
        $orderId = @($orders.content)[0].id
    } else {
        $product = Invoke-Api 'POST' '/api/products' (@{ sku = "TRACE-$run"; name = 'Áo thun traceId'; category = 'TRACE'
                                                         price = 150000; stock = 10 } | ConvertTo-Json -Compress) 201
        $user = Invoke-Api 'POST' '/api/users' (@{ email = "trace.$run@example.com"; fullName = 'Khách traceId'
                                                   username = "trace$run"; password = 'Secret123!' } | ConvertTo-Json -Compress) 201
        $orderJson = '{{"userId":{0},"items":[{{"productId":{1},"quantity":1}}]}}' -f $user.id, $product.id
        $orderId = (Invoke-Api 'POST' '/api/orders' $orderJson 201 @{ 'Idempotency-Key' = [guid]::NewGuid().ToString('N') }).id
    }
    $traceId = "demo-$run"
    $sentAt = [DateTimeOffset]::Now
    $r = Send-Request 'GET' "/api/orders/$orderId/detail" $null @{ 'X-Trace-Id' = $traceId }
    $doneAt = [DateTimeOffset]::Now
    Write-Host ("GET /api/orders/{0}/detail với X-Trace-Id: {1} → {2}, header X-Trace-Id trả về: {3}" -f $orderId, $traceId,
                $r.Code, $r.TraceId)
    Start-Sleep -Seconds 1   # chờ log ghi xong ra file

    # ---------------------------------------------------------------------------------------------------------------
    # 3. Đọc log: dòng có traceId, và dòng của luồng order-detail-N trong lúc request chạy (app và script cùng một máy,
    #    cùng đồng hồ; bản lỗi không có dòng nào mang traceId để lần theo)

    # vd 2026-10-12T10:40:01.123+07:00 DEBUG 7923 --- [ order-detail-4] [demo-1] c.s.common.config.TransactionLogging : BEGIN ...
    $pattern = '^(\S+)\s+(\w+)\s+\d+\s+---\s+\[\s*([^\]]*)\]\s+\[([^\]]*)\]\s+(\S+)\s*:\s?(.*)$'
    $entries = @()
    foreach ($line in Get-Content $log -Encoding UTF8) {
        if ($line -match $pattern) {
            $entries += [pscustomobject]@{
                Time = [DateTimeOffset]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
                Thread = $Matches[3]; TraceId = $Matches[4]; Logger = $Matches[5]; Message = $Matches[6]
            }
        }
    }
    $from = $sentAt.AddMilliseconds(-200)
    $to = $doneAt.AddMilliseconds(500)
    $requestLines = @($entries | Where-Object {
        $_.TraceId -eq $traceId -or ($_.Thread -like '*order-detail-*' -and $_.Time -ge $from -and $_.Time -le $to)
    })
    $table = $requestLines | ForEach-Object {
        $text = $_.Message
        if ($text.Length -gt 70) { $text = $text.Substring(0, 70) + '...' }
        $shown = $_.TraceId
        if (-not $shown) { $shown = '(trống)' }
        [pscustomobject]@{ 'Giờ' = $_.Time.ToLocalTime().ToString('HH:mm:ss.fff'); 'Luồng' = $_.Thread.Trim(); 'traceId' = $shown
                           'Nội dung' = $text }
    }
    Write-Host ''
    Write-Host 'Log của request (order-detail-N: luồng chạy từng nguồn của trang; luồng request chỉ chờ, không ghi log):' -ForegroundColor Cyan
    $table | Format-Table -AutoSize | Out-String -Width 170 | Write-Host

    if ($requestLines.Count -eq 0) { Stop-Script "Không thấy dòng log nào của request trong $log" }
    $taskLines = @($requestLines | Where-Object { $_.Thread -like '*order-detail-*' })
    $missing = @($taskLines | Where-Object { $_.TraceId -ne $traceId })
    Write-Host ("Dòng của luồng order-detail-N: {0}, thiếu traceId: {1}" -f $taskLines.Count, $missing.Count)
    Write-Host ''
    if ($Bug) {
        if ($missing.Count -gt 0) {
            Write-Host (('Đúng như dự đoán: bản lỗi giao việc cho executor mà không chép MDC, nên {0} dòng của luồng order-detail-N ' +
                         'không có traceId: không lọc được chúng theo request.') -f $missing.Count) -ForegroundColor Green
            exit 0
        }
        Write-Host 'Lạ: bản lỗi mà log của luồng order-detail-N vẫn có traceId' -ForegroundColor Yellow
        exit 1
    }
    if ($taskLines.Count -gt 0 -and $missing.Count -eq 0) {
        Write-Host 'ĐÚNG: mọi dòng log của request, kể cả của các luồng order-detail-N, đều có traceId (MDC được chép sang luồng mới)' -ForegroundColor Green
        exit 0
    }
    Write-Host "SAI: $($missing.Count) dòng của luồng order-detail-N không có traceId" -ForegroundColor Red
    exit 1
} finally {
    Stop-ShoplabApp $app
    if ($build) { Remove-Item -Recurse -Force $build.Dir -ErrorAction SilentlyContinue }
}
