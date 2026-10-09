<#
.SYNOPSIS
  Đỏ với bản lỗi, xanh với bản sửa: mỗi kịch bản có test bảo vệ, chạy trên code hiện tại phải XANH,
  chạy trên code đã áp bản lỗi (scripts/bugs/*.patch) phải ĐỎ. In ma trận cho mọi kịch bản.

.DESCRIPTION
  Không đụng vào thư mục làm việc của bạn: script copy pom.xml, mvnw, .mvn, src sang một thư mục tạm, chạy test
  bảo vệ của mọi kịch bản trên bản copy (bản sửa), rồi lần lượt áp từng patch, chạy lại test bảo vệ của kịch bản đó
  (bản lỗi) và gỡ patch ra.

  Bản lỗi chỉ nằm trong file patch, không nằm trong code chính hay code test. Code đổi mà patch không áp được nữa thì
  script báo "patch không áp được" để cập nhật patch.

  Kịch bản race (bán vượt, cộng / trừ tồn kho cùng lúc): lỗi không phải lần nào cũng lộ, nên bản lỗi được chạy tối đa
  -Attempts lần (mặc định 3), đỏ ở bất kỳ lần nào là đạt.

  Cần git và Docker (test dựng PostgreSQL bằng Testcontainers), như khi chạy test. Mỗi kịch bản một lượt Maven,
  cả 12 kịch bản mất khoảng 5–10 phút. Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu mọi kịch bản xanh với bản sửa và đỏ với bản lỗi; 1 nếu không; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\red-green.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\red-green.ps1 -Scenario deadlock,self-invocation
  Chỉ chạy hai kịch bản này.
#>
param(
    [string[]]$Scenario,
    [int]$Attempts = 3,
    [string[]]$MavenArgs = @(),   # tham số thêm cho Maven; giá trị bắt đầu bằng '-' thì viết -MavenArgs:-Dabc=1
    [switch]$KeepTemp             # giữ lại thư mục tạm để xem
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$bugsDir = Join-Path $PSScriptRoot 'bugs'
$onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows

# Test bảo vệ: "Class" (mọi test của class) hoặc "Class#method1+method2"
$all = @(
    @{ Id = 'oversell'; Patch = '01-oversell.patch'; Race = $true
       Title = 'Bán vượt: 1.000 lượt mua, kho còn 1'
       Bug = 'đọc kho → kiểm tra → trừ ở Java → ghi con số đã tính'
       Tests = @('FlashSaleIntegrationTests', 'NaiveStockDeductionTests#real_conditionalUpdate_sellsExactlyTheStock') }
    @{ Id = 'deadlock'; Patch = '02-deadlock.patch'
       Title = 'Deadlock: chuyển A→B và B→A cùng lúc'
       Bug = 'khoá hai ví theo thứ tự tham số'
       Tests = @('TransferDeadlockTests#oppositeTransfers_lockingInIdOrder_bothSucceed') }
    @{ Id = 'deadlock-log'; Patch = '03-deadlock-log.patch'
       Title = 'Log deadlock ghi mã 40P01'
       Bug = 'log không có mã lỗi'
       Tests = @('GlobalExceptionHandlerTests#deadlock_isReportedSeparatelyFromLockTimeout') }
    @{ Id = 'lock-timeout'; Patch = '04-lock-timeout.patch'
       Title = 'Giới hạn thời gian chờ khoá'
       Bug = 'bỏ SET lock_timeout'
       Tests = @('OrderIdempotencyIntegrationTests#keyHeldByAnotherTransaction_returns409AfterLockTimeout') }
    @{ Id = 'profile-lost-update'; Patch = '05-profile-lost-update.patch'
       Title = 'Lost update: sửa hồ sơ với version cũ'
       Bug = 'không so version client gửi'
       Tests = @('UserApiIntegrationTests#updateProfile_staleVersion_returns409') }
    @{ Id = 'version-409'; Patch = '06-version-409.patch'
       Title = '@Version chặn ghi đè → 409 Problem Details'
       Bug = 'không bắt ObjectOptimisticLockingFailureException'
       Tests = @('UserApiIntegrationTests#profileChangedBetweenCheckAndWrite_versionColumnReturns409') }
    @{ Id = 'product-lost-update'; Patch = '07-product-lost-update.patch'
       Title = 'Lost update: sửa sản phẩm với version cũ'
       Bug = 'không so version client gửi'
       Tests = @('ProductApiIntegrationTests#patch_staleVersion_returns409') }
    @{ Id = 'stock-adjustment'; Patch = '08-stock-adjustment.patch'; Race = $true
       Title = 'Nhập hàng và đặt hàng cùng lúc'
       Bug = 'đọc kho → cộng ở Java → ghi con số tuyệt đối'
       Tests = @('ProductApiIntegrationTests#restocksAndOrdersAtOnce_loseNoUpdate') }
    @{ Id = 'duplicate-email'; Patch = '09-duplicate-email.patch'
       Title = 'Đăng ký trùng email cùng lúc'
       Bug = 'không có unique constraint, chỉ kiểm tra trước'
       Tests = @('UserApiIntegrationTests#duplicateEmailCaughtByDatabase_returnsDuplicateEmail+sameEmailAtOnce_registersExactlyOne') }
    @{ Id = 'unique-500'; Patch = '10-unique-500.patch'
       Title = 'Vi phạm unique: 409, không bao giờ 500'
       Bug = 'không dịch uk_wallets_user, không có handler dự phòng'
       Tests = @('GlobalExceptionHandlerTests#untranslatedDataIntegrityViolation_returnsGeneric409',
                 'WalletApiIntegrationTests#duplicateWalletCaughtByDatabase_returnsDuplicateWallet') }
    @{ Id = 'self-invocation'; Patch = '11-self-invocation.patch'
       Title = '@Transactional: gọi nội bộ'
       Bug = 'cả ba cách sửa quay về this.transfer(...)'
       Tests = @('SelfInvocationTrapTests#transferAllFromAnotherBean_keepsMoney+transferAllThroughProxy_keepsMoney+transferAllEachInOwnTransaction_keepsMoney') }
    @{ Id = 'hash-in-transaction'; Patch = '12-hash-in-transaction.patch'
       Title = 'Không chờ lâu trong transaction'
       Bug = 'băm mật khẩu bên trong transaction'
       Tests = @('PasswordHashingOutsideTransactionTests#register_hashesPasswordOutsideTransaction') }
)

# powershell -File truyền "-Scenario a,b" thành MỘT chuỗi "a,b": tự tách theo dấu phẩy
$Scenario = @($Scenario | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$scenarios = @($all | Where-Object { $Scenario.Count -eq 0 -or $Scenario -contains $_.Id })
if ($scenarios.Count -eq 0) {
    Write-Host "Không có kịch bản nào tên: $($Scenario -join ', '). Có: $(($all | ForEach-Object { $_.Id }) -join ', ')" -ForegroundColor Red
    exit 2
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host 'Cần git để áp patch (git apply).' -ForegroundColor Red
    exit 2
}

# ---------------------------------------------------------------------------------------------------------------
# Bản copy để áp patch

$work = Join-Path ([IO.Path]::GetTempPath()) ('shoplab-red-green-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $work | Out-Null
foreach ($item in 'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src') {
    Copy-Item -Recurse -Path (Join-Path $root $item) -Destination $work
}
# Patch dùng xuống dòng LF; trên Windows git có thể checkout file nguồn thành CRLF → đưa bản copy về LF cho patch áp được
$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($file in Get-ChildItem -Path (Join-Path $work 'src') -Recurse -File -Include *.java, *.properties, *.sql, *.xml) {
    $text = [IO.File]::ReadAllText($file.FullName, $utf8)
    if ($text.Contains("`r`n")) { [IO.File]::WriteAllText($file.FullName, $text.Replace("`r`n", "`n"), $utf8) }
}

function Invoke-Git([string[]]$gitArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git -C $work @gitArgs 2>&1 | ForEach-Object { "$_" }
        return @{ ExitCode = $LASTEXITCODE; Lines = $output }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Đọc báo cáo surefire: mỗi test một dòng { Class, Method, Status (pass / fail / error / skipped), Message }
function Read-Reports([string]$dir) {
    $results = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path $dir)) { return $results }
    foreach ($file in Get-ChildItem -Path $dir -Filter 'TEST-*.xml') {
        [xml]$doc = [IO.File]::ReadAllText($file.FullName, $utf8)
        foreach ($tc in @($doc.testsuite.testcase)) {
            $status = 'pass'; $message = ''
            if ($tc.failure) { $status = 'fail'; $message = $tc.failure.message }
            elseif ($tc.error) { $status = 'error'; $message = if ($tc.error.message) { $tc.error.message } else { $tc.error.type } }
            elseif ($tc.skipped) { $status = 'skipped' }
            # method có tham số được ghi kèm kiểu tham số, vd "deadlock_x(CapturedOutput)": bỏ phần trong ngoặc
            $results.Add([pscustomobject]@{ Class = ($tc.classname -split '\.')[-1]; Method = ($tc.name -replace '\(.*$', '')
                                            Status = $status; Message = "$message" })
        }
    }
    return $results
}

# Chạy các test theo selector trên bản copy, trả về { ExitCode, Lines, Results, Seconds }
function Invoke-Tests([string[]]$selectors) {
    $reports = Join-Path $work 'target/surefire-reports'
    if (Test-Path $reports) { Remove-Item -Recurse -Force $reports }
    $mavenArgsAll = @('-q', 'test', "-Dtest=$($selectors -join ',')", '-Dsurefire.failIfNoSpecifiedTests=false') + $MavenArgs
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $work
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # Java có thể in ra stderr; Windows PowerShell coi đó là lỗi nếu để 'Stop'
    try {
        if ($onWindows) {
            $output = & .\mvnw.cmd @mavenArgsAll 2>&1 | ForEach-Object { "$_" }
        } else {
            $output = & sh ./mvnw @mavenArgsAll 2>&1 | ForEach-Object { "$_" }
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    return @{ ExitCode = $code; Lines = $output; Results = @(Read-Reports $reports); Seconds = [int]$watch.Elapsed.TotalSeconds }
}

# Kết quả của các test bảo vệ: XANH (mọi test bảo vệ chạy và qua), ĐỎ (có test bảo vệ fail), KHÔNG CHẠY (thiếu test)
function Get-Verdict($results, [string[]]$selectors) {
    $guards = @()
    $missing = 0
    foreach ($selector in $selectors) {
        $parts = $selector -split '#', 2
        $class = $parts[0]
        $methods = if ($parts.Count -gt 1) { @($parts[1] -split '\+') } else { @() }
        $found = @($results | Where-Object { $_.Class -eq $class -and ($methods.Count -eq 0 -or $methods -contains $_.Method) })
        if ($found.Count -eq 0 -or ($methods.Count -gt 0 -and $found.Count -lt $methods.Count)) { $missing++ }
        $guards += $found
    }
    $failed = @($guards | Where-Object { $_.Status -eq 'fail' -or $_.Status -eq 'error' })
    $verdict = if ($failed.Count -gt 0) { 'ĐỎ' } elseif ($missing -gt 0) { 'KHÔNG CHẠY' } else { 'XANH' }
    return @{ Verdict = $verdict; Failed = $failed; Guards = $guards }
}

function Format-Failure($test) {
    $message = ($test.Message -replace '\s+', ' ').Trim()
    if ($message.Length -gt 110) { $message = $message.Substring(0, 110) + '…' }
    return "$($test.Class)#$($test.Method): $message"
}

function Write-Tail($run) {
    $run.Lines | Where-Object { $_ -match 'ERROR|BUILD|Exception' } | Select-Object -Last 6 |
            ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
}

function Get-Color([string]$verdict, [string]$expected) {
    if ($verdict -eq $expected) { return 'Green' }
    return 'Red'
}

# ---------------------------------------------------------------------------------------------------------------
Write-Host "== Đỏ / xanh cho $($scenarios.Count) kịch bản (bản copy ở $work)"
Write-Host ''
Write-Host '-- Bản sửa (code hiện tại): chạy test bảo vệ của mọi kịch bản, mong đợi XANH' -ForegroundColor Cyan
$fixedRun = Invoke-Tests @($scenarios | ForEach-Object { $_.Tests })
Write-Host "   xong sau $($fixedRun.Seconds) giây"
foreach ($s in $scenarios) {
    $v = Get-Verdict $fixedRun.Results $s.Tests
    $s.Fixed = $v.Verdict
    Write-Host ('   {0,-20} {1}' -f $s.Id, $v.Verdict) -ForegroundColor (Get-Color $v.Verdict 'XANH')
    foreach ($f in $v.Failed) { Write-Host "        $(Format-Failure $f)" }
}
if (@($scenarios | Where-Object { $_.Fixed -eq 'KHÔNG CHẠY' }).Count -gt 0) { Write-Tail $fixedRun }

Write-Host ''
Write-Host '-- Bản lỗi: áp từng patch, chạy test bảo vệ của kịch bản đó, mong đợi ĐỎ' -ForegroundColor Cyan
$number = 0
foreach ($s in $scenarios) {
    $number++
    $patch = Join-Path $bugsDir $s.Patch
    Write-Host ''
    Write-Host ("[{0}/{1}] {2}  ({3})" -f $number, $scenarios.Count, $s.Title, $s.Id)
    Write-Host "      bản lỗi    : $($s.Bug)   (scripts/bugs/$($s.Patch))"
    Write-Host "      test bảo vệ: $($s.Tests -join ', ')"

    $check = Invoke-Git @('apply', '--check', $patch)
    if ($check.ExitCode -ne 0) {
        $s.Bugged = 'PATCH KHÔNG ÁP ĐƯỢC'
        Write-Host '      → patch không áp được (code đã đổi?), cần cập nhật patch:' -ForegroundColor Red
        $check.Lines | Select-Object -First 4 | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
        continue
    }
    [void](Invoke-Git @('apply', $patch))
    try {
        $tries = if ($s.Race) { [Math]::Max(1, $Attempts) } else { 1 }
        for ($attempt = 1; $attempt -le $tries; $attempt++) {
            $run = Invoke-Tests $s.Tests
            $v = Get-Verdict $run.Results $s.Tests
            $s.Bugged = $v.Verdict
            $label = if ($tries -gt 1) { " (lần $attempt/$tries, $($run.Seconds) giây)" } else { " ($($run.Seconds) giây)" }
            Write-Host "      → $($v.Verdict)$label" -ForegroundColor (Get-Color $v.Verdict 'ĐỎ')
            foreach ($f in $v.Failed) { Write-Host "        $(Format-Failure $f)" }
            if ($v.Verdict -eq 'KHÔNG CHẠY') { Write-Tail $run }
            if ($v.Verdict -ne 'XANH') { break }
            if ($attempt -lt $tries) { Write-Host '        lỗi race chưa lộ ở lần này, chạy lại' }
        }
    } finally {
        [void](Invoke-Git @('apply', '-R', $patch))
    }
}

if (-not $KeepTemp) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host '== Ma trận'
Write-Host ('   {0,-20} {1,-12} {2}' -f 'Kịch bản', 'Bản sửa', 'Bản lỗi')
$ok = 0
foreach ($s in $scenarios) {
    $good = $s.Fixed -eq 'XANH' -and $s.Bugged -eq 'ĐỎ'
    if ($good) { $ok++ }
    Write-Host ('   {0,-20} {1,-12} {2}' -f $s.Id, $s.Fixed, $s.Bugged) -ForegroundColor $(if ($good) { 'Green' } else { 'Red' })
}
Write-Host ''
if ($ok -eq $scenarios.Count) {
    Write-Host "ĐÚNG ($ok/$($scenarios.Count)): mọi kịch bản có test xanh với bản sửa và đỏ với bản lỗi" -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $($scenarios.Count - $ok)/$($scenarios.Count) kịch bản không như mong đợi" -ForegroundColor Red
exit 1
