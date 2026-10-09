<#
.SYNOPSIS
  Sinh dữ liệu lớn cho thí nghiệm index: 1 triệu user và 10 triệu đơn hàng (mặc định) trong PostgreSQL của
  docker compose (container shoplab-postgres), bằng generate_series.

.DESCRIPTION
  Không gọi API, không chạy test: chép scripts/seed-big-data.sql vào container rồi chạy bằng psql. Từng bước, tỉ lệ
  trạng thái, cách rải ngày đặt... ghi ở đầu file .sql. Tóm tắt:
    - users / accounts: tên Việt theo tỉ lệ họ thật, email chữ thường không trùng, mật khẩu chung "shoplab-seed".
    - orders: ngày đặt rải đều trong 2 năm gần nhất, id tăng theo ngày đặt; khoảng 2% PENDING, 89% COMPLETED,
      8% CANCELLED, còn lại PAID / SHIPPED (đơn mới vài ngày).
    - nạp xong chạy VACUUM ANALYZE (thống kê cho planner, visibility map cho Index Only Scan) rồi in bảng kiểm tra;
      cảnh báo nếu một transaction mở từ trước khi nạp xong làm VACUUM không đánh dấu được trang nào.

  XOÁ SẠCH users, accounts, wallets, orders, order_items, idempotency_keys trước khi sinh (giữ products). Có dữ liệu
  thì hỏi lại trước khi xoá, trừ khi có -Force. Mọi bước nằm trong một transaction: lỗi giữa chừng thì DB như cũ.

  Trước khi chạy:
    1. Cấp cho Docker ít nhất 4 GB RAM (script tự kiểm tra, xem README mục "Dữ liệu lớn").
    2. docker compose up -d
    3. Chạy app một lần để Flyway tạo bảng, rồi TẮT app (script kiểm tra): app giữ sẵn một dải id lấy trước từ
       sequence, sinh xong mà app cũ còn chạy thì nó cấp id trùng với dữ liệu mới.
  Mất khoảng 2–5 phút, cần khoảng 5 GB đĩa trống cho Docker.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

  Thoát với mã 0 nếu sinh xong, 1 nếu psql báo lỗi (đã rollback), 2 nếu chưa đủ điều kiện để chạy.

.PARAMETER Users
  Số user (mặc định 1.000.000).

.PARAMETER Orders
  Số đơn hàng (mặc định 10.000.000).

.PARAMETER Force
  Không hỏi lại trước khi xoá dữ liệu cũ.

.PARAMETER SkipMemoryCheck
  Bỏ qua kiểm tra Docker có ít nhất 4 GB RAM (vd khi chỉ sinh ít dữ liệu để thử).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\seed-big-data.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\seed-big-data.ps1 -Users 10000 -Orders 100000 -SkipMemoryCheck
  Thử nhanh với dữ liệu nhỏ (vài giây).
#>
param(
    [ValidateRange(1, 100000000)][int]$Users = 1000000,
    [ValidateRange(1, 1000000000)][int]$Orders = 10000000,
    [switch]$Force,
    [switch]$SkipMemoryCheck
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$sqlFile = Join-Path $PSScriptRoot 'seed-big-data.sql'
$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')

# Docker Desktop báo RAM của máy ảo chạy Docker. Cấp 4 GB thì Linux trong máy ảo giữ lại một phần cho kernel,
# docker info chỉ còn báo khoảng 3,8 GB, nên ngưỡng kiểm tra là 3,5 GB.
$requiredGb = 4
$minimumBytes = 3.5GB

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

function Format-Gb([double]$bytes) {
    return ('{0:N1} GB' -f ($bytes / 1GB))
}

Write-Host "Sinh $('{0:N0}' -f $Users) user, $('{0:N0}' -f $Orders) đơn hàng vào PostgreSQL ($container)"
Write-Host ''

# ---------------------------------------------------------------------------------------------------------------
# 1. Docker chạy chưa, đủ RAM chưa

$info = Invoke-Docker @('info', '--format', '{{.MemTotal}}')
if ($info.ExitCode -ne 0) {
    Stop-Script "Không gọi được Docker. Mở Docker Desktop rồi chạy lại.`n  $($info.Lines -join "`n  ")"
}
$memTotal = [double]($info.Lines | Select-Object -Last 1)
if ($memTotal -lt $minimumBytes) {
    $message = "Docker đang có $(Format-Gb $memTotal) RAM, cần ít nhất $requiredGb GB cho 10 triệu đơn " +
               "(PostgreSQL giữ 1 GB shared_buffers, nạp và dựng index cần thêm vài trăm MB mỗi bước)."
    if (-not $SkipMemoryCheck) {
        Stop-Script ($message + "`n" +
            "  Docker Desktop dùng WSL 2: thêm vào %UserProfile%\.wslconfig`n" +
            "      [wsl2]`n" +
            "      memory=4GB`n" +
            "    rồi chạy 'wsl --shutdown' và mở lại Docker Desktop.`n" +
            "  Docker Desktop dùng Hyper-V (hoặc macOS): Settings > Resources > Memory.`n" +
            "  Chỉ sinh ít dữ liệu để thử thì thêm -SkipMemoryCheck.")
    }
    Write-Host "CẢNH BÁO: $message" -ForegroundColor Yellow
} else {
    Write-Host "Docker có $(Format-Gb $memTotal) RAM: đủ" -ForegroundColor Green
}

# ---------------------------------------------------------------------------------------------------------------
# 2. Container và bảng

$health = Invoke-Docker @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}

# Cần đủ bảng tới migration mới nhất mà script dùng: orders.user_id (V11), wallets (V12)
$schemaCheck = "SELECT to_regclass('public.wallets') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns " +
               "WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = 'user_id')"
$schema = Invoke-Docker ($psql + @('-tAc', $schemaCheck))
if ($schema.ExitCode -ne 0 -or ($schema.Lines -join '') -ne 't') {
    Stop-Script ("Chưa có đủ bảng. Chạy app một lần để Flyway tạo bảng (.\mvnw spring-boot:run), đợi khởi động xong " +
                 "thì tắt app, rồi chạy lại script.")
}

# App phải tắt: Hibernate giữ sẵn dải id lấy trước từ sequence (vd user 10002..10050). App chạy trong lúc sinh thì
# dải đó trùng id vừa sinh, lần đăng ký tiếp theo lỗi trùng khoá chính. File .sql cũng tự kiểm tra điều này.
$appCheck = "SELECT count(*) FROM pg_stat_activity " +
            "WHERE datname = current_database() AND application_name = 'PostgreSQL JDBC Driver'"
$app = Invoke-Docker ($psql + @('-tAc', $appCheck))
if ($app.ExitCode -ne 0 -or ($app.Lines -join '') -ne '0') {
    Stop-Script ("App đang chạy (có kết nối JDBC tới DB). Tắt app rồi chạy lại script; sinh xong mới chạy lại app.`n" +
                 "  Lý do: app giữ sẵn dải id lấy trước từ sequence, sinh xong nó sẽ cấp id trùng với dữ liệu mới.")
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Hỏi trước khi xoá dữ liệu cũ

$counts = Invoke-Docker ($psql + @('-tA', '-F', ' ', '-c',
    'SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM orders), (SELECT count(*) FROM wallets)'))
if ($counts.ExitCode -ne 0) {
    Stop-Script "Không đếm được dữ liệu hiện có:`n  $($counts.Lines -join "`n  ")"
}
$existing = ($counts.Lines -join '').Trim() -split ' '
$hasData = ($existing | Where-Object { [long]$_ -gt 0 }).Count -gt 0
if ($hasData -and -not $Force) {
    Write-Host ''
    Write-Host ("DB đang có {0:N0} user, {1:N0} đơn, {2:N0} ví. Script sẽ XOÁ HẾT users, accounts, wallets, orders, " -f
                [long]$existing[0], [long]$existing[1], [long]$existing[2]) -NoNewline -ForegroundColor Yellow
    Write-Host 'order_items, idempotency_keys rồi sinh lại (giữ products).' -ForegroundColor Yellow
    $answer = Read-Host 'Gõ yes để tiếp tục'
    if ($answer -ne 'yes') {
        Write-Host 'Đã huỷ, không đổi gì.'
        exit 2
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Chạy file .sql trong container
# docker cp thay vì đẩy nội dung qua stdin: PowerShell 5.1 đổi mã ký tự khi đẩy chuỗi sang lệnh ngoài, tiếng Việt
# trong file sẽ hỏng. psql đọc thẳng file UTF-8 trong container.

$copy = Invoke-Docker @('cp', $sqlFile, "${container}:/tmp/seed-big-data.sql")
if ($copy.ExitCode -ne 0) {
    Stop-Script "Không chép được file .sql vào container:`n  $($copy.Lines -join "`n  ")"
}

Write-Host ''
$watch = [System.Diagnostics.Stopwatch]::StartNew()
# Không gộp stderr: để psql in thẳng ra màn hình từng bước khi đang chạy
& docker @psql -v "users=$Users" -v "orders=$Orders" -f /tmp/seed-big-data.sql
$exitCode = $LASTEXITCODE
$watch.Stop()
[void](Invoke-Docker @('exec', $container, 'rm', '-f', '/tmp/seed-big-data.sql'))

$elapsed = '{0}:{1:00}' -f [int][Math]::Floor($watch.Elapsed.TotalMinutes), $watch.Elapsed.Seconds
Write-Host ''
if ($exitCode -ne 0) {
    Write-Host "THẤT BẠI sau $elapsed (psql thoát với mã $exitCode). Transaction đã rollback: dữ liệu và index như trước khi chạy." -ForegroundColor Red
    exit 1
}
Write-Host "XONG sau $elapsed. Mở psql: docker exec -it $container psql -U shoplab -d shoplab" -ForegroundColor Green
Write-Host 'Giờ có thể chạy lại app: .\mvnw spring-boot:run'
exit 0
