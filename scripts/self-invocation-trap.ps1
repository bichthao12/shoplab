<#
.SYNOPSIS
  Chạy test bẫy gọi nội bộ của @Transactional (SelfInvocationTrapTests) và in diễn biến từng cách gọi:
  qua proxy (có transaction), this.transfer(...) (không có transaction, mất tiền), bản sửa (TransactionTemplate),
  và code thật WalletService.transfer khi bị gọi không qua proxy.

.DESCRIPTION
  Kịch bản: chuyển "lương" từ ví A (100) theo hai lượt: A → ví không tồn tại 30 (lỗi ở bước tìm ví đích, SAU khi
  đã lưu A bị trừ 30), rồi A → B 10. Đúng thì lượt lỗi rollback: A = 90, B = 110, tổng 200.

  Bản lỗi (BatchTransferService.transferAll gọi this.transfer) chỉ có trong test, app không có API nào đi vào nó,
  nên script chạy test qua Maven (cần Docker đang chạy: test dựng PostgreSQL bằng Testcontainers) rồi đọc log
  BEGIN / COMMIT / ROLLBACK, SQL và các dòng [self-invocation] mà test ghi ra.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  -Break: tự "phá" bản sửa (bỏ TransactionTemplate, gọi thẳng transfer(...) như bản bẫy) để xem test có bắt được không.

  Thoát với mã 0 nếu mọi thứ đúng như mong đợi (không -Break: cả 4 test xanh; -Break: đúng test của bản sửa đỏ
  vì mất tiền); 1 nếu không; 2 nếu không chạy được test.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\self-invocation-trap.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\self-invocation-trap.ps1 -Break
  Phá bản sửa: test transferAllEachInOwnTransaction_keepsMoney phải đỏ (A = 60.00 thay vì 90.00).
#>
param(
    [switch]$Break
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
$testClass = 'SelfInvocationTrapTests'
$report = Join-Path $root "target/surefire-reports/com.shoplab.wallet.internal.$testClass.txt"
$fixCase = 'transferAllEachInOwnTransaction_keepsMoney'

# Một dòng log mặc định của Spring Boot:
# 2026-10-08T09:39:25.466Z  INFO 577 --- [           main] c.s.w.internal.SelfInvocationTrapTests : [self-invocation] CASE ...
$logLine = '^\S+T(?<time>\d\d:\d\d:\d\d\.\d{3})\S*\s+(?<level>[A-Z]+)\s+\d+\s+---\s+\[\s*(?<thread>[^\]]+)\]\s+(?<logger>\S+)\s*:\s(?<msg>.*)$'

# Các case theo thứ tự in (JUnit không chạy theo thứ tự khai báo), kèm điều mong đợi
$cases = [ordered]@{
    'transfer_calledThroughProxy_rollsBack' = @{
        Title  = '1. Gọi batch.transfer(...) từ bên ngoài (qua proxy)'
        Expect = 'có transaction; lượt lỗi rollback, A giữ 100'
        Check  = { param($r) $r.Outcome -eq 'WalletNotFoundException' -and $r.Tx -eq 'true' -and $r.A -eq 100 -and $r.B -eq 100 }
    }
    'transferAll_selfInvocation_losesMoney' = @{
        Title  = '2. BẪY: transferAll gọi this.transfer(...) (không qua proxy)'
        Expect = 'KHÔNG có transaction; lượt lỗi vẫn trừ 30 của A → mất 30 (A 60, B 110, tổng 170)'
        Check  = { param($r) $r.Outcome -eq '[FAILED,TRANSFERRED]' -and $r.Tx -eq 'false' -and $r.A -eq 60 -and $r.B -eq 110 }
    }
    $fixCase = @{
        Title  = '3. SỬA: mỗi lượt chạy trong transaction mở bằng TransactionTemplate'
        Expect = 'có transaction; lượt lỗi rollback, lượt kia vẫn chuyển (A 90, B 110, tổng 200)'
        Check  = { param($r) $r.Outcome -eq '[FAILED,TRANSFERRED]' -and $r.Tx -eq 'true' -and $r.A -eq 90 -and $r.B -eq 110 }
    }
    'realTransferWithoutProxy_failsFastOnLockQuery' = @{
        Title  = '4. Code thật: WalletService.transfer(ví A → ví B, 10) bị gọi không qua proxy'
        Expect = 'lỗi ngay ở câu khoá SELECT ... FOR UPDATE (cần transaction), không ví nào bị đổi'
        Check  = { param($r) $r.Outcome -match 'TransactionRequiredException' -and $r.A -eq 100 -and $r.B -eq 100 }
    }
}
if ($Break) {
    $cases[$fixCase].Title = '3. SỬA, nhưng ĐÃ BỊ PHÁ (-Break): bỏ TransactionTemplate, gọi thẳng transfer(...)'
}

# Chạy test một lần, trả về các dòng output của Maven (gồm log của app trong test)
function Invoke-TrapTest {
    $mavenArgs = @('-q', 'test', "-Dtest=$testClass")
    if ($Break) { $mavenArgs += '-Dshoplab.trap.break=true' }   # là chuỗi trong mảng: PowerShell 5.1 không tách ở dấu chấm như khi gõ trần
    Push-Location $root
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # Java có thể in ra stderr; Windows PowerShell coi đó là lỗi nếu để 'Stop'
    try {
        if ($onWindows) {
            $output = & .\mvnw.cmd @mavenArgs 2>&1 | ForEach-Object { "$_" }
        } else {
            $output = & sh ./mvnw @mavenArgs 2>&1 | ForEach-Object { "$_" }
        }
        return @{ ExitCode = $LASTEXITCODE; Lines = $output }
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
}

# Đọc log, gom theo case: mỗi case có các dòng diễn biến (đã viết lại cho dễ đọc) và kết quả (RESULT)
function Read-Cases([string[]]$lines) {
    $parsed = @{}
    $current = $null
    foreach ($line in $lines) {
        $m = [regex]::Match($line, $logLine)
        if (-not $m.Success) { continue }
        $time = $m.Groups['time'].Value
        $msg = $m.Groups['msg'].Value

        if ($msg -match '^\[self-invocation\] CASE (\S+) a=(\d+) b=(\d+) missing=(\d+)') {
            $current = @{
                Events = New-Object System.Collections.Generic.List[string]
                Names = @{ $Matches[2] = 'ví A'; $Matches[3] = 'ví B'; $Matches[4] = 'ví không tồn tại' }
                Result = $null; Transfer = $null; PendingBegin = $null
            }
            $parsed[$Matches[1]] = $current
            continue
        }
        if (-not $current) { continue }

        if ($msg -match '^\[self-invocation\] transfer from=(\d+) to=(\d+) amount=(\S+) transaction=(\w+)') {
            $from = $current.Names[$Matches[1]]; $to = $current.Names[$Matches[2]]
            $current.Transfer = @{ From = $from; To = $to; Amount = $Matches[3]; Tx = ($Matches[4] -eq 'true')
                                   Finds = 0; Saves = 0; Selects = 0; Updates = 0; Missing = ($to -eq 'ví không tồn tại') }
            $txText = if ($current.Transfer.Tx) { 'CÓ' } else { 'KHÔNG' }
            $current.Events.Add("   lượt: $from → $to, $($Matches[3])   (trong transfer có transaction: $txText)")
            if ($current.PendingBegin) { $current.Events.Add($current.PendingBegin); $current.PendingBegin = $null }
            continue
        }
        if ($msg -match '^\[self-invocation\] BREAK') {
            $current.Events.Add("   $time  (-Break) bỏ TransactionTemplate, gọi thẳng transfer(...)")
            continue
        }
        if ($msg -match '^\[self-invocation\] RESULT outcome=(\S+) transaction=(\S+) a=(\S+) b=(\S+)') {
            $invariant = [Globalization.CultureInfo]::InvariantCulture   # giữ 2 số lẻ; máy locale vi-VN dùng dấu phẩy
            $current.Result = @{ Outcome = $Matches[1]; Tx = $Matches[2]
                                 A = [decimal]::Parse($Matches[3], $invariant); B = [decimal]::Parse($Matches[4], $invariant) }
            continue
        }

        $t = $current.Transfer
        $row = '     {0}  {1,-61} {2}'
        if ($msg -match '^(BEGIN|COMMIT|ROLLBACK)\s+(.*)$') {
            $kind = $Matches[1]; $name = $Matches[2]
            $note = ''
            if ($name -match '^BatchTransferService\.transfer') {
                if ($kind -eq 'BEGIN') {
                    # BEGIN của proxy / TransactionTemplate có trước dòng "transfer ...": in sau tiêu đề lượt
                    $current.PendingBegin = ($row -f $time, "$kind $name", '← mở transaction cho cả lượt')
                    continue
                }
                $note = if ($kind -eq 'ROLLBACK') { '← huỷ cả lượt: chưa có UPDATE nào' } else { '← ghi cả hai ví cùng lúc' }
            } elseif ($t -and $name -match '^SimpleJpaRepository\.findById') {
                if ($kind -eq 'BEGIN') {
                    $t.Finds++
                    $who = if ($t.Finds -eq 1) { $t.From } else { $t.To }
                    $note = "tìm $who" + $(if ($t.Finds -eq 2 -and $t.Missing) { ': không có → lỗi' } else { '' })
                }
            } elseif ($t -and $name -match '^SimpleJpaRepository\.save') {
                if ($kind -eq 'BEGIN') {
                    $t.Saves++
                    $note = if ($t.Saves -eq 1) { "lưu $($t.From) đã trừ $($t.Amount)" } else { "lưu $($t.To) đã cộng $($t.Amount)" }
                } elseif ($kind -eq 'COMMIT') {
                    $note = '← commit ngay, không rollback được nữa'
                }
            }
            $current.Events.Add(($row -f $time, "$kind $name", $note))
            continue
        }
        if ($t -and $msg -match '^select .* from wallets') {
            if ($t.Tx) {   # trong transaction: select 1 = ví nguồn, select 2 = ví đích
                $t.Selects++
                $who = if ($t.Selects -eq 1) { $t.From } else { $t.To }
                $note = "tìm $who" + $(if ($t.Selects -eq 2 -and $t.Missing) { ': không có → lỗi' } else { '' })
                $current.Events.Add(($row -f $time, '  select ... from wallets', $note))
            }
            continue       # ngoài transaction: đã ghi chú ở dòng BEGIN của repository
        }
        if ($t -and $msg -match '^update wallets') {
            $t.Updates++
            $note = if ($t.Tx) { 'lúc commit' } else { '' }
            $current.Events.Add(($row -f $time, '  update wallets set balance=? ...', $note))
        }
    }
    return $parsed
}

Write-Host "== Chạy $testClass$(if ($Break) { ' với -Break (phá bản sửa)' })"
Write-Host '   Ví A có 100, chuyển hai lượt: A → ví không tồn tại 30 (lỗi sau khi đã lưu A bị trừ), rồi A → B 10'
$result = Invoke-TrapTest
$parsed = Read-Cases $result.Lines

if ($parsed.Count -eq 0) {
    Write-Host ''
    Write-Host "Không chạy được test (mã thoát Maven $($result.ExitCode)). Docker đã chạy chưa? Trích lỗi:" -ForegroundColor Red
    $result.Lines | Where-Object { $_ -match 'ERROR|Exception|Could not|Cannot' } | Select-Object -First 15 |
            ForEach-Object { Write-Host "   $_" }
    exit 2
}

$caseFailures = New-Object System.Collections.Generic.List[string]
foreach ($name in $cases.Keys) {
    $info = $cases[$name]
    Write-Host ''
    Write-Host "-- $($info.Title)" -ForegroundColor Cyan
    Write-Host "   mong đợi: $($info.Expect)"
    $case = $parsed[$name]
    if (-not $case -or -not $case.Result) {
        Write-Host '   (không thấy kết quả của case này trong log)' -ForegroundColor Red
        $caseFailures.Add($name)
        continue
    }
    if ($name -eq 'realTransferWithoutProxy_failsFastOnLockQuery') {
        Write-Host '     (không có BEGIN / SQL nào: lỗi trước cả câu SQL đầu tiên)'
    }
    foreach ($e in $case.Events) { Write-Host $e }

    $r = $case.Result
    $total = $r.A + $r.B
    $lost = 200 - $total
    $txText = switch ($r.Tx) { 'true' { 'CÓ' } 'false' { 'KHÔNG' } default { '-' } }
    $outcome = $r.Outcome -replace '<-', ' ← '
    Write-Host ("   kết quả: {0}; transaction trong transfer: {1}; A = {2}, B = {3}, tổng {4}{5}" -f `
        $outcome, $txText, $r.A, $r.B, $total, $(if ($lost -gt 0) { " → MẤT $lost" } else { '' }))
    if (& $info.Check $r) {
        Write-Host '   đúng như mong đợi' -ForegroundColor Green
    } else {
        Write-Host '   KHÁC mong đợi' -ForegroundColor Red
        $caseFailures.Add($name)
    }
}

Write-Host ''
$summary = if (Test-Path $report) { (Get-Content $report | Select-String 'Tests run').Line } else { '' }
if ($result.ExitCode -eq 0) {
    Write-Host "   Test XANH: $summary" -ForegroundColor Green
} else {
    Write-Host "   Test ĐỎ (mã thoát Maven $($result.ExitCode)): $summary" -ForegroundColor Red
    $result.Lines | Where-Object { $_ -match '^\[ERROR\]\s+\S+\.\w+:\d+|expected:|but was:' } |
            ForEach-Object { $_.Trim() } | Select-Object -Unique | Select-Object -First 8 |
            ForEach-Object { Write-Host "     $_" }
}
Write-Host ''

if ($Break) {
    # Phá bản sửa: đúng là chỉ case 3 khác mong đợi và Maven báo đỏ thì test có tác dụng
    if ($result.ExitCode -ne 0 -and $caseFailures.Count -eq 1 -and $caseFailures[0] -eq $fixCase) {
        $r = $parsed[$fixCase].Result
        Write-Host "ĐÚNG: đã phá bản sửa và test bắt được: A = $($r.A) thay vì 90.00, mất $(200 - $r.A - $r.B)" -ForegroundColor Green
        exit 0
    }
    Write-Host 'SAI: phá bản sửa mà test không báo như mong đợi' -ForegroundColor Red
    exit 1
}
if ($result.ExitCode -eq 0 -and $caseFailures.Count -eq 0) {
    Write-Host 'ĐÚNG (4/4 case): gọi nội bộ this.transfer(...) không có transaction và làm mất 30;' -ForegroundColor Green
    Write-Host '      qua proxy hoặc TransactionTemplate thì lượt lỗi rollback, tổng tiền giữ 200' -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $($caseFailures.Count) case không như mong đợi$(if ($caseFailures.Count) { ': ' + ($caseFailures -join ', ') })" `
    -ForegroundColor Red
exit 1
