<#
.SYNOPSIS
  Gọi API danh sách 100 đơn kèm dòng hàng (GET /api/orders/with-items) trên app đang chạy, rồi đếm số câu SQL mà
  request đó chạy trên PostgreSQL.

.DESCRIPTION
  Gọi API thật (không chạy test). Cần app đang chạy, và PostgreSQL của docker compose đã nạp pg_stat_statements
  (như scripts/top-queries.ps1: docker compose up -d --force-recreate với docker-compose.yml hiện tại).
    1. Đăng ký một người dùng mới, tạo 2 sản phẩm, đặt -Orders đơn (mặc định 100), mỗi đơn 2 sản phẩm, đều qua API.
    2. pg_stat_statements_reset(), rồi gọi GET /api/orders/with-items?userId=...&size=<Orders> đúng một lần.
    3. Đọc pg_stat_statements: từng câu SQL và số lần chạy. Mong đợi 2 câu (trang đơn kèm dòng hàng, đếm) chứ không
       phải 1 + 100. Log của app có cùng con số trong khối "Logging session metrics" (thống kê Hibernate):
       "... ns executing 2 JDBC statements".
  Đừng gọi app hay DB từ nơi khác trong lúc chạy: pg_stat_statements đếm mọi câu SQL chạy trên DB.
  Bản N+1 để so sánh (chạy test, bản lỗi chạy 102 câu): scripts/red-green.ps1 -Scenario n-plus-one
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu không quá 2 câu SQL; 1 nếu nhiều hơn; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\n-plus-one.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\n-plus-one.ps1 -Orders 20
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 1000)][int]$Orders = 100
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')
$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, username, SKU không trùng lần trước
$maxStatements = 2

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

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

# Gửi một request, trả về @{ Code = mã HTTP; Body = nội dung }
function Send-Request([string]$method, [string]$path, [string]$json, [string]$idempotencyKey) {
    $request = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "$BaseUrl$path")
    if ($json) {
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    if ($idempotencyKey) { $request.Headers.Add('Idempotency-Key', $idempotencyKey) }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        return @{ Code = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
    } finally {
        $request.Dispose()
    }
}

# Gửi request, mã HTTP khác $expected thì in lỗi và dừng; trả về body đã đọc thành object
function Invoke-Api([string]$method, [string]$path, [string]$json, [int]$expected, [string]$idempotencyKey) {
    $result = Send-Request $method $path $json $idempotencyKey
    if ($result.Code -ne $expected) {
        Stop-Script "$method $path -> $($result.Code) (mong đợi $expected): $($result.Body)"
    }
    return $result.Body | ConvertFrom-Json
}

Write-Host "Đếm số câu SQL của GET /api/orders/with-items cho $Orders đơn, mỗi đơn 2 dòng hàng"
Write-Host ''

# ---------------------------------------------------------------------------------------------------------------
# 1. Kiểm tra app, pg_stat_statements

try {
    [void](Send-Request 'GET' '/api/products?size=1' $null $null)
} catch {
    Stop-Script "Không gọi được app ở $BaseUrl ($($_.Exception.Message)). Chạy app trước: .\mvnw spring-boot:run"
}
$preload = Invoke-Docker ($psql + @('-tAc', 'SHOW shared_preload_libraries'))
if ($preload.ExitCode -ne 0) {
    Stop-Script "Không chạy được psql trong container $container. Chạy: docker compose up -d`n  $($preload.Lines -join "`n  ")"
}
if (($preload.Lines -join '') -notmatch 'pg_stat_statements') {
    Stop-Script ("PostgreSQL chưa nạp pg_stat_statements: container đang chạy theo docker-compose.yml cũ.`n" +
                 "    git pull origin master`n" +
                 "    docker compose up -d --force-recreate")
}
[void](Invoke-Sql 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements')

# ---------------------------------------------------------------------------------------------------------------
# 2. Dữ liệu qua API: người dùng mới, 2 sản phẩm, $Orders đơn

$user = Invoke-Api 'POST' '/api/users' (@{
    email    = "nplus1.$run@example.com"
    fullName = 'Khách N+1'
    username = "nplus1$run"
    password = 'Secret123!'
} | ConvertTo-Json -Compress) 201
$productIds = foreach ($i in 1, 2) {
    (Invoke-Api 'POST' '/api/products' (@{
        sku = "NPLUS1-$run-$i"; name = "Sản phẩm N+1 $i"; category = 'NPLUS1'; price = 100000 * $i; stock = 1000000
    } | ConvertTo-Json -Compress) 201).id
}
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$orderJson = '{{"userId":{0},"items":[{{"productId":{1},"quantity":1}},{{"productId":{2},"quantity":1}}]}}' -f
             $user.id, $productIds[0], $productIds[1]
for ($i = 1; $i -le $Orders; $i++) {
    [void](Invoke-Api 'POST' '/api/orders' $orderJson 201 ([guid]::NewGuid().ToString('N')))
}
Write-Host ("Người dùng {0}: đặt {1} đơn qua API, mỗi đơn 2 sản phẩm ({2:N1} giây)" -f $user.id, $Orders, $watch.Elapsed.TotalSeconds)

# ---------------------------------------------------------------------------------------------------------------
# 3. Reset thống kê, gọi API danh sách đúng một lần

[void](Invoke-Sql 'SELECT pg_stat_statements_reset()')
$page = Invoke-Api 'GET' "/api/orders/with-items?userId=$($user.id)&size=$Orders" $null 200
$withTwoItems = @($page.content | Where-Object { @($_.items).Count -eq 2 }).Count
Write-Host ("GET /api/orders/with-items?userId={0}&size={1} -> {2} đơn, {3} đơn có đủ 2 dòng hàng" -f
            $user.id, $Orders, @($page.content).Count, $withTwoItems)
if (@($page.content).Count -ne $Orders -or $withTwoItems -ne $Orders) {
    Stop-Script 'Danh sách trả về không đúng số đơn / số dòng hàng mong đợi'
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Đọc pg_stat_statements

$rows = Invoke-Sql ("SELECT calls || '|' || left(regexp_replace(query, '\s+', ' ', 'g'), 110) FROM pg_stat_statements " +
                    "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) " +
                    "AND query NOT LIKE '%pg_stat_statements%' ORDER BY calls DESC, query")
$statements = foreach ($row in $rows) {
    $parts = $row -split '\|', 2
    [pscustomobject]@{ 'Số lần' = [int]$parts[0]; 'Câu SQL (pg_stat_statements)' = $parts[1] }
}
Write-Host ''
$statements | Format-Table -AutoSize | Out-String -Width 160 | Write-Host

# BEGIN / SET / SHOW là lệnh điều khiển của driver, không phải câu đọc dữ liệu; COMMIT của app không được ghi lại
$queries = @($statements | Where-Object { $_.'Câu SQL (pg_stat_statements)' -notmatch '^(BEGIN|COMMIT|ROLLBACK|SET|SHOW)\b' })
$total = ($queries | Measure-Object -Property 'Số lần' -Sum).Sum
Write-Host "Câu SQL đọc dữ liệu: $total (không tính BEGIN READ ONLY). Log của app, khối 'Logging session metrics' ngay sau"
Write-Host "dòng COMMIT OrderService.listByUserWithItems, ghi cùng con số: '... ns executing $total JDBC statements'."
Write-Host ''
if ($total -le $maxStatements) {
    Write-Host "ĐÚNG: $total câu SQL cho $Orders đơn kèm dòng hàng, không tăng theo số đơn (N+1 sẽ là 1 + $Orders câu)" -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $total câu SQL cho $Orders đơn, nhiều hơn ${maxStatements}: có dấu hiệu N+1" -ForegroundColor Red
exit 1
