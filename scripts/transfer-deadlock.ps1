<#
.SYNOPSIS
  Chạy lại test case deadlock qua API: chuyển A->B 10 và B->A 30 cùng lúc, kiểm tra không deadlock và số dư đúng.

.DESCRIPTION
  Cùng kịch bản với TransferDeadlockTests, nhưng gọi API của app đang chạy (.\mvnw spring-boot:run).
  Mỗi vòng:
    1. tạo hai người dùng A, B (POST /api/users), tạo ví cho mỗi người (POST /api/wallets),
       nạp mỗi ví 100 (POST /api/wallets/{id}/deposits)
    2. gửi CÙNG LÚC hai lượt chuyển: A->B 10 và B->A 30 (POST /api/wallets/transfers, mỗi lượt một Idempotency-Key)
    3. in lượt nào xong trước, sau bao lâu, số dư mỗi lượt trả về; đọc lại số dư hai ví (GET /api/wallets/{id})

  App khoá hai ví theo thứ tự id tăng dần, nên cả hai lượt đều 200, lượt đến sau chỉ chờ lượt trước xong,
  và cuối cùng A = 120, B = 80. Nếu app khoá theo thứ tự tham số (ví nguồn trước) thì có thể thấy 409 deadlock.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu mọi vòng đều đúng; 1 nếu có vòng sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1 -Runs 10
  Lặp 10 vòng, mỗi vòng hai ví mới.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Runs = 1
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

# Đúng số liệu của TransferDeadlockTests
$initialBalance = 100
$amountAB = 10
$amountBA = 30
$expectedA = $initialBalance - $amountAB + $amountBA   # 120
$expectedB = $initialBalance + $amountAB - $amountBA   # 80

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, username, Idempotency-Key không trùng

$client = New-Object System.Net.Http.HttpClient
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

function Get-Balance([long]$walletId) {
    $result = Send-Request (New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$BaseUrl/api/wallets/$walletId"))
    return [decimal]($result.Body | ConvertFrom-Json).balance
}

# Tạo người dùng + ví, nạp số dư ban đầu; trả về id ví
function New-FundedWallet([string]$name, [int]$round) {
    $tag = "dl$run$round$($name.ToLower())"
    $user = Invoke-Setup '/api/users' (@{
        email    = "$tag@example.com"
        fullName = "Người dùng $name"
        username = $tag
        password = 'matkhau123'
    } | ConvertTo-Json -Compress)
    $wallet = Invoke-Setup '/api/wallets' ('{"userId":' + $user.id + '}')
    [void](Invoke-Setup "/api/wallets/$($wallet.id)/deposits" ('{"amount":' + $initialBalance + '}') "deposit-$tag")
    return [long]$wallet.id
}

try {
    [void](Send-Request (New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$BaseUrl/api/products")))
} catch {
    Write-Host "Không gọi được $BaseUrl. App đã chạy chưa? (.\mvnw spring-boot:run)" -ForegroundColor Red
    exit 2
}

$failedRounds = 0
$deadlocks = 0
for ($round = 1; $round -le $Runs; $round++) {
    Write-Host "== Vòng $round/$Runs"
    $a = New-FundedWallet 'A' $round
    $b = New-FundedWallet 'B' $round
    Write-Host "   ví A (id $a) = $initialBalance, ví B (id $b) = $initialBalance"

    # Gửi cùng lúc: hai request đi ngay, không chờ nhau
    $transfers = @(
        @{ Name = "A->B $amountAB"; From = $a; To = $b; Amount = $amountAB },
        @{ Name = "B->A $amountBA"; From = $b; To = $a; Amount = $amountBA }
    )
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $tasks = foreach ($t in $transfers) {
        $json = '{"fromWalletId":' + $t.From + ',"toWalletId":' + $t.To + ',"amount":' + $t.Amount + '}'
        $client.SendAsync((New-JsonPost '/api/wallets/transfers' $json "transfer-$run-$round-$($t.From)-$($t.To)"))
    }
    Write-Host "   gửi cùng lúc: $($transfers[0].Name) | $($transfers[1].Name)"

    # Ghi lại lượt nào xong trước, sau bao lâu
    $pending = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $tasks.Count; $i++) { [void]$pending.Add($i) }
    $roundOk = $true
    while ($pending.Count -gt 0) {
        $waitOn = [System.Threading.Tasks.Task[]]@($pending | ForEach-Object { $tasks[$_] })
        $done = $pending[[System.Threading.Tasks.Task]::WaitAny($waitOn)]
        $elapsed = $watch.ElapsedMilliseconds
        $pending.Remove($done)
        $t = $transfers[$done]
        $task = $tasks[$done]

        if ($task.IsFaulted -or $task.IsCanceled) {
            $roundOk = $false
            Write-Host ('   {0,-8} → lỗi kết nối / hết giờ sau {1} ms' -f $t.Name, $elapsed) -ForegroundColor Red
            continue
        }
        $code = [int]$task.Result.StatusCode
        $body = $task.Result.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ($code -eq 200) {
            Write-Host ('   {0,-8} → 200 sau {1,4} ms   (sau lượt này: ví nguồn = {2}, ví đích = {3})' -f `
                $t.Name, $elapsed, $body.from.balance, $body.to.balance) -ForegroundColor Green
        } else {
            $roundOk = $false
            $type = ($body.type -split '/')[-1]
            if ($type -eq 'deadlock') { $deadlocks++ }
            Write-Host ('   {0,-8} → {1} {2} sau {3} ms: {4}' -f $t.Name, $code, $type, $elapsed, $body.detail) -ForegroundColor Red
        }
    }

    $balanceA = Get-Balance $a
    $balanceB = Get-Balance $b
    Write-Host "   số dư cuối: A = $balanceA (mong đợi $expectedA), B = $balanceB (mong đợi $expectedB), tổng $($balanceA + $balanceB)"
    if ($roundOk -and $balanceA -eq $expectedA -and $balanceB -eq $expectedB) {
        Write-Host '   ĐÚNG' -ForegroundColor Green
    } else {
        $failedRounds++
        Write-Host '   SAI' -ForegroundColor Red
    }
}

$client.Dispose()

Write-Host ''
if ($failedRounds -eq 0) {
    Write-Host "ĐÚNG ($Runs/$Runs vòng): A->B và B->A cùng lúc đều chuyển xong, không deadlock, A = $expectedA, B = $expectedB" `
        -ForegroundColor Green
    exit 0
}
$reason = if ($deadlocks -gt 0) { ", $deadlocks lượt bị huỷ vì deadlock (app khoá hai ví theo thứ tự khác nhau?)" } else { '' }
Write-Host "SAI: $failedRounds/$Runs vòng không như mong đợi$reason" -ForegroundColor Red
exit 1
