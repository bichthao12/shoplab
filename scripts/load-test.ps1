<#
.SYNOPSIS
  Tải k6 lên trang chi tiết đơn (GET /api/orders/{id}/detail): luồng thường so với luồng ảo (virtual thread), rồi pool
  kết nối DB còn 5. In sẵn số cho sổ đo (dòng 2: thông lượng req/s; dòng 3: p95 ms, cùng hai lượt).

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

  Chẩn đoán mỗi lượt: lỗi theo loại (503, không có response) kèm vài mẫu có thời điểm; các dòng WARN / ERROR trong log
  của app, tách lúc đo với lúc khởi động / chạy cho nóng máy; JFR (app chạy kèm Java Flight Recorder, ghi ra
  target/load-test/<lượt>.jfr): GC dừng lâu nhất, luồng ảo bị ghim (pinned) từ 20 ms.

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

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\load-test.ps1 -Case virtual -AppArgs '--shoplab.order-detail.optional-timeout=350ms'
  -AppArgs: thêm tham số cho app (cách nhau bằng dấu cách), vd đổi hạn chờ để xem trang thiếu nguồn phụ dưới tải.
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
    [string]$AppArgs = '',
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

# jcmd, jfr của cùng JDK: app chạy kèm JFR (Java Flight Recorder), cuối mỗi lượt script đọc GC dừng bao lâu, luồng ảo
# có bị ghim (pinned) không. java trong PATH có thể chỉ là bản rút gọn (vd ...\Oracle\Java\javapath), nên hỏi chính java
# thư mục cài (java.home) của nó
$jcmd = $null
$jfr = $null
$settings = Invoke-Native $java @('-XshowSettings:properties', '-version')
$javaHomeLine = @($settings.Lines | Where-Object { $_ -match '^\s*java\.home = ' }) | Select-Object -First 1
if ($javaHomeLine -match '^\s*java\.home = (.+)$') {
    $exe = ''
    if ($onWindows) { $exe = '.exe' }
    $candidate = Join-Path $Matches[1].Trim() "bin/jcmd$exe"
    if (Test-Path $candidate) { $jcmd = $candidate }
    $candidate = Join-Path $Matches[1].Trim() "bin/jfr$exe"
    if (Test-Path $candidate) { $jfr = $candidate }
}
$useJfr = [bool]($jcmd -and $jfr)
if (-not $useJfr) {
    Write-Host 'Không thấy jcmd, jfr cạnh java (đang dùng JRE?): bỏ phần đọc JFR (GC, luồng ảo bị ghim).' -ForegroundColor Yellow
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
    $javaArgs = @()
    if ($useJfr) { $javaArgs += '-XX:StartFlightRecording=name=load,settings=default' }
    $javaArgs += @('-jar', "target/$($jar.Name)", "--server.port=$Port",
                  '--logging.level.sql=INFO', '--logging.level.tx=INFO',
                  "--spring.threads.virtual.enabled=$(([string]$virtual).ToLower())",
                  "--spring.datasource.hikari.maximum-pool-size=$pool")
    if ($AppArgs.Trim()) { $javaArgs += @($AppArgs.Trim() -split '\s+') }
    Set-Content -Path $log -Value '' -ErrorAction SilentlyContinue   # log của lần chạy trước (nếu có) không lẫn vào
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
    # Mẫu lỗi k6 in ra (mỗi người dùng ảo tối đa một), dạng DETAIL_ERROR t=... id=... status=... ms=... ...
    $samples = @($result.Lines | ForEach-Object { if ($_ -match '(DETAIL_(ERROR|DEGRADED) [^"]*)') { $Matches[1] } })
    return [pscustomobject]@{ Summary = ($line.Substring('K6_SUMMARY '.Length) | ConvertFrom-Json); Samples = $samples }
}

# Một lần đo: k6 rồi đọc thời gian chờ lấy kết nối (hikaricp.connections.acquire) của riêng khoảng đo đó
function Measure-Load([string]$label, [bool]$virtual, [int]$pool, [int]$vus, [string]$duration) {
    $acquireBefore = Get-Metric 'hikaricp.connections.acquire'
    $timeoutBefore = Get-Metric 'hikaricp.connections.timeout'
    $start = [DateTimeOffset]::Now
    $run = Invoke-K6 $vus $duration
    $end = [DateTimeOffset]::Now
    $k = $run.Summary
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
        ErrNetwork = [int]$k.errNetwork; Err503 = [int]$k.err503; ErrOther = [int]$k.errOther
        MissingPayments = [int]$k.missingPaymentHistory; MissingRecommendations = [int]$k.missingRecommendations
        Start = $start; End = $end
        Samples = $run.Samples; GcMaxMs = $null; GcCount = $null; Pinned = $null; PinnedMaxMs = $null
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
        $jfrFindings = Get-JfrFindings $name $process
        Write-Diagnostics $name $jfrFindings $rows
        foreach ($r in $rows) {
            if ($null -ne $jfrFindings) {
                $r.GcMaxMs = $jfrFindings.GcMaxMs; $r.GcCount = $jfrFindings.GcCount
                $r.Pinned = $jfrFindings.Pinned; $r.PinnedMaxMs = $jfrFindings.PinnedMaxMs
            }
        }
    } finally {
        Stop-App $process
    }
    return $rows
}

# "11.2 ms", "1.03 s", "812 us" (cách jfr print ghi thời lượng) → ms
function ConvertTo-Ms([string]$value, [string]$unit) {
    $n = [double]::Parse($value, [System.Globalization.CultureInfo]::InvariantCulture)
    switch ($unit) {
        'ns' { return $n / 1000000 }
        'us' { return $n / 1000 }
        's' { return $n * 1000 }
        'min' { return $n * 60000 }
        default { return $n }
    }
}

# Ghi JFR của app ra target/load-test/<lượt>.jfr (jcmd JFR.dump), đọc: GC dừng (jdk.GCPhasePause) và luồng ảo bị ghim
# vào luồng hệ điều hành từ 20 ms trở lên (jdk.VirtualThreadPinned, ngưỡng của settings=default) kèm đỉnh stack
function Get-JfrFindings([string]$name, $process) {
    if (-not $useJfr -or $process.HasExited) { return $null }
    $file = "target/load-test/$name.jfr"   # đường dẫn tương đối: app (thư mục làm việc là gốc repo) tự ghi
    $dump = Invoke-Native $jcmd @([string]$process.Id, 'JFR.dump', 'name=load', "filename=$file")
    $path = Join-Path $root $file
    if ($dump.ExitCode -ne 0 -or -not (Test-Path $path)) {
        Write-Host ("  (không ghi được JFR: " + (($dump.Lines | Select-Object -Last 3) -join ' ') + ')') -ForegroundColor Yellow
        return $null
    }
    $print = Invoke-Native $jfr @('print', '--events', 'jdk.GCPhasePause,jdk.VirtualThreadPinned', '--stack-depth', '6', $path)
    $gc = @()
    $pinned = @()
    $current = $null
    $frames = @()
    foreach ($line in $print.Lines) {
        if ($line -match '^(jdk\.\w+) \{') { $current = $Matches[1]; $frames = @(); continue }
        if ($null -eq $current) { continue }
        if ($line -match '^\s*duration = ([0-9.]+) (ns|us|ms|s|min)') {
            $ms = ConvertTo-Ms $Matches[1] $Matches[2]
            if ($current -eq 'jdk.GCPhasePause') { $gc += $ms } else { $pinned += [pscustomobject]@{ Ms = $ms; Frames = @() } }
        } elseif ($current -eq 'jdk.VirtualThreadPinned' -and $line -match '^\s+([\w.$]+\.[\w<>$]+\(.*\)\s+line:.*)$') {
            if ($pinned.Count -gt 0) { $pinned[-1].Frames += $Matches[1].Trim() }
        } elseif ($line -match '^\}') {
            $current = $null
        }
    }
    $gcMax = $null
    if ($gc.Count -gt 0) { $gcMax = ($gc | Measure-Object -Maximum).Maximum }
    $pinnedMax = $null
    if ($pinned.Count -gt 0) { $pinnedMax = ($pinned | ForEach-Object { $_.Ms } | Measure-Object -Maximum).Maximum }
    return [pscustomobject]@{ File = $file; GcMaxMs = $gcMax; GcCount = $gc.Count; Pinned = $pinned.Count; PinnedMaxMs = $pinnedMax
                              PinnedTop = @($pinned | Group-Object { ($_.Frames | Select-Object -First 3) -join ' <- ' } |
                                            Sort-Object Count -Descending | Select-Object -First 3) }
}

# Chẩn đoán cả lượt (khởi động, chạy cho nóng máy, đo, tham khảo): JFR và các dòng WARN / ERROR trong log của app
function Write-Diagnostics([string]$name, $jfrFindings, $rows) {
    if ($null -ne $jfrFindings) {
        Write-Host ("  JFR ({0}): GC dừng lâu nhất {1} ms ({2} lần dừng); luồng ảo bị ghim từ 20 ms: {3} lần{4}" -f
                    $jfrFindings.File, (Format-Num $jfrFindings.GcMaxMs '0.0'), $jfrFindings.GcCount, $jfrFindings.Pinned,
                    $(if ($jfrFindings.Pinned -gt 0) { ', lâu nhất ' + (Format-Num $jfrFindings.PinnedMaxMs '0') + ' ms' } else { '' }))
        foreach ($g in $jfrFindings.PinnedTop) {
            Write-Host ("    ghim ×{0} tại: {1}" -f $g.Count, $g.Name) -ForegroundColor Yellow
        }
    }
    $log = Join-Path $outDir "$name.log"
    if (-not (Test-Path $log)) { return }
    $lines = @(Get-Content $log -Encoding UTF8 | Where-Object { $_ -match '^\S+\s+(WARN|ERROR) ' })
    if ($lines.Count -eq 0) {
        Write-Host '  Log của app: không có dòng WARN / ERROR.'
        return
    }
    Write-Host ("  Log của app ({0}): {1} dòng WARN / ERROR" -f "target/load-test/$name.log", $lines.Count) -ForegroundColor Yellow
    # Gom theo lúc xảy ra (trong khoảng đo nào, hay lúc khởi động / chạy cho nóng máy) và nội dung (phần sau " : "),
    # mỗi nhóm in số lần, thời điểm đầu và cuối
    $groups = $lines | Group-Object {
        $when = 'ngoài lúc đo (khởi động, chạy cho nóng máy)'
        try {
            $t = [DateTimeOffset]::Parse(($_ -split '\s+')[0], [System.Globalization.CultureInfo]::InvariantCulture)
            foreach ($r in $rows) {
                if ($t -ge $r.Start -and $t -le $r.End) { $when = "lúc đo $($r.Label)"; break }
            }
        } catch { }
        $when + ' | ' + ($_ -split ' : ', 2)[-1]
    } | Sort-Object Count -Descending | Select-Object -First 6
    foreach ($g in $groups) {
        $first = ($g.Group[0] -split '\s+')[0]
        $last = ($g.Group[-1] -split '\s+')[0]
        if ($first -match 'T(\d{2}:\d{2}:\d{2}\.\d{3})') { $first = $Matches[1] }
        if ($last -match 'T(\d{2}:\d{2}:\d{2}\.\d{3})') { $last = $Matches[1] }
        $text = $g.Name
        if ($text.Length -gt 140) { $text = $text.Substring(0, 140) + '...' }
        Write-Host ("    ×{0} ({1} → {2}): {3}" -f $g.Count, $first, $last, $text)
    }
}

function Write-Row($r) {
    Write-Host ("  → {0} req/s, p50 {1} ms, p95 {2} ms, lỗi {3}; chờ kết nối: pending TB {4} / lớn nhất {5}, chờ lấy TB {6} ms; CPU app {7} %" -f
                (Format-Num $r.Rps '0.0'), (Format-Num $r.P50 '0.0'), (Format-Num $r.P95 '0.0'), $r.Errors,
                (Format-Num $r.PendingAvg '0.0'), (Format-Num $r.PendingMax '0'), (Format-Num $r.WaitMs '0.0'),
                (Format-Num ($r.CpuAvg * 100) '0'))
    if ($r.Errors -gt 0) {
        Write-Host ("    lỗi: 503 ×{0}, không có response (hết giờ, mất kết nối) ×{1}, khác ×{2}; lâu nhất {3} ms" -f
                    $r.Err503, $r.ErrNetwork, $r.ErrOther, (Format-Num $r.Max '0')) -ForegroundColor Yellow
    }
    if ($r.Degraded -gt 0) {
        Write-Host ("    thiếu nguồn phụ: lịch sử thanh toán ×{0}, gợi ý ×{1}" -f $r.MissingPayments, $r.MissingRecommendations) -ForegroundColor Yellow
    }
    if ($r.ErrNetwork -gt 0 -and $r.Err503 -eq 0 -and $r.ErrOther -eq 0) {
        Write-Host '    Chỉ có lỗi không có response: request không tới được app, hoặc app không trả lời trong 30 giây. Nếu log' -ForegroundColor Yellow
        Write-Host '    của app bên dưới không có WARN / ERROR lúc đo thì app không thấy các request này: lỗi nằm ở chặng giữa k6 và' -ForegroundColor Yellow
        Write-Host '    app (mạng của Docker Desktop khi k6 chạy trong Docker, hàng chờ kết nối của Tomcat), không phải ở app.' -ForegroundColor Yellow
    }
    # Vài mẫu (giờ máy này) để so với log của app: lúc gửi = lúc có kết quả - thời gian chờ
    foreach ($s in @($r.Samples | Select-Object -First 4)) {
        $f = @{}
        foreach ($m in [regex]::Matches($s, '(\w+)=(\S*)')) { $f[$m.Groups[1].Value] = $m.Groups[2].Value }
        $sent = '?'
        try {
            $done = [DateTimeOffset]::Parse($f['t'], [System.Globalization.CultureInfo]::InvariantCulture)
            $sent = $done.AddMilliseconds(-[double]$f['ms']).ToLocalTime().ToString('HH:mm:ss.fff')
        } catch { }
        $errText = ''
        if ($s -match 'error=(.+)$' -and $Matches[1].Trim()) { $errText = " ($($Matches[1].Trim()))" }
        if ($s -like 'DETAIL_DEGRADED*') {
            $what = "thiếu $($f['missing'])"
        } elseif ($f['status'] -eq '0') {
            $what = 'không có response'
        } else {
            $what = ("status $($f['status']) $($f['type'])").Trim()
        }
        Write-Host ("    vd gửi {0}, đơn {1}: {2} sau {3} ms{4}" -f $sent, $f['id'], $what, $f['ms'], $errText)
    }
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
        'GC dừng max (ms)'           = Format-Num $_.GcMaxMs '0.0'
        'Ghim luồng ảo'              = Format-Num $_.Pinned '0'
    }
}
$table | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
Write-Host '(GC, ghim luồng ảo: đọc từ JFR, tính cho cả lượt: khởi động, chạy cho nóng máy, đo, tham khảo)'
Write-Host ''

$platform = $results | Where-Object { $_.Label -eq 'platform' }
$virtualRun = $results | Where-Object { $_.Label -eq 'virtual' }
$pool5 = $results | Where-Object { $_.Label -eq 'pool5' }
$platformRef = $results | Where-Object { $_.Label -eq "platform-$ReferenceVus" }
$virtualRef = $results | Where-Object { $_.Label -eq "virtual-$ReferenceVus" }

if ($platform -and $virtualRun) {
    Write-Host ("Dòng 2, thông lượng dưới tải k6 (req/s, {0} VU):   TRƯỚC {1}   SAU {2}" -f $Vus,
                (Format-Num $platform.Rps '0.0'), (Format-Num $virtualRun.Rps '0.0')) -ForegroundColor Cyan
    Write-Host ("Dòng 3, p95 dưới tải k6 (ms, cùng hai lượt trên):  TRƯỚC {0}   SAU {1}" -f
                (Format-Num $platform.P95 '0.0'), (Format-Num $virtualRun.P95 '0.0')) -ForegroundColor Cyan
}
if ($pool5) {
    Write-Host ("Pool {0} kết nối (luồng ảo, {1} VU; không có dòng riêng trong sổ): {2} req/s, p95 {3} ms; pending lớn nhất {4}, TB {5}; chờ lấy kết nối TB {6} ms" -f
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
    # Thẻ p95: giảm khi luồng thường bị dùng hết và request phải xếp hàng chờ luồng
    $exhausted = ($platform.BusyMax -ge 200) -and ($Vus -gt 200)
    if ($virtualRun.P95 -le 0.9 * $platform.P95) {
        Write-Host ("p95 giảm {0} → {1} ms: luồng thường bị dùng hết (Tomcat bận {2} / 200), request phải xếp hàng chờ luồng, đúng như thẻ." -f
                    (Format-Num $platform.P95 '0.0'), (Format-Num $virtualRun.P95 '0.0'), (Format-Num $platform.BusyMax '0')) -ForegroundColor Green
    } elseif (-not $exhausted) {
        Write-Host ("p95 không giảm ({0} → {1} ms), đúng như thẻ: thẻ chỉ mong giảm khi luồng thường bị dùng hết. Tomcat bận lớn nhất {2} / 200," -f
                    (Format-Num $platform.P95 '0.0'), (Format-Num $virtualRun.P95 '0.0'), (Format-Num $platform.BusyMax '0')) -ForegroundColor Yellow
        Write-Host ("  nhưng {0} người dùng ảo không vượt 200 luồng nên không request nào xếp hàng chờ luồng (xem phần tham khảo)." -f $Vus) -ForegroundColor Yellow
    } else {
        Write-Host ("p95 không giảm ({0} → {1} ms) dù luồng thường đã bị dùng hết: xem CPU, chờ kết nối ở bảng tài nguyên." -f
                    (Format-Num $platform.P95 '0.0'), (Format-Num $virtualRun.P95 '0.0')) -ForegroundColor Yellow
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
    Write-Host ("SAI: có request không trả 200 ở lượt {0}. Xem các dòng lỗi, JFR và log của app in dưới lượt đó;" -f
                (($bad | ForEach-Object { $_.Label }) -join ', ')) -ForegroundColor Red
    Write-Host '  log đầy đủ ở target/load-test/<lượt>.log, mở <lượt>.jfr bằng JDK Mission Control để xem kỹ hơn.' -ForegroundColor Red
    exit 1
}
exit 0
