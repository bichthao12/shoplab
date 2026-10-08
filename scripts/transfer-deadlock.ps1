<#
.SYNOPSIS
  Chạy test deadlock chuyển tiền (TransferDeadlockTests) và in diễn biến của hai cách khoá:
  khoá theo thứ tự tham số (deadlock, PostgreSQL huỷ một lượt) và khoá theo thứ tự id tăng dần (bản sửa, không deadlock).

.DESCRIPTION
  Kịch bản: chuyển A->B và B->A cùng lúc, chờ 50ms giữa hai lần khoá.
   - Khoá theo thứ tự tham số (ví nguồn trước): mỗi lượt giữ một khoá và chờ khoá lượt kia đang giữ → deadlock.
   - Khoá theo thứ tự id tăng dần (WalletService): cả hai cùng xin ví A trước, lượt sau chờ lượt trước xong → không deadlock.

  Module ví chưa có REST API (và bản khoá theo tham số chỉ có trong test), nên script chạy test qua Maven
  (cần Docker đang chạy: test dựng PostgreSQL bằng Testcontainers) rồi đọc log SQL / transaction của test.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu mọi lần chạy đều đúng như trên (cả hai test xanh), 1 nếu không.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1 -Runs 3
  Chạy 3 lần để thấy lượt bị huỷ (ở bản khoá theo tham số) thay đổi ngẫu nhiên giữa A->B và B->A.
#>
param(
    [int]$Runs = 1
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
$testClass = 'TransferDeadlockTests'
$report = Join-Path $root "target/surefire-reports/com.shoplab.wallet.internal.$testClass.txt"

# Một dòng log mặc định của Spring Boot:
# 2026-10-08T11:57:55.340+07:00 DEBUG 1962 --- [virtual-53] org.hibernate.SQL : select ...
$logLine = '^\S+T(?<time>\d\d:\d\d:\d\d\.\d{3})\S*\s+(?<level>[A-Z]+)\s+\d+\s+---\s+\[\s*(?<thread>[^\]]+)\]\s+(?<logger>\S+)\s*:\s(?<msg>.*)$'

# Chạy test một lần, trả về các dòng output của Maven (gồm log của app trong test)
function Invoke-DeadlockTest {
    Push-Location $root
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # Java có thể in ra stderr; Windows PowerShell coi đó là lỗi nếu để 'Stop'
    try {
        if ($onWindows) {
            $output = & .\mvnw.cmd -q test "-Dtest=$testClass" 2>&1 | ForEach-Object { "$_" }
        } else {
            $output = & sh ./mvnw -q test "-Dtest=$testClass" 2>&1 | ForEach-Object { "$_" }
        }
        return @{ ExitCode = $LASTEXITCODE; Lines = $output }
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
}

# Đọc log của hai test, in diễn biến từng test.
# Trả về @{ Loser = lượt bị huỷ ở bản khoá theo tham số; OrderedCommits = số lượt COMMIT ở bản khoá theo id }
function Show-Timeline([string[]]$lines) {
    $titles = @{
        naive   = 'Khoá theo thứ tự tham số (NaiveWalletTransfer): ví nguồn trước, ví đích sau'
        ordered = 'Khoá theo thứ tự id tăng dần (WalletService): luôn ví A (id nhỏ) trước, ví B sau'
    }
    $tx = @{}          # thread → @{ Variant; Dir; Locks }
    $section = $null
    $sectionStart = $null
    $waitStart = $null
    $deadlockAt = $null
    $committed = @()   # lượt đã COMMIT trong phần đang in
    $loser = $null
    $orderedCommits = 0
    $inDetail = $false

    foreach ($line in $lines) {
        $m = [regex]::Match($line, $logLine)
        if (-not $m.Success) {
            # Dòng tiếp theo của thông báo deadlock: "Detail: Process ... waits for ..."
            if ($inDetail -and $line -match '(Process \d+ waits for .*)') {
                Write-Host ('{0,-14}{1,-6}  {2}' -f '', '', $Matches[1].Trim())
            }
            continue
        }
        $inDetail = $false
        $time = $m.Groups['time'].Value
        $thread = $m.Groups['thread'].Value
        $msg = $m.Groups['msg'].Value

        if ($msg -match '^BEGIN\s+(naive|ordered) (\S+)') {
            $variant = $Matches[1]
            if ($variant -ne $section) {
                Write-Summary $section $sectionStart $waitStart $deadlockAt $committed
                $section = $variant
                $sectionStart = [TimeSpan]::Parse($time)
                $waitStart = $null
                $deadlockAt = $null
                $committed = @()
                Write-Host ''
                Write-Host "-- $($titles[$variant])" -ForegroundColor Cyan
            }
            $tx[$thread] = @{ Variant = $variant; Dir = $Matches[2]; Locks = 0 }
            Write-Host ('{0,-14}{1,-6}  BEGIN' -f $time, $Matches[2])
            continue
        }
        if (-not $tx.ContainsKey($thread)) { continue }   # dòng log không thuộc các lượt chuyển
        $t = $tx[$thread]
        $who = $t.Dir
        $wallets = $who -split '->'                         # "A->B" → A, B

        if ($msg -match 'from wallets .* for (no key )?update') {
            $t.Locks++
            if ($t.Variant -eq 'naive') {
                $wallet = if ($t.Locks -eq 1) { $wallets[0] } else { $wallets[1] }   # ví nguồn rồi ví đích
            } else {
                $wallet = if ($t.Locks -eq 1) { 'A' } else { 'B' }                   # id nhỏ rồi id lớn
            }
            $note = ''
            if ($t.Locks -eq 2 -and $t.Variant -eq 'naive') {
                if (-not $waitStart) { $waitStart = [TimeSpan]::Parse($time) }
                $note = ' ... chờ'
            }
            if ($t.Locks -eq 2 -and $t.Variant -eq 'ordered' -and $committed.Count -gt 0) {
                $note = "   (trước đó chờ ở khoá ví A tới khi $($committed[0]) COMMIT)"
            }
            Write-Host ('{0,-14}{1,-6}  khoá ví {2} (SELECT ... FOR UPDATE){3}' -f $time, $who, $wallet, $note)
        } elseif ($msg -match 'deadlock detected') {
            $deadlockAt = [TimeSpan]::Parse($time)
            $inDetail = $true
            Write-Host ('{0,-14}{1,-6}  PostgreSQL: deadlock detected (40P01)' -f $time, $who) -ForegroundColor Red
        } elseif ($msg -match '^update wallets') {
            Write-Host ('{0,-14}{1,-6}  cập nhật số dư (UPDATE wallets)' -f $time, $who)
        } elseif ($msg -match '^ROLLBACK\s+') {
            if ($t.Variant -eq 'naive') { $loser = $who }
            Write-Host ('{0,-14}{1,-6}  ROLLBACK: bị huỷ, không chuyển gì' -f $time, $who) -ForegroundColor Red
        } elseif ($msg -match '^COMMIT\s+') {
            $committed += $who
            if ($t.Variant -eq 'ordered') { $orderedCommits++ }
            $script:lastCommit = [TimeSpan]::Parse($time)
            Write-Host ('{0,-14}{1,-6}  COMMIT: chuyển xong' -f $time, $who) -ForegroundColor Green
        }
    }
    Write-Summary $section $sectionStart $waitStart $deadlockAt $committed
    return @{ Loser = $loser; OrderedCommits = $orderedCommits }
}

# Dòng tóm tắt cuối mỗi phần
function Write-Summary($section, $sectionStart, $waitStart, $deadlockAt, $committed) {
    if ($section -eq 'naive' -and $waitStart -and $deadlockAt) {
        Write-Host ('   Hai lượt chờ nhau ~{0:N1} giây thì PostgreSQL mới phát hiện deadlock (deadlock_timeout mặc định 1 giây)' -f `
            ($deadlockAt - $waitStart).TotalSeconds)
    }
    if ($section -eq 'ordered' -and $sectionStart -and $script:lastCommit) {
        if ($committed.Count -eq 2) {
            Write-Host ('   Không deadlock: lượt đến sau chỉ chờ lượt trước xong, cả hai chuyển xong sau ~{0:N2} giây' -f `
                ($script:lastCommit - $sectionStart).TotalSeconds)
        }
    }
}

$losers = @()
$failed = 0
for ($run = 1; $run -le $Runs; $run++) {
    Write-Host "== Lần $run/$Runs`: chạy $testClass (A->B và B->A cùng lúc, chờ 50ms giữa hai lần khoá)"
    $result = Invoke-DeadlockTest
    $timeline = Show-Timeline $result.Lines
    Write-Host ''

    if ($result.ExitCode -eq 0) {
        $summary = if (Test-Path $report) { (Get-Content $report | Select-String 'Tests run').Line } else { '' }
        Write-Host "   Test XANH: $summary" -ForegroundColor Green
        $losers += $timeline.Loser
    } else {
        $failed++
        Write-Host "   Test ĐỎ (mã thoát Maven $($result.ExitCode)). Trích lỗi:" -ForegroundColor Red
        $result.Lines | Where-Object { $_ -match '<<< (FAILURE|ERROR)|Expecting|expected|but was|Exception:' } |
                Select-Object -First 10 | ForEach-Object { Write-Host "     $_" }
        if (Test-Path $report) { Write-Host "   Chi tiết: $report" }
    }
    Write-Host ''
}

if ($Runs -gt 1 -and $losers.Count -gt 0) {
    Write-Host '== Khoá theo thứ tự tham số: lượt bị PostgreSQL huỷ qua các lần chạy:'
    $losers | Group-Object | Sort-Object Name | ForEach-Object { Write-Host ('{0,6} lần  {1}' -f $_.Count, $_.Name) }
}

if ($failed -eq 0) {
    Write-Host "ĐÚNG ($Runs/$Runs lần): khoá theo thứ tự tham số → deadlock, PostgreSQL huỷ một lượt;" `
        -ForegroundColor Green
    Write-Host "      khoá theo thứ tự id tăng dần → không deadlock, cả hai lượt chuyển xong" -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $failed/$Runs lần test không như mong đợi" -ForegroundColor Red
exit 1
