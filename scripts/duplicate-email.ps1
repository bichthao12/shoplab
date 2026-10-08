<#
.SYNOPSIS
  Đăng ký trùng gửi cùng lúc: Count request cùng email (rồi cùng username), kiểm tra chỉ đúng 1 người dùng được tạo.

.DESCRIPTION
  Cùng kịch bản với sameEmailAtOnce_registersExactlyOne / sameUsernameAtOnce_registersExactlyOne trong
  UserApiIntegrationTests, nhưng gọi API của app đang chạy (.\mvnw spring-boot:run).

    1. Count request POST /api/users cùng một email (username khác nhau), gửi CÙNG LÚC.
    2. Count request POST /api/users cùng một username (email khác nhau), gửi CÙNG LÚC.

  Mong đợi mỗi case: đúng 1 request 201, còn lại 409 duplicate-email / duplicate-username, không mã nào khác.

  App kiểm tra trùng trước khi INSERT (existsByEmail), nhưng nhiều request gửi cùng lúc vẫn cùng qua được bước này
  trước khi request đầu tiên commit. Chỉ unique constraint của DB (uk_users_email, uk_accounts_username) chặn
  được chúng; app dịch lỗi đó về 409 duplicate-email / duplicate-username.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu cả 2 case đều đúng; 1 nếu có case sai; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\duplicate-email.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\duplicate-email.ps1 -Count 100
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Count = 50
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()   # mã của lần chạy: email, username không trùng lần trước

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = [Math]::Max($Count, 2)   # mọi request đi ngay, không xếp hàng chờ kết nối
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.ExpectContinue = $false  # PowerShell 5.1: không chờ "100 Continue" trước mỗi POST

function New-Registration([string]$email, [string]$fullName, [string]$username) {
    $json = '{"email":"' + $email + '","fullName":"' + $fullName + '","username":"' + $username +
            '","password":"matkhau123"}'
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/users")
    $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    return $request
}

$failedCases = New-Object System.Collections.Generic.List[string]

# Gửi cùng lúc mọi đăng ký của một case, in kết quả, kiểm tra đúng 1 lượt 201 và mọi lượt còn lại 409 $expectedType
function Test-SimultaneousRegistrations([string]$name, [string]$description, $registrations, [string]$expectedType) {
    Write-Host "== ${name}: $description"

    # Tạo sẵn mọi request rồi mới gửi, để chúng đi gần như cùng một lúc
    $requests = @(foreach ($r in $registrations) { New-Registration $r.Email $r.FullName $r.Username })
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $tasks = @(foreach ($request in $requests) { $client.SendAsync($request) })
    try {
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
    } catch {
        # request lỗi kết nối: xem ở từng task bên dưới
    }
    $watch.Stop()
    Write-Host "   xong sau $($watch.ElapsedMilliseconds) ms"

    $created = New-Object System.Collections.Generic.List[string]
    $rejected = 0
    $sampleDetail = $null
    $others = @{}   # kết quả không mong đợi: "mã type" → số lần
    for ($i = 0; $i -lt $tasks.Count; $i++) {
        if ($tasks[$i].IsFaulted -or $tasks[$i].IsCanceled) {
            $others['lỗi kết nối / hết giờ'] = 1 + [int]$others['lỗi kết nối / hết giờ']
            continue
        }
        $code = [int]$tasks[$i].Result.StatusCode
        $body = $tasks[$i].Result.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $type = if ($body.type) { ($body.type -split '/')[-1] } else { '' }
        if ($code -eq 201) {
            $created.Add(('{0} (id {1}, email {2}, username {3})' -f $body.fullName, $body.id, $body.email, $body.account.username))
        } elseif ($code -eq 409 -and $type -eq $expectedType) {
            $rejected++
            if (-not $sampleDetail) { $sampleDetail = $body.detail }
        } else {
            $key = "$code $type".Trim()
            $others[$key] = 1 + [int]$others[$key]
        }
    }

    Write-Host "   201: $($created.Count)" -ForegroundColor $(if ($created.Count -eq 1) { 'Green' } else { 'Red' })
    foreach ($c in $created) { Write-Host "        $c" }
    Write-Host "   409 ${expectedType}: $rejected$(if ($sampleDetail) { "   ví dụ: $sampleDetail" })"
    foreach ($key in $others.Keys) { Write-Host "   $key`: $($others[$key])" -ForegroundColor Red }

    $problems = New-Object System.Collections.Generic.List[string]
    if ($created.Count -ne 1) {
        $problems.Add("$($created.Count) người dùng được tạo, mong đợi đúng 1")
    }
    if ($others.Count -gt 0) {
        $problems.Add("có request nhận kết quả khác 201 / 409 $expectedType")
    }
    if ($problems.Count -eq 0) {
        Write-Host "   ĐÚNG: chỉ 1 người dùng được tạo, $rejected request còn lại nhận 409 $expectedType" -ForegroundColor Green
    } else {
        foreach ($p in $problems) { Write-Host "   SAI: $p" -ForegroundColor Red }
        $failedCases.Add($name)
    }
    Write-Host ''
}

try {
    [void]$client.GetAsync("$BaseUrl/api/products").GetAwaiter().GetResult()
} catch {
    Write-Host "Không gọi được $BaseUrl. App đã chạy chưa? (.\mvnw spring-boot:run)" -ForegroundColor Red
    exit 2
}

$email = "dup$run@example.com"
Test-SimultaneousRegistrations 'Case 1' "$Count request đăng ký cùng email $email, username khác nhau, gửi cùng lúc" `
    @(for ($i = 1; $i -le $Count; $i++) {
        @{ Email = $email; FullName = "Người $i"; Username = "dup$run-$i" }
    }) 'duplicate-email'

$username = "dup$run"
Test-SimultaneousRegistrations 'Case 2' "$Count request đăng ký cùng username $username, email khác nhau, gửi cùng lúc" `
    @(for ($i = 1; $i -le $Count; $i++) {
        @{ Email = "dup$run-$i@example.com"; FullName = "Người $i"; Username = $username }
    }) 'duplicate-username'

$client.Dispose()

if ($failedCases.Count -eq 0) {
    Write-Host 'ĐÚNG (2/2 case): đăng ký trùng gửi cùng lúc chỉ tạo đúng 1 người dùng, các request còn lại nhận 409' `
        -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $($failedCases -join ', ') không như mong đợi" -ForegroundColor Red
exit 1
