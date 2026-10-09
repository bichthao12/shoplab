<#
.SYNOPSIS
  Gọi API vài phút với tỉ lệ request giống một shop thật, rồi lấy các câu SQL tốn tổng thời gian nhất từ
  pg_stat_statements.

.DESCRIPTION
  Gọi API thật (không chạy test). Cần:
    1. PostgreSQL của docker compose đã nạp pg_stat_statements: docker compose up -d --force-recreate với
       docker-compose.yml hiện tại (tạo lại container theo cấu hình mới, dữ liệu giữ nguyên).
    2. Nên có dữ liệu lớn (scripts/seed-big-data.ps1): ít dữ liệu thì câu nào cũng nhanh, khó thấy câu nào đáng sửa.
    3. App đang chạy, nên tắt log SQL để app không chậm vì ghi log:
         .\mvnw spring-boot:run "-Dspring-boot.run.arguments=--logging.level.sql=INFO --logging.level.tx=INFO"

  Các bước:
    1. Tạo extension pg_stat_statements nếu chưa có.
    2. Lấy mẫu 2.000 user đang ACTIVE và 2.000 đơn có sẵn (đọc thẳng DB), tạo 50 sản phẩm tồn kho lớn qua API.
    3. pg_stat_statements_reset(): từ đây chỉ tính các câu SQL chạy trong lúc gọi API.
    4. Gọi API trong -Minutes phút, lúc nào cũng có -Parallel request đang chạy, chọn ngẫu nhiên theo tỉ lệ:
         35% GET  /api/orders?userId=...   đơn của tôi (trang đầu, 20 đơn)
         20% GET  /api/orders/{id}         chi tiết một đơn
         15% GET  /api/users/{id}          hồ sơ
         10% GET  /api/products?category=  danh sách sản phẩm theo loại
         15% POST /api/orders              đặt 1–3 sản phẩm, Idempotency-Key mới
          5% POST /api/users               đăng ký
    5. In số request, lỗi, thời gian phản hồi của từng loại, rồi -Top câu SQL có tổng thời gian chạy lớn nhất
       (scripts/top-queries.sql, chạy riêng được).
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu chạy xong, 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\top-queries.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\top-queries.ps1 -Minutes 1 -Parallel 8 -Top 5
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [double]$Minutes = 3,
    [int]$Parallel = 16,
    [int]$Top = 3
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')
$sqlFile = Join-Path $PSScriptRoot 'top-queries.sql'
$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: SKU, email, username không trùng lần trước
$random = New-Object System.Random
$script:seq = 0

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

# Chạy một câu SQL, trả về các dòng kết quả (không tiêu đề). Lỗi thì dừng script.
function Invoke-Sql([string]$sql) {
    $result = Invoke-Docker ($psql + @('-tAc', $sql))
    if ($result.ExitCode -ne 0) {
        Stop-Script "Lỗi khi chạy SQL: $sql`n  $($result.Lines -join "`n  ")"
    }
    return $result.Lines
}

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = $Parallel
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

function New-Request([string]$method, [string]$path, [string]$json) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    return $request
}

function Pick($items) {
    return $items[$random.Next($items.Count)]
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Kiểm tra app, pg_stat_statements

Write-Host "Gọi API $Minutes phút ($Parallel request song song), rồi lấy $Top câu SQL tốn tổng thời gian nhất"
Write-Host ''

try {
    $probe = $client.GetAsync("$BaseUrl/api/products?size=1").GetAwaiter().GetResult()
    if (-not $probe.IsSuccessStatusCode) { throw "HTTP $([int]$probe.StatusCode)" }
} catch {
    Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.Message)). Chạy app trước:`n  .\mvnw spring-boot:run `"-Dspring-boot.run.arguments=--logging.level.sql=INFO --logging.level.tx=INFO`""
}

$preload = Invoke-Docker ($psql + @('-tAc', 'SHOW shared_preload_libraries'))
if ($preload.ExitCode -ne 0) {
    Stop-Script "Không chạy được psql trong container $container. Chạy: docker compose up -d`n  $($preload.Lines -join "`n  ")"
}
if (($preload.Lines -join '') -notmatch 'pg_stat_statements') {
    Stop-Script ("PostgreSQL chưa nạp pg_stat_statements (shared_preload_libraries = '$($preload.Lines -join '')'):`n" +
                 "  container đang chạy theo docker-compose.yml cũ. Lấy bản mới rồi tạo lại container (dữ liệu giữ nguyên):`n" +
                 "    git pull origin master`n" +
                 "    docker compose up -d --force-recreate")
}
[void](Invoke-Sql 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements')
Write-Host 'pg_stat_statements: đã nạp, extension đã tạo' -ForegroundColor Green

# ---------------------------------------------------------------------------------------------------------------
# 2. Dữ liệu cho request: user, đơn có sẵn (đọc thẳng DB, trước khi reset nên không tính vào kết quả); sản phẩm mới

$userIds = @(Invoke-Sql ("SELECT u.id FROM users u JOIN accounts a ON a.user_id = u.id " +
                         "WHERE a.status = 'ACTIVE' ORDER BY random() LIMIT 2000") | ForEach-Object { [long]$_ })
$orderIds = @(Invoke-Sql 'SELECT id FROM orders ORDER BY random() LIMIT 2000' | ForEach-Object { [long]$_ })
if ($userIds.Count -eq 0) {
    Stop-Script 'DB chưa có user nào đang ACTIVE. Sinh dữ liệu trước: scripts/seed-big-data.ps1'
}
$totals = (Invoke-Sql 'SELECT (SELECT count(*) FROM users) || '' '' || (SELECT count(*) FROM orders)') -split ' '
Write-Host ("DB có {0:N0} user, {1:N0} đơn; lấy mẫu {2:N0} user ACTIVE, {3:N0} đơn" -f
            [long]$totals[0], [long]$totals[1], $userIds.Count, $orderIds.Count)

$categories = @('AO', 'QUAN', 'GIAY', 'TUI', 'PHUKIEN')
$productIds = New-Object System.Collections.Generic.List[long]
for ($i = 1; $i -le 50; $i++) {
    $body = @{
        sku      = "LOAD-$run-$i"
        name     = "Sản phẩm tải $i"
        category = $categories[$i % $categories.Count]
        price    = 50000 + 10000 * $random.Next(200)
        stock    = 10000000
    } | ConvertTo-Json -Compress
    $response = $client.SendAsync((New-Request 'POST' '/api/products' $body)).GetAwaiter().GetResult()
    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ([int]$response.StatusCode -ne 201) {
        Stop-Script "POST /api/products -> $([int]$response.StatusCode): $content"
    }
    $productIds.Add([long]($content | ConvertFrom-Json).id)
}
Write-Host "Tạo $($productIds.Count) sản phẩm, mỗi sản phẩm tồn kho 10.000.000"

# ---------------------------------------------------------------------------------------------------------------
# 3. Các loại request và tỉ lệ (tổng 100)

$operations = @(
    @{ Name = 'GET  /api/orders?userId='; Weight = 35; Make = {
        New-Request 'GET' "/api/orders?userId=$(Pick $userIds)&page=0&size=20" } }
    @{ Name = 'GET  /api/orders/{id}'; Weight = 20; Make = {
        New-Request 'GET' "/api/orders/$(if ($orderIds.Count -gt 0) { Pick $orderIds } else { 1 })" } }
    @{ Name = 'GET  /api/users/{id}'; Weight = 15; Make = {
        New-Request 'GET' "/api/users/$(Pick $userIds)" } }
    @{ Name = 'GET  /api/products?category='; Weight = 10; Make = {
        New-Request 'GET' "/api/products?category=$(Pick $categories)&page=0&size=20" } }
    @{ Name = 'POST /api/orders'; Weight = 15; Make = {
        $count = 1 + $random.Next(3)
        $items = @($productIds | Sort-Object { $random.Next() } | Select-Object -First $count |
                   ForEach-Object { @{ productId = $_; quantity = 1 + $random.Next(2) } })
        $body = @{ userId = (Pick $userIds); items = $items } | ConvertTo-Json -Compress -Depth 3
        $request = New-Request 'POST' '/api/orders' $body
        $request.Headers.Add('Idempotency-Key', [guid]::NewGuid().ToString('N'))
        $request } }
    @{ Name = 'POST /api/users'; Weight = 5; Make = {
        $script:seq++
        $body = @{
            email    = "load$run.$($script:seq)@example.com"
            fullName = "Khách tải $($script:seq)"
            username = "load$run$($script:seq)"
            password = 'Secret123!'
        } | ConvertTo-Json -Compress
        New-Request 'POST' '/api/users' $body } }
)
$cumulative = 0
foreach ($op in $operations) {
    $cumulative += $op.Weight
    $op.Upto = $cumulative
    $op.Latencies = New-Object System.Collections.Generic.List[double]
    $op.Errors = 0
    $op.ErrorSample = $null
}

function Select-Operation {
    $roll = $random.Next(100)
    foreach ($op in $operations) {
        if ($roll -lt $op.Upto) { return $op }
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Reset thống kê rồi gọi API

[void](Invoke-Sql 'SELECT pg_stat_statements_reset()')
Write-Host ''
Write-Host "Đã reset pg_stat_statements. Gọi API trong $Minutes phút..."

$frequency = [System.Diagnostics.Stopwatch]::Frequency
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = [TimeSpan]::FromMinutes($Minutes)
$nextProgress = [TimeSpan]::FromSeconds(15)
$pending = New-Object System.Collections.Generic.List[object]
$completed = 0

while ($true) {
    while ($watch.Elapsed -lt $deadline -and $pending.Count -lt $Parallel) {
        $op = Select-Operation
        $request = & $op.Make
        $pending.Add(@{ Op = $op; Request = $request; Start = [System.Diagnostics.Stopwatch]::GetTimestamp();
                        Task = $client.SendAsync($request) })
    }
    if ($pending.Count -eq 0) { break }

    $tasks = [System.Threading.Tasks.Task[]]@($pending | ForEach-Object { $_.Task })
    $index = [System.Threading.Tasks.Task]::WaitAny($tasks, 1000)
    if ($index -ge 0) {
        $done = $pending[$index]
        $pending.RemoveAt($index)
        $op = $done.Op
        $op.Latencies.Add(([System.Diagnostics.Stopwatch]::GetTimestamp() - $done.Start) * 1000.0 / $frequency)
        if ($done.Task.IsFaulted) {
            $op.Errors++
            if (-not $op.ErrorSample) { $op.ErrorSample = $done.Task.Exception.InnerException.Message }
        } else {
            $response = $done.Task.Result
            $code = [int]$response.StatusCode
            if ($code -lt 200 -or $code -ge 300) {
                $op.Errors++
                if (-not $op.ErrorSample) {
                    $op.ErrorSample = "$code " + $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
            }
            $response.Dispose()
        }
        $done.Request.Dispose()
        $completed++
    }

    if ($watch.Elapsed -ge $nextProgress) {
        $errors = ($operations | ForEach-Object { $_.Errors } | Measure-Object -Sum).Sum
        Write-Host ('  {0:m\:ss}  {1,8:N0} request ({2:N0}/s), lỗi {3:N0}' -f
                    $watch.Elapsed, $completed, ($completed / $watch.Elapsed.TotalSeconds), $errors)
        $nextProgress = $nextProgress.Add([TimeSpan]::FromSeconds(15))
    }
}
$watch.Stop()

# ---------------------------------------------------------------------------------------------------------------
# 5. Kết quả phía app, rồi phía DB

Write-Host ''
Write-Host ('== Request: {0:N0} trong {1:m\:ss} ({2:N0}/s)' -f $completed, $watch.Elapsed, ($completed / $watch.Elapsed.TotalSeconds))
$rows = foreach ($op in $operations) {
    $sorted = @($op.Latencies | Sort-Object)
    [pscustomobject]@{
        'Request'          = $op.Name
        'Số lần'           = $sorted.Count
        'Lỗi'              = $op.Errors
        'Trung bình (ms)'  = if ($sorted.Count) { [math]::Round(($sorted | Measure-Object -Average).Average, 1) } else { '' }
        'p95 (ms)'         = if ($sorted.Count) { [math]::Round($sorted[[int][math]::Ceiling($sorted.Count * 0.95) - 1], 1) } else { '' }
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
foreach ($op in $operations | Where-Object { $_.ErrorSample }) {
    $sample = $op.ErrorSample
    if ($sample.Length -gt 300) { $sample = $sample.Substring(0, 300) + '...' }
    Write-Host "  Lỗi đầu tiên của $($op.Name): $sample" -ForegroundColor Yellow
}

Write-Host "== $Top câu SQL tốn tổng thời gian nhất (pg_stat_statements, chỉ tính lúc gọi API)"
# docker cp thay vì truyền SQL qua tham số: PowerShell 5.1 làm mất dấu " trong tham số truyền cho lệnh ngoài
$copy = Invoke-Docker @('cp', $sqlFile, "${container}:/tmp/top-queries.sql")
if ($copy.ExitCode -ne 0) {
    Stop-Script "Không chép được file .sql vào container:`n  $($copy.Lines -join "`n  ")"
}
& docker @psql -v "top=$Top" -f /tmp/top-queries.sql
exit 0
