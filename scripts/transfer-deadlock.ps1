<#
.SYNOPSIS
  Chuyển tiền A->B và B->A cùng lúc qua API, kiểm tra không deadlock và số dư đúng.

.DESCRIPTION
  Cần app đang chạy (.\mvnw spring-boot:run). Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Các bước:
    2. tạo hai người dùng A, B (POST /api/users)
    3. tạo ví cho mỗi người (POST /api/wallets) và nạp InitialBalance (POST /api/wallets/{id}/deposits)
    4. bắn Pairs lượt A->B (1 đồng / lượt) và Pairs lượt B->A (2 đồng / lượt) cùng lúc
       (POST /api/wallets/transfers, tối đa Parallel request song song, mỗi lượt một Idempotency-Key riêng)
    5. đọc lại số dư hai ví (GET /api/wallets/{id})

  App khoá hai ví theo thứ tự id tăng dần, nên mọi lượt đều chuyển xong:
    ví A = InitialBalance - Pairs*1 + Pairs*2,  ví B = InitialBalance + Pairs*1 - Pairs*2,  tổng không đổi.
  Nếu app khoá theo thứ tự tham số (ví nguồn trước), sẽ thấy response 409 deadlock và có thể cả request hết giờ chờ.

  Thoát với mã 0 nếu mọi lượt chuyển đều 200 và số dư đúng; 1 nếu sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1 -Pairs 500 -Parallel 100
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Pairs = 100,
    [int]$Parallel = 50,
    [int]$InitialBalance = 1000
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

if ($InitialBalance -lt 2 * $Pairs) {
    # B->A trừ 2 đồng / lượt: nếu mọi lượt B->A chạy trước, ví B cần ít nhất 2 * Pairs
    Write-Host "InitialBalance phải >= 2 * Pairs ($(2 * $Pairs)) để không lượt nào thiếu tiền" -ForegroundColor Red
    exit 2
}

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, username, Idempotency-Key không trùng

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = $Parallel          # tối đa Parallel request đang chạy cùng lúc
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

function New-JsonPost([string]$path, [string]$json, [string]$idempotencyKey) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl$path")
    $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    if ($idempotencyKey) { $request.Headers.Add('Idempotency-Key', $idempotencyKey) }
    return $request
}

# Gửi một request, trả về @{ Code = mã HTTP; Body = nội dung }
function Send-Request($request) {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    return @{ Code = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
}

# POST JSON, trả về body đã đọc thành object. Mã HTTP không phải 2xx thì in lỗi và dừng.
function Invoke-Setup([string]$path, [string]$json, [string]$idempotencyKey) {
    $result = Send-Request (New-JsonPost $path $json $idempotencyKey)
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

Write-Host '== Bước 2: tạo hai người dùng A, B'
$userIds = @{}
foreach ($name in 'A', 'B') {
    $user = Invoke-Setup '/api/users' (@{
        email    = "transfer$run$($name.ToLower())@example.com"
        fullName = "Người dùng $name $run"
        username = "transfer$run$($name.ToLower())"
        password = 'matkhau123'
    } | ConvertTo-Json -Compress)
    $userIds[$name] = $user.id
    Write-Host "   $name`: userId = $($user.id)"
}

Write-Host "== Bước 3: tạo ví và nạp mỗi ví $InitialBalance"
$walletIds = @{}
foreach ($name in 'A', 'B') {
    $wallet = Invoke-Setup '/api/wallets' ('{"userId":' + $userIds[$name] + '}')
    $wallet = Invoke-Setup "/api/wallets/$($wallet.id)/deposits" ('{"amount":' + $InitialBalance + '}') "deposit-$run-$name"
    $walletIds[$name] = $wallet.id
    Write-Host "   ví $name`: id = $($wallet.id), số dư = $($wallet.balance)"
}

Write-Host "== Bước 4: $Pairs lượt A->B (1 / lượt) và $Pairs lượt B->A (2 / lượt) cùng lúc, $Parallel lượt chạy song song"
$abJson = '{"fromWalletId":' + $walletIds['A'] + ',"toWalletId":' + $walletIds['B'] + ',"amount":1}'
$baJson = '{"fromWalletId":' + $walletIds['B'] + ',"toWalletId":' + $walletIds['A'] + ',"amount":2}'
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = New-Object System.Collections.ArrayList
for ($i = 1; $i -le 2 * $Pairs; $i++) {
    # xen kẽ A->B, B->A: hai chiều cùng chạy suốt cả lượt bắn
    $json = if ($i % 2 -eq 1) { $abJson } else { $baJson }
    [void]$tasks.Add($client.SendAsync((New-JsonPost '/api/wallets/transfers' $json "transfer-$run-$i")))
}
try {
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks.ToArray())
} catch {
    # request lỗi kết nối / hết giờ: xem ở từng task bên dưới (mã 000)
}
$watch.Stop()
Write-Host ("   xong sau ~{0:N1} giây" -f $watch.Elapsed.TotalSeconds)

$results = foreach ($task in $tasks) {
    if ($task.IsFaulted -or $task.IsCanceled) {
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

Write-Host '   Mã HTTP (000 = không kết nối được / hết giờ chờ):'
$results | Group-Object Code | Sort-Object Name | ForEach-Object { Write-Host ('{0,12} {1}' -f $_.Count, $_.Name) }
$errors = @($results | Where-Object { $_.Type })
if ($errors.Count -gt 0) {
    Write-Host '   Loại lỗi:'
    $errors | Group-Object Type | Sort-Object Name | ForEach-Object { Write-Host ('{0,12} {1}' -f $_.Count, $_.Name) }
}

Write-Host '== Bước 5: đọc lại số dư'
$balanceA = [decimal](Get-Json "/api/wallets/$($walletIds['A'])").balance
$balanceB = [decimal](Get-Json "/api/wallets/$($walletIds['B'])").balance
$expectedA = $InitialBalance - $Pairs * 1 + $Pairs * 2
$expectedB = $InitialBalance + $Pairs * 1 - $Pairs * 2
$ok = @($results | Where-Object { $_.Code -eq '200' }).Count
$deadlocks = @($results | Where-Object { $_.Type -eq 'deadlock' }).Count
Write-Host "   chuyển xong     : $ok / $(2 * $Pairs)"
Write-Host "   ví A            : $balanceA (mong đợi $expectedA)"
Write-Host "   ví B            : $balanceB (mong đợi $expectedB)"
Write-Host "   tổng            : $($balanceA + $balanceB) (mong đợi $(2 * $InitialBalance))"

$client.Dispose()

if ($ok -eq 2 * $Pairs -and $balanceA -eq $expectedA -and $balanceB -eq $expectedB) {
    Write-Host "ĐÚNG: $(2 * $Pairs) lượt chuyển ngược chiều cùng lúc đều xong, không deadlock, số dư đúng" -ForegroundColor Green
    exit 0
}
if ($deadlocks -gt 0) {
    Write-Host "SAI: $deadlocks lượt bị huỷ vì deadlock (app khoá hai ví theo thứ tự khác nhau?)" -ForegroundColor Red
} else {
    Write-Host "SAI: có lượt chuyển không thành công hoặc số dư không đúng (xem ở trên)" -ForegroundColor Red
}
exit 1
