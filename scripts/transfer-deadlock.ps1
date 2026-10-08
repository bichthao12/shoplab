<#
.SYNOPSIS
  Chạy test deadlock chuyển tiền (TransferDeadlockTests) và in diễn biến: lượt chuyển nào khoá ví lúc nào,
  PostgreSQL báo deadlock ra sao, lượt nào bị huỷ, lượt nào chuyển xong.

.DESCRIPTION
  Kịch bản: chuyển A->B và B->A cùng lúc, mỗi lượt khoá hai ví theo thứ tự tham số (ví nguồn trước, ví đích sau)
  và chờ 50ms giữa hai lần khoá. Mỗi lượt giữ một khoá và chờ khoá lượt kia đang giữ: deadlock.

  Module ví chưa có REST API và bản chuyển tiền gây deadlock chỉ có trong test, nên script chạy test qua Maven
  (cần Docker đang chạy: test dựng PostgreSQL bằng Testcontainers) rồi đọc log SQL / transaction của test.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu mọi lần chạy đều tái hiện đúng deadlock (test xanh), 1 nếu không.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1 -Runs 3
  Chạy 3 lần để thấy lượt bị huỷ thay đổi ngẫu nhiên giữa A->B và B->A.
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

# Đọc log, in diễn biến của hai lượt chuyển; trả về tên lượt bị huỷ (hoặc $null)
function Show-Timeline([string[]]$lines) {
    $names = @{}      # thread → "A->B" / "B->A"
    $locks = @{}      # thread → số lần đã khoá ví
    $loser = $null
    $waitStart = $null
    $deadlockAt = $null
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

        if ($msg -match '^BEGIN\s+transfer (\S+)') {
            $names[$thread] = $Matches[1]
            $locks[$thread] = 0
            Write-Host ('{0,-14}{1,-6}  BEGIN' -f $time, $Matches[1])
            continue
        }
        if (-not $names.ContainsKey($thread)) { continue }   # dòng log không thuộc hai lượt chuyển
        $who = $names[$thread]

        if ($msg -match 'from wallets .* for (no key )?update') {
            $locks[$thread]++
            if ($locks[$thread] -eq 1) {
                Write-Host ('{0,-14}{1,-6}  khoá ví nguồn (SELECT ... FOR UPDATE)' -f $time, $who)
            } else {
                if (-not $waitStart) { $waitStart = [TimeSpan]::Parse($time) }
                Write-Host ('{0,-14}{1,-6}  xin khoá ví đích ... chờ' -f $time, $who)
            }
        } elseif ($msg -match 'deadlock detected') {
            $deadlockAt = [TimeSpan]::Parse($time)
            $inDetail = $true
            Write-Host ('{0,-14}{1,-6}  PostgreSQL: deadlock detected (40P01)' -f $time, $who) -ForegroundColor Red
        } elseif ($msg -match '^update wallets') {
            Write-Host ('{0,-14}{1,-6}  cập nhật số dư (UPDATE wallets)' -f $time, $who)
        } elseif ($msg -match '^ROLLBACK\s+transfer') {
            $loser = $who
            Write-Host ('{0,-14}{1,-6}  ROLLBACK: bị huỷ, không chuyển gì' -f $time, $who) -ForegroundColor Red
        } elseif ($msg -match '^COMMIT\s+transfer') {
            Write-Host ('{0,-14}{1,-6}  COMMIT: chuyển xong' -f $time, $who) -ForegroundColor Green
        }
    }

    if ($waitStart -and $deadlockAt) {
        Write-Host ('   Hai lượt chờ nhau ~{0:N1} giây thì PostgreSQL mới phát hiện deadlock (deadlock_timeout mặc định 1 giây)' -f `
            ($deadlockAt - $waitStart).TotalSeconds)
    }
    return $loser
}

$losers = @()
$failed = 0
for ($run = 1; $run -le $Runs; $run++) {
    Write-Host "== Lần $run/$Runs`: chạy $testClass (A->B và B->A cùng lúc, khoá theo thứ tự tham số, chờ 50ms giữa hai lần khoá)"
    $result = Invoke-DeadlockTest
    $loser = Show-Timeline $result.Lines

    if ($result.ExitCode -eq 0) {
        $summary = if (Test-Path $report) { (Get-Content $report | Select-String 'Tests run').Line } else { '' }
        Write-Host "   Test XANH: $summary" -ForegroundColor Green
        $losers += $loser
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
    Write-Host '== Lượt bị PostgreSQL huỷ qua các lần chạy:'
    $losers | Group-Object | Sort-Object Name | ForEach-Object { Write-Host ('{0,6} lần  {1}' -f $_.Count, $_.Name) }
}

if ($failed -eq 0) {
    Write-Host "ĐÚNG: $Runs/$Runs lần đều deadlock, PostgreSQL huỷ một lượt, lượt kia chuyển xong, tổng tiền không đổi" `
        -ForegroundColor Green
    exit 0
}
Write-Host "SAI: $failed/$Runs lần test không như mong đợi" -ForegroundColor Red
exit 1
