# Hàm dùng chung cho các script cần tự chạy app (view-counter.ps1 -Bug, memory-leak.ps1). Nạp bằng dot-source:
#   . (Join-Path $PSScriptRoot 'lib/shoplab-app.ps1')
# Cần biến $root (gốc repo) và hàm Stop-Script của script gọi. Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.

Add-Type -AssemblyName System.Net.Http
$script:onWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or $IsWindows

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

# java để chạy app (JAVA_HOME trước, rồi PATH), và jcmd của cùng JDK (hỏi java thư mục cài java.home: java trong PATH
# có thể chỉ là bản rút gọn, vd ...\Oracle\Java\javapath). jcmd là $null nếu chỉ có JRE.
function Find-Java {
    $java = 'java'
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin/java'
        if ($script:onWindows) { $candidate += '.exe' }
        if (Test-Path $candidate) { $java = $candidate }
    }
    if ($java -eq 'java' -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        Stop-Script 'Không tìm thấy java (JAVA_HOME hay PATH). Cần JDK 21.'
    }
    $jcmd = $null
    $settings = Invoke-Native $java @('-XshowSettings:properties', '-version')
    $line = @($settings.Lines | Where-Object { $_ -match '^\s*java\.home = ' }) | Select-Object -First 1
    if ($line -match '^\s*java\.home = (.+)$') {
        $candidate = Join-Path $Matches[1].Trim() 'bin/jcmd'
        if ($script:onWindows) { $candidate += '.exe' }
        if (Test-Path $candidate) { $jcmd = $candidate }
    }
    return @{ Java = $java; Jcmd = $jcmd }
}

# Build jar từ một thư mục có pom.xml, mvnw (bỏ test). Trả về đường dẫn jar.
function Build-Jar([string]$dir) {
    Push-Location $dir
    try {
        if ($script:onWindows) {
            $build = Invoke-Native (Join-Path $dir 'mvnw.cmd') @('-q', '-DskipTests', 'package')
        } else {
            $build = Invoke-Native 'sh' @('./mvnw', '-q', '-DskipTests', 'package')
        }
    } finally {
        Pop-Location
    }
    if ($build.ExitCode -ne 0) {
        Stop-Script ("Build lỗi ở $dir (mã $($build.ExitCode)). 30 dòng cuối:`n  " + ((@($build.Lines) | Select-Object -Last 30) -join "`n  "))
    }
    $jar = Get-ChildItem (Join-Path $dir 'target') -Filter 'shoplab-*.jar' | Where-Object { $_.Name -notlike '*.original' } |
           Select-Object -First 1
    return $jar.FullName
}

# Bản lỗi: copy pom.xml, mvnw, .mvn, src sang thư mục tạm (không đụng thư mục làm việc), áp scripts/bugs/<patch>,
# build. Trả về @{ Dir; Jar }; xoá bằng Remove-Item -Recurse khi xong.
function New-BuggyBuild([string]$patch) {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Stop-Script 'Cần git để áp patch (git apply).' }
    $work = Join-Path ([IO.Path]::GetTempPath()) ('shoplab-bug-' + [IO.Path]::GetFileNameWithoutExtension($patch) + '-' +
                                                 (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $work | Out-Null
    foreach ($item in 'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src') {
        Copy-Item -Recurse -Path (Join-Path $root $item) -Destination $work
    }
    # Patch dùng xuống dòng LF; trên Windows git có thể checkout file nguồn thành CRLF → đưa bản copy về LF
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    foreach ($file in Get-ChildItem -Path (Join-Path $work 'src') -Recurse -File -Include *.java, *.properties, *.sql, *.xml) {
        $text = [IO.File]::ReadAllText($file.FullName, $utf8)
        if ($text.Contains("`r`n")) { [IO.File]::WriteAllText($file.FullName, $text.Replace("`r`n", "`n"), $utf8) }
    }
    $apply = Invoke-Native 'git' @('-C', $work, 'apply', (Join-Path $root "scripts/bugs/$patch"))
    if ($apply.ExitCode -ne 0) {
        Stop-Script ("Không áp được scripts/bugs/${patch}:`n  " + ($apply.Lines -join "`n  "))
    }
    return @{ Dir = $work; Jar = (Build-Jar $work) }
}

# Chạy app từ jar (nằm trong $dir; thư mục làm việc $dir, log ra $log), chờ /actuator/health UP. $jvmArgs đứng trước
# -jar, $appArgs sau. Jar ghi bằng đường dẫn tương đối: Start-Process của PowerShell 5.1 không tự bọc tham số có dấu
# cách (vd thư mục tạm trong C:\Users\Tên Có Dấu Cách).
function Start-ShoplabApp([string]$java, [string]$jar, [string]$dir, [int]$port, [string]$log, [string[]]$jvmArgs,
                          [string[]]$appArgs, [bool]$sqlLog = $false) {
    if (Test-Port $port) {
        Stop-Script "Đã có app chạy ở cổng ${port}: dừng nó trước (Ctrl+C), hoặc chọn cổng khác (-Port)."
    }
    # Log SQL, BEGIN / COMMIT tắt (mặc định của app là bật), trừ khi cần đọc chúng ($sqlLog)
    $quiet = @('--logging.level.sql=INFO', '--logging.level.tx=INFO')
    if ($sqlLog) { $quiet = @() }
    $full = (Resolve-Path $jar).Path
    $base = (Resolve-Path $dir).Path
    $relative = $full
    if ($full.StartsWith($base)) { $relative = $full.Substring($base.Length).TrimStart('\', '/') }
    $arguments = @($jvmArgs) + @('-jar', $relative, "--server.port=$port") + $quiet +
                 @($appArgs) | Where-Object { $_ }
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $dir -NoNewWindow -PassThru `
                            -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Stop-Script ("App dừng khi khởi động (mã $($process.ExitCode)). 30 dòng cuối của ${log}:`n  " +
                         ((Get-Content $log -Tail 30) -join "`n  "))
        }
        if (Test-Health $port) { return $process }
        Start-Sleep -Milliseconds 500
    }
    Stop-ShoplabApp $process
    Stop-Script "App không sẵn sàng sau 120 giây, xem $log"
}

function Stop-ShoplabApp($process) {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        [void]$process.WaitForExit(15000)
    }
}

$script:probe = New-Object System.Net.Http.HttpClient
$script:probe.Timeout = [TimeSpan]::FromSeconds(5)

# Có gì đang trả lời ở cổng này không
function Test-Port([int]$port) {
    try {
        $script:probe.GetAsync("http://localhost:$port/").GetAwaiter().GetResult().Dispose()
        return $true
    } catch {
        return $false
    }
}

function Test-Health([int]$port) {
    try {
        $response = $script:probe.GetAsync("http://localhost:$port/actuator/health").GetAwaiter().GetResult()
        $ok = [int]$response.StatusCode -eq 200
        $response.Dispose()
        return $ok
    } catch {
        return $false
    }
}

# ---------------------------------------------------------------------------------------------------------------
# k6: cài sẵn (k6 trong PATH) hoặc chạy bằng Docker (image grafana/k6, gọi app qua host.docker.internal)

$script:k6Image = 'grafana/k6:1.3.0'
$script:k6UseDocker = $false

# $mode: auto | local | docker. In ra k6 nào được dùng.
function Resolve-K6([string]$mode, [int]$port) {
    $hasLocal = [bool](Get-Command k6 -ErrorAction SilentlyContinue)
    if ($mode -eq 'local' -and -not $hasLocal) {
        Stop-Script 'Không tìm thấy k6 trong PATH. Cài (winget install k6 --source winget) hoặc bỏ -K6 local để chạy k6 bằng Docker.'
    }
    if ($mode -eq 'docker' -or ($mode -eq 'auto' -and -not $hasLocal)) {
        $script:k6UseDocker = $true
        $image = Invoke-Native 'docker' @('image', 'inspect', $script:k6Image)
        if ($image.ExitCode -ne 0) {
            Write-Host "Máy chưa cài k6: dùng image $($script:k6Image), tải về lần đầu..."
            $pull = Invoke-Native 'docker' @('pull', $script:k6Image)
            if ($pull.ExitCode -ne 0) {
                Stop-Script ("Không tải được $($script:k6Image). Cài k6 (winget install k6 --source winget) rồi chạy lại.`n  " +
                             ($pull.Lines -join "`n  "))
            }
        }
        Write-Host "k6: Docker, image $($script:k6Image) (app gọi qua host.docker.internal:$port)"
    } else {
        Write-Host "k6: $((Get-Command k6).Source)"
    }
}

# Chạy scripts/k6/order-detail-load.js, trả về đối tượng của dòng K6_SUMMARY. $envVars: @{ VUS = 200; DURATION = '60s'; ... }
function Invoke-K6Summary([int]$port, [hashtable]$envVars) {
    $k6Dir = Join-Path $root 'scripts/k6'
    $envArgs = @()
    foreach ($k in $envVars.Keys) { $envArgs += @('-e', "$k=$($envVars[$k])") }
    if ($script:k6UseDocker) {
        $arguments = @('run', '--rm', '-i', '--add-host=host.docker.internal:host-gateway', '-v', "${k6Dir}:/scripts:ro",
                       $script:k6Image, 'run', '-q', '--no-color', '--no-usage-report', '-e',
                       "BASE_URL=http://host.docker.internal:$port") + $envArgs + @('/scripts/order-detail-load.js')
        $result = Invoke-Native 'docker' $arguments
    } else {
        $arguments = @('run', '-q', '--no-color', '--no-usage-report', '-e', "BASE_URL=http://localhost:$port") + $envArgs +
                     @((Join-Path $k6Dir 'order-detail-load.js'))
        $result = Invoke-Native 'k6' $arguments
    }
    $line = @($result.Lines | Where-Object { $_ -like 'K6_SUMMARY *' }) | Select-Object -Last 1
    if ($result.ExitCode -ne 0 -or -not $line) {
        Stop-Script ("k6 không chạy xong (mã $($result.ExitCode)). 20 dòng cuối:`n  " +
                     ((@($result.Lines) | Select-Object -Last 20) -join "`n  "))
    }
    return $line.Substring('K6_SUMMARY '.Length) | ConvertFrom-Json
}

# Một số đo của Actuator: @{ VALUE = ...; COUNT = ... } theo statistic; $query vd '?tag=area:heap'
function Get-AppMetric([int]$port, [string]$name, [string]$query) {
    $values = @{}
    try {
        $response = $script:probe.GetAsync("http://localhost:$port/actuator/metrics/$name$query").GetAwaiter().GetResult()
        if ([int]$response.StatusCode -eq 200) {
            $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            foreach ($m in $json.measurements) { $values[$m.statistic] = [double]$m.value }
        }
        $response.Dispose()
    } catch { }
    return $values
}
