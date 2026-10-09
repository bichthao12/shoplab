<#
.SYNOPSIS
  Xoá toàn bộ dữ liệu trong các bảng của DB shoplab (PostgreSQL của docker compose), giữ nguyên các bảng.

.DESCRIPTION
  TRUNCATE mọi bảng trong schema public bằng MỘT câu lệnh (cả các bảng thêm bằng migration sau này), trừ
  flyway_schema_history: giữ lịch sử migration nên app vẫn khởi động bình thường, không chạy lại migration nào.
  Trước khi xoá, in số dòng của từng bảng rồi hỏi lại (trừ khi có -Force). Không gọi API, không chạy test.

  Id: mặc định sequence giữ nguyên, đơn / người dùng mới tiếp tục từ số cũ. Chạy lúc app đang chạy cũng được.
  -ResetIds: đặt lại mọi sequence của các bảng về đầu (TRUNCATE ... RESTART IDENTITY), id mới lại bắt đầu từ 1. Khi
  đó app phải TẮT (script kiểm tra): app giữ sẵn một dải id lấy trước từ sequence, sẽ cấp id trùng với dải mới.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đã xoá (hoặc không có gì để xoá), 2 nếu không chạy được hoặc đã huỷ.

.PARAMETER Force
  Không hỏi lại trước khi xoá.

.PARAMETER ResetIds
  Đặt lại các sequence, id mới bắt đầu từ 1. Cần tắt app trước.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\clear-data.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\clear-data.ps1 -ResetIds -Force
#>
param(
    [switch]$Force,
    [switch]$ResetIds
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')

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

# ---------------------------------------------------------------------------------------------------------------
# 1. Container, danh sách bảng

$health = Invoke-Docker @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}

$tables = @(Invoke-Sql ("SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                        "AND tablename <> 'flyway_schema_history' ORDER BY tablename") | Where-Object { $_ })
if ($tables.Count -eq 0) {
    Write-Host 'Schema public chưa có bảng nào (app chưa chạy lần nào?). Không có gì để xoá.'
    exit 0
}
# Tên bảng ghép thẳng vào câu SQL: chỉ chấp nhận tên thường (chữ thường, số, _), không cần đặt trong dấu "
$odd = @($tables | Where-Object { $_ -notmatch '^[a-z_][a-z0-9_]*$' })
if ($odd.Count -gt 0) {
    Stop-Script "Tên bảng lạ, không xoá tự động: $($odd -join ', ')"
}

# ---------------------------------------------------------------------------------------------------------------
# 2. -ResetIds: app phải tắt

if ($ResetIds) {
    $appCheck = "SELECT count(*) FROM pg_stat_activity " +
                "WHERE datname = current_database() AND application_name = 'PostgreSQL JDBC Driver'"
    if (((Invoke-Sql $appCheck) -join '') -ne '0') {
        Stop-Script ("App đang chạy (có kết nối JDBC tới DB). -ResetIds cần tắt app trước: app giữ sẵn một dải id " +
                     "lấy trước từ sequence, đặt lại sequence thì nó sẽ cấp id trùng với dải mới.")
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Số dòng hiện có, hỏi lại

function Get-RowCounts {
    $sql = ($tables | ForEach-Object { "SELECT '$_' || '|' || count(*) FROM $_" }) -join ' UNION ALL '
    $counts = [ordered]@{}
    foreach ($line in (Invoke-Sql $sql)) {
        $parts = $line -split '\|'
        $counts[$parts[0]] = [long]$parts[1]
    }
    return $counts
}

$before = Get-RowCounts
$total = ($before.Values | Measure-Object -Sum).Sum
Write-Host 'Dữ liệu hiện có trong DB shoplab (schema public):'
$before.GetEnumerator() | ForEach-Object { [pscustomobject]@{ 'Bảng' = $_.Key; 'Số dòng' = '{0:N0}' -f $_.Value } } |
    Format-Table -AutoSize | Out-String -Width 120 | Write-Host

if ($total -eq 0 -and -not $ResetIds) {
    Write-Host 'Các bảng đã trống, không có gì để xoá.'
    exit 0
}

$what = "XOÁ HẾT {0:N0} dòng trong {1} bảng (giữ flyway_schema_history)" -f $total, $tables.Count
if ($ResetIds) { $what += ', đặt lại id về 1' }
if (-not $Force) {
    Write-Host "Script sẽ $what." -ForegroundColor Yellow
    $answer = Read-Host 'Gõ yes để tiếp tục'
    if ($answer -ne 'yes') {
        Write-Host 'Đã huỷ, không đổi gì.'
        exit 2
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 4. TRUNCATE một lần cho mọi bảng: khoá ngoại giữa các bảng không cản, vì các bảng tham chiếu nhau bị xoá cùng lúc.
# lock_timeout: request đang dở của app giữ khoá bảng thì báo lỗi sau 10 giây, không chờ mãi.

$truncate = "SET lock_timeout = '10s'; TRUNCATE TABLE $($tables -join ', ')"
if ($ResetIds) { $truncate += ' RESTART IDENTITY' }
$watch = [System.Diagnostics.Stopwatch]::StartNew()
[void](Invoke-Sql $truncate)
$watch.Stop()

$after = Get-RowCounts
$left = ($after.Values | Measure-Object -Sum).Sum
if ($left -ne 0) {
    Stop-Script "Sau khi xoá vẫn còn $left dòng (có request ghi vào ngay sau đó?)."
}
Write-Host ("XONG sau {0:N1} giây: {1}. Mọi bảng còn 0 dòng." -f $watch.Elapsed.TotalSeconds, $what.Replace('XOÁ HẾT', 'đã xoá')) -ForegroundColor Green
if ($ResetIds) {
    Write-Host 'Giờ có thể chạy lại app: .\mvnw spring-boot:run'
}
exit 0
