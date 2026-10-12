<#
.SYNOPSIS
  Tải k6 lên trang chi tiết đơn (GET /api/orders/{id}/detail): luồng thường so với luồng ảo (virtual thread), rồi pool
  kết nối DB còn 5. In sẵn số cho sổ đo (dòng 2: thông lượng req/s; dòng 3: pool 5 kết nối).

.DESCRIPTION
  Gọi API qua k6 (không chạy test, không chạy SQL). Script tự build app (Maven) rồi chạy app bằng java -jar, mỗi cấu
  hình một lần, nên phải dừng app đang chạy trước. Cần: PostgreSQL của docker compose đang chạy và đã có đơn, Java 21,
  k6 (nếu máy chưa cài thì script chạy k6 bằng Docker, image grafana/k6).

  Các lượt, mỗi lượt khởi động app riêng (log SQL tắt), chạy k6 15 giây cho nóng máy rồi mới đo:
    platform  luồng thường (spring.threads.virtual.enabled=false): Tomcat có pool 200 luồng; pool 10 kết nối DB
    virtual   luồng ảo (spring.threads.virtual.enabled=true, như app đang cấu hình); pool 10 kết nối
    pool5     luồng ảo; pool còn PoolSize (5) kết nối
  Mỗi lượt đo: Vus người dùng ảo (mặc định 200) gọi trang chi tiết của một trong 1.000 đơn mới nhất, liên tục, không
  nghỉ, trong Duration (mặc định 2 phút). Kịch bản: scripts/k6/order-detail-load.js.
  Tham khảo (ReferenceVus > 0, mặc định 400 người dùng ảo trong 30 giây): chạy thêm ngay sau lượt platform và virtual,
  trên cùng app, để thấy khi số request đồng thời vượt 200 luồng của Tomcat.

  Trong lúc chạy, k6 đọc 2 lần mỗi giây qua Actuator: hikaricp.connections.pending (request đang xếp hàng chờ kết
  nối), hikaricp.connections.active, tomcat.threads.busy, process.cpu.usage. Sau mỗi lượt script đọc thêm
  hikaricp.connections.acquire (thời gian chờ lấy kết nối), hikaricp.connections.timeout, jvm.threads.peak.

  Hết khoảng 9 phút với mặc định. Log của app: target/load-test/<lượt>.log.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đo xong và mọi request của lượt platform, virtual trả 200; 1 nếu có request lỗi; 2 nếu không
  chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\load-test.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\load-test.ps1 -Case pool5 -PoolSize 3 -SkipBuild

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\load-test.ps1 -Duration 30s -ReferenceVus 0   # chạy thử nhanh
#>
param(
    [ValidateSet('all', 'platform', 'virtual', 'pool5')][string]$Case = 'all',
    [ValidateRange(1, 5000)][int]$Vus = 200,
    [ValidatePattern('^\d+[sm]$')][string]$Duration = '2m',
    [ValidateRange(2, 100)][int]$PoolSize = 5,   # tối thiểu 2: lúc khởi động Flyway cần 2 kết nối cùng lúc
    [ValidateRange(0, 5000)][int]$ReferenceVus = 400,
    [ValidatePattern('^\d+[sm]$')][string]$ReferenceDuration = '30s',
    [ValidateSet('auto', 'local', 'docker')][string]$K6 = 'auto',
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$root = Split-Path -Parent $PSScriptRoot
$onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows
$container = 'shoplab-postgres'
$k6Image = 'grafana/k6:1.3.0'
$k6Dir = Join-Path $PSScriptRoot 'k6'
$k6Script = 'order-detail-load.js'
$outDir = Join-Path $root 'target/load-test'
$baseUrl = "http://localhost:$Port"
$vi = [System.Globalization.CultureInfo]::GetCultureInfo('vi-VN')
$warmUp = '15s'

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

# Lệnh ngoài, gộp stdout và stderr. stderr thành ErrorRecord khi gộp 2>&1, nên tạm đổi ErrorActionPreference để
# Windows PowerShell 5.1 không dừng script
function Invoke-Native([string]$exe, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $exe @arguments 2>&1 | ForEach-Object { "$_" }
        return @{ ExitCode = $LASTEXITCODE; Lines = @($output) }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-Seconds([string]$text) {
    $n = [int]$text.Substring(0, $text.Length - 1)
    if ($text.EndsWith('m')) { return $n * 60 }
    return $n
}

function Format-Num([object]$value, [string]$format) {
    if ($null -eq $value) { return '–' }
    return ([double]$value).ToString($format, $vi)
}

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(5)

# GET trả JSON, hoặc $null nếu không gọi được / không phải 200
function Get-Json([string]$path) {
    try {
        $response = $client.GetAsync("$baseUrl$path").GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200) { return $null }
        return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    } catch {
        return $null
    }
}

# Một số đo của Actuator: @{ VALUE = ...; COUNT = ...; TOTAL_TIME = ... } theo statistic
function Get-Metric([string]$name) {
    $json = Get-Json "/actuator/metrics/$name"
    $values = @{}
    if ($null -ne $json) {
        foreach ($m in $json.measurements) { $values[$m.statistic] = [double]$m.value }
    }
    return $values
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Kiểm tra trước khi chạy

Write-Host 'Tải k6 lên trang chi tiết đơn: luồng thường, luồng ảo, pool kết nối nhỏ'
Write-Host ''

$health = Invoke-Native 'docker' @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}

$occupied = $false
try {
    $client.GetAsync("$baseUrl/").GetAwaiter().GetResult().Dispose()
    $occupied = $true
} catch {
    # không có gì ở cổng này: đúng như cần
}
if ($occupied) {
    Stop-Script ("Đã có app chạy ở cổng ${Port}: dừng nó trước (Ctrl+C). Script tự chạy app với từng cấu hình " +
                 '(luồng thường / luồng ảo, số kết nối của pool).')
}

$java = 'java'
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin/java'
    if ($onWindows) { $candidate += '.exe' }
    if (Test-Path $candidate) { $java = $candidate }
}
if ($java -eq 'java' -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Stop-Script 'Không tìm thấy java (JAVA_HOME hay PATH). Cần Java 21.'
}

$useDocker = $false
if ($K6 -eq 'docker' -or ($K6 -eq 'auto' -and -not (Get-Command k6 -ErrorAction SilentlyContinue))) {
    $useDocker = $true
    $image = Invoke-Native 'docker' @('image', 'inspect', $k6Image)
    if ($image.ExitCode -ne 0) {
        Write-Host "Máy chưa cài k6: dùng image $k6Image, tải về lần đầu..."
        $pull = Invoke-Native 'docker' @('pull', $k6Image)
        if ($pull.ExitCode -ne 0) {
            Stop-Script ("Không tải được $k6Image. Cài k6 (winget install k6 --source winget) rồi chạy lại.`n  " +
                         ($pull.Lines -join "`n  "))
        }
    }
    Write-Host "k6: Docker, image $k6Image (app gọi qua host.docker.internal:$Port)"
} elseif ($K6 -eq 'local' -and -not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Stop-Script 'Không tìm thấy k6 trong PATH. Cài (winget install k6 --source winget) hoặc bỏ -K6 local để chạy k6 bằng Docker.'
} else {
    Write-Host "k6: $((Get-Command k6).Source)"
}

# ---------------------------------------------------------------------------------------------------------------
# 2. Build app

$jar = Get-ChildItem (Join-Path $root 'target') -Filter 'shoplab-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $SkipBuild -or -not $jar) {
    Write-Host 'Build app: mvnw -DskipTests package (khoảng 1 phút)...'
    Push-Location $root
    try {
        if ($onWindows) {
            $build = Invoke-Native (Join-Path $root 'mvnw.cmd') @('-q', '-DskipTests', 'package')
        } else {
            $build = Invoke-Native 'sh' @('./mvnw', '-q', '-DskipTests', 'package')
        }
    } finally {
        Pop-Location
    }
    if ($build.ExitCode -ne 0) {
        Stop-Script ("Build lỗi (mã $($build.ExitCode)). 30 dòng cuối:`n  " + ((@($build.Lines) | Select-Object -Last 30) -join "`n  "))
    }
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter 'shoplab-*.jar' | Where-Object { $_.Name -notlike '*.original' } |
           Select-Object -First 1
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# ---------------------------------------------------------------------------------------------------------------
# 3. Chạy app, chạy k6

function Start-App([string]$name, [bool]$virtual, [int]$pool) {
    $log = Join-Path $outDir "$name.log"
    $errLog = Join-Path $outDir "$name.err.log"
    $javaArgs = @('-jar', "target/$($jar.Name)", "--server.port=$Port",
                  '--logging.level.sql=INFO', '--logging.level.tx=INFO',
                  "--spring.threads.virtual.enabled=$(([string]$virtual).ToLower())",
                  "--spring.datasource.hikari.maximum-pool-size=$pool")
    $process = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $root -NoNewWindow -PassThru `
                             -RedirectStandardOutput $log -RedirectStandardError $errLog
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Stop-Script ("App dừng khi khởi động (mã $($process.ExitCode)). 30 dòng cuối của ${log}:`n  " +
                         ((Get-Content $log -Tail 30) -join "`n  "))
        }
        $h = Get-Json '/actuator/health'
        if ($null -ne $h -and $h.status -eq 'UP') { return $process }
        Start-Sleep -Milliseconds 500
    }
    Stop-App $process
    Stop-Script "App không sẵn sàng sau 120 giây, xem $log"
}

function Stop-App($process) {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        [void]$process.WaitForExit(15000)
    }
}

# Chạy kịch bản k6, trả về đối tượng của dòng K6_SUMMARY
function Invoke-K6([int]$vus, [string]$duration) {
    $finish = (Get-Date).AddSeconds((Get-Seconds $duration) + 5).ToString('HH:mm:ss')
    Write-Host "  k6: $vus người dùng ảo trong $duration (xong khoảng $finish)..."
    $envArgs = @('-e', "VUS=$vus", '-e', "DURATION=$duration")
    if ($useDocker) {
        $arguments = @('run', '--rm', '-i', '--add-host=host.docker.internal:host-gateway', '-v', "${k6Dir}:/scripts:ro",
                       $k6Image, 'run', '-q', '--no-color', '--no-usage-report', '-e', "BASE_URL=http://host.docker.internal:$Port") + $envArgs +
                     @("/scripts/$k6Script")
        $result = Invoke-Native 'docker' $arguments
    } else {
        $arguments = @('run', '-q', '--no-color', '--no-usage-report', '-e', "BASE_URL=$baseUrl") + $envArgs + @((Join-Path $k6Dir $k6Script))
        $result = Invoke-Native 'k6' $arguments
    }
    $line = @($result.Lines | Where-Object { $_ -like 'K6_SUMMARY *' }) | Select-Object -Last 1
    if ($result.ExitCode -ne 0 -or -not $line) {
        Stop-Script ("k6 không chạy xong (mã $($result.ExitCode)). 20 dòng cuối:`n  " +
                     ((@($result.Lines) | Select-Object -Last 20) -join "`n  "))
    }
    return $line.Substring('K6_SUMMARY '.Length) | ConvertFrom-Json
}

# Một lần đo: k6 rồi đọc thời gian chờ lấy kết nối (hikaricp.connections.acquire) của riêng khoảng đo đó
function Measure-Load([string]$label, [bool]$virtual, [int]$pool, [int]$vus, [string]$duration) {
    $acquireBefore = Get-Metric 'hikaricp.connections.acquire'
    $timeoutBefore = Get-Metric 'hikaricp.connections.timeout'
    $k = Invoke-K6 $vus $duration
    $acquireAfter = Get-Metric 'hikaricp.connections.acquire'
    $timeoutAfter = Get-Metric 'hikaricp.connections.timeout'
    $peak = Get-Metric 'jvm.threads.peak'

    $acquires = $acquireAfter['COUNT'] - $acquireBefore['COUNT']
    $waitMs = $null
    if ($acquires -gt 0) { $waitMs = ($acquireAfter['TOTAL_TIME'] - $acquireBefore['TOTAL_TIME']) / $acquires * 1000 }
    $thread = 'thường'
    if ($virtual) { $thread = 'ảo' }
    return [pscustomobject]@{
        Label = $label; Thread = $thread; Pool = $pool; Vus = $vus; Virtual = $virtual
        Rps = [double]$k.rps; P50 = $k.p50; P95 = $k.p95; P99 = $k.p99; Max = $k.max
        Errors = [int]$k.not200; Degraded = [int]$k.degraded
        PendingAvg = $k.pendingAvg; PendingMax = $k.pendingMax; ActiveMax = $k.activeMax
        BusyAvg = $k.busyAvg; BusyMax = $k.busyMax; CpuAvg = $k.cpuAvg
        WaitMs = $waitMs; Timeouts = $timeoutAfter['COUNT'] - $timeoutBefore['COUNT']; ThreadsPeak = $peak['VALUE']
    }
}

function Invoke-Phase([string]$name, [string]$title, [bool]$virtual, [int]$pool, [bool]$reference) {
    Write-Host ''
    Write-Host "== $title" -ForegroundColor Cyan
    Write-Host "  Khởi động app: spring.threads.virtual.enabled=$(([string]$virtual).ToLower()), hikari.maximum-pool-size=$pool"
    $process = Start-App $name $virtual $pool
    $rows = @()
    try {
        Write-Host "  Chạy cho nóng máy (không tính):"
        [void](Invoke-K6 $Vus $warmUp)
        $row = Measure-Load $name $virtual $pool $Vus $Duration
        Write-Row $row
        $rows += $row
        if ($reference -and $ReferenceVus -gt 0) {
            Write-Host '  Tham khảo:'
            $ref = Measure-Load "$name-$ReferenceVus" $virtual $pool $ReferenceVus $ReferenceDuration
            Write-Row $ref
            $rows += $ref
        }
    } finally {
        Stop-App $process
    }
    return $rows
}

function Write-Row($r) {
    Write-Host ("  → {0} req/s, p50 {1} ms, p95 {2} ms, lỗi {3}; chờ kết nối: pending TB {4} / lớn nhất {5}, chờ lấy TB {6} ms; CPU app {7} %" -f
                (Format-Num $r.Rps '0.0'), (Format-Num $r.P50 '0.0'), (Format-Num $r.P95 '0.0'), $r.Errors,
                (Format-Num $r.PendingAvg '0.0'), (Format-Num $r.PendingMax '0'), (Format-Num $r.WaitMs '0.0'),
                (Format-Num ($r.CpuAvg * 100) '0'))
}

$phases = @()
if ($Case -in @('all', 'platform')) { $phases += @{ Name = 'platform'; Title = 'Luồng thường, pool 10 kết nối (TRƯỚC)'; Virtual = $false; Pool = 10; Ref = $true } }
if ($Case -in @('all', 'virtual')) { $phases += @{ Name = 'virtual'; Title = 'Luồng ảo, pool 10 kết nối (SAU)'; Virtual = $true; Pool = 10; Ref = $true } }
if ($Case -in @('all', 'pool5')) { $phases += @{ Name = 'pool5'; Title = "Luồng ảo, pool $PoolSize kết nối"; Virtual = $true; Pool = $PoolSize; Ref = $false } }

$perPhase = 25 + (Get-Seconds $warmUp) + (Get-Seconds $Duration)
$total = $phases.Count * $perPhase
if ($ReferenceVus -gt 0) { $total += @($phases | Where-Object { $_.Ref }).Count * (Get-Seconds $ReferenceDuration) }
Write-Host ("{0} lượt, mỗi lượt {1} người dùng ảo trong {2}; hết khoảng {3} phút" -f $phases.Count, $Vus, $Duration,
            [Math]::Ceiling($total / 60))

$results = @()
foreach ($p in $phases) {
    $results += Invoke-Phase $p.Name $p.Title $p.Virtual $p.Pool $p.Ref
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Bảng kết quả, số cho sổ đo

Write-Host ''
Write-Host 'Kết quả (chỉ tính lời gọi trang chi tiết; ms là thời gian client chờ):' -ForegroundColor Cyan
$table = $results | ForEach-Object {
    [pscustomobject]@{
        'Lượt'          = $_.Label
        'Luồng'         = $_.Thread
        'Pool'          = $_.Pool
        'VU'            = $_.Vus
        'req/s'         = Format-Num $_.Rps '0.0'
        'p50'           = Format-Num $_.P50 '0.0'
        'p95'           = Format-Num $_.P95 '0.0'
        'p99'           = Format-Num $_.P99 '0.0'
        'Lỗi'           = $_.Errors
        'Thiếu nguồn phụ' = $_.Degraded
    }
}
$table | Format-Table -AutoSize | Out-String -Width 160 | Write-Host

Write-Host 'Tài nguyên của app trong lúc đo (mẫu 2 lần mỗi giây qua Actuator):' -ForegroundColor Cyan
$table = $results | ForEach-Object {
    $busy = '–'
    if ($null -ne $_.BusyMax) { $busy = '{0} / {1}' -f (Format-Num $_.BusyAvg '0'), (Format-Num $_.BusyMax '0') }
    [pscustomobject]@{
        'Lượt'                       = $_.Label
        'Luồng Tomcat bận TB / max'  = $busy
        'Luồng JVM lớn nhất'         = Format-Num $_.ThreadsPeak '0'
        'CPU app TB'                 = (Format-Num ($_.CpuAvg * 100) '0') + ' %'
        'Kết nối dùng max'           = Format-Num $_.ActiveMax '0'
        'Pending TB / max'           = '{0} / {1}' -f (Format-Num $_.PendingAvg '0.0'), (Format-Num $_.PendingMax '0')
        'Chờ lấy kết nối TB (ms)'    = Format-Num $_.WaitMs '0.0'
        'Quá hạn chờ kết nối'        = Format-Num $_.Timeouts '0'
    }
}
$table | Format-Table -AutoSize | Out-String -Width 180 | Write-Host

$platform = $results | Where-Object { $_.Label -eq 'platform' }
$virtualRun = $results | Where-Object { $_.Label -eq 'virtual' }
$pool5 = $results | Where-Object { $_.Label -eq 'pool5' }
$platformRef = $results | Where-Object { $_.Label -eq "platform-$ReferenceVus" }
$virtualRef = $results | Where-Object { $_.Label -eq "virtual-$ReferenceVus" }

if ($platform -and $virtualRun) {
    Write-Host ("Dòng 2, thông lượng dưới tải k6 (req/s, {0} VU):  TRƯỚC {1}   SAU {2}" -f $Vus,
                (Format-Num $platform.Rps '0.0'), (Format-Num $virtualRun.Rps '0.0')) -ForegroundColor Cyan
    Write-Host ("        p95 cùng lượt (ms):                     TRƯỚC {0}   SAU {1}" -f
                (Format-Num $platform.P95 '0.0'), (Format-Num $virtualRun.P95 '0.0')) -ForegroundColor Cyan
}
if ($pool5) {
    Write-Host ("Dòng 3, pool {0} kết nối (luồng ảo, {1} VU): {2} req/s, p95 {3} ms; pending lớn nhất {4}, TB {5}; chờ lấy kết nối TB {6} ms" -f
                $pool5.Pool, $Vus, (Format-Num $pool5.Rps '0.0'), (Format-Num $pool5.P95 '0.0'), (Format-Num $pool5.PendingMax '0'),
                (Format-Num $pool5.PendingAvg '0.0'), (Format-Num $pool5.WaitMs '0.0')) -ForegroundColor Cyan
}
Write-Host ''

# Nhận xét theo thẻ: tăng khi request chủ yếu chờ I/O; không tăng thì tìm nút thắt
if ($platform -and $virtualRun) {
    $ratio = $virtualRun.Rps / $platform.Rps
    # k6 đóng: mỗi người dùng ảo chờ xong mới gọi tiếp, nên tải tối đa là VU / thời gian một request
    $ceiling = $Vus / ($virtualRun.P50 / 1000)
    if ($ratio -ge 1.1) {
        Write-Host ("Luồng ảo tăng thông lượng ×{0}: request chủ yếu chờ I/O (hai nguồn chậm 300 / 400 ms), đúng như thẻ." -f
                    (Format-Num $ratio '0.00')) -ForegroundColor Green
    } else {
        Write-Host ("Luồng ảo không tăng thông lượng (×{0}). Theo số đo:" -f (Format-Num $ratio '0.00')) -ForegroundColor Yellow
        if ($platform.BusyMax -ge 200 -and $Vus -le 200) {
            Write-Host ("  - {0} người dùng ảo không vượt 200 luồng của Tomcat: mỗi request luôn có sẵn một luồng thường, không" -f $Vus)
            Write-Host '    request nào phải chờ luồng. Luồng Tomcat chỉ ngồi chờ join(); việc chờ thật (300 / 400 ms) đã chạy trên luồng ảo'
            Write-Host '    của orderDetailExecutor từ trước.'
        }
        if ($platform.Rps -ge 0.9 * $ceiling -and $virtualRun.Rps -ge 0.9 * $ceiling) {
            Write-Host ("  - Cả hai đã chạm trần của tải: k6 đóng, mỗi người dùng ảo chờ xong mới gọi tiếp, nên tối đa {0} / {1} s = {2} req/s." -f
                        $Vus, (Format-Num ($virtualRun.P50 / 1000) '0.000'), (Format-Num $ceiling '0'))
            Write-Host '    Nút thắt là lượng tải (số người dùng ảo), không phải app.'
        }
        if ($platform.CpuAvg -ge 0.8 -or $virtualRun.CpuAvg -ge 0.8) {
            Write-Host '  - CPU của app trung bình từ 80 % trở lên: nút thắt CPU.'
        }
        if ($null -ne $virtualRun.WaitMs -and $virtualRun.WaitMs -ge 50) {
            Write-Host ("  - Chờ lấy kết nối trung bình {0} ms: nút thắt pool kết nối." -f (Format-Num $virtualRun.WaitMs '0.0'))
        }
    }
    if ($platformRef -and $virtualRef) {
        Write-Host ("Tham khảo, {0} người dùng ảo: luồng thường {1} req/s, p95 {2} ms; luồng ảo {3} req/s, p95 {4} ms (×{5})." -f
                    $ReferenceVus, (Format-Num $platformRef.Rps '0.0'), (Format-Num $platformRef.P95 '0.0'),
                    (Format-Num $virtualRef.Rps '0.0'), (Format-Num $virtualRef.P95 '0.0'),
                    (Format-Num ($virtualRef.Rps / $platformRef.Rps) '0.00'))
        if ($ReferenceVus -gt 200) {
            Write-Host ('  Luồng thường: tối đa 200 request chạy cùng lúc (Tomcat bận {0} / 200), số còn lại xếp hàng chờ luồng nên p95 tăng;' -f
                        (Format-Num $platformRef.BusyMax '0'))
            Write-Host '  luồng ảo: mỗi request một luồng, không ai chờ luồng.'
        }
    }
}
if ($pool5) {
    $base = $virtualRun
    $msg = 'Pool {0} kết nối: pending lớn nhất {1}, chờ lấy kết nối TB {2} ms ({3} lần quá hạn chờ).' -f $pool5.Pool,
           (Format-Num $pool5.PendingMax '0'), (Format-Num $pool5.WaitMs '0.0'), (Format-Num $pool5.Timeouts '0')
    Write-Host $msg
    if ($base) {
        Write-Host ("  So với pool 10: {0} → {1} req/s, p95 {2} → {3} ms." -f (Format-Num $base.Rps '0.0'), (Format-Num $pool5.Rps '0.0'),
                    (Format-Num $base.P95 '0.0'), (Format-Num $pool5.P95 '0.0'))
        if ($pool5.P95 -lt $base.P95 * 1.1 -and $pool5.Rps -ge $base.Rps * 0.9) {
            Write-Host '  Request có xếp hàng chờ kết nối nhưng trang chưa chậm hơn: nhánh DB (đơn rồi sản phẩm, vài ms) chạy song song'
            Write-Host '  với nguồn gợi ý 400 ms, nên chờ thêm để lấy kết nối mà vẫn xong trước nhánh chậm nhất.'
        } else {
            Write-Host '  Chờ kết nối đã làm trang chậm hơn: pool kết nối là nút thắt.' -ForegroundColor Yellow
        }
    }
}

$bad = @($results | Where-Object { $_.Label -in @('platform', 'virtual') -and $_.Errors -gt 0 })
if ($bad.Count -gt 0) {
    Write-Host "SAI: có request không trả 200 ở lượt $(($bad | ForEach-Object { $_.Label }) -join ', '); xem target/load-test/<lượt>.log" -ForegroundColor Red
    exit 1
}
exit 0
