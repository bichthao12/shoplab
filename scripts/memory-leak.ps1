<#
.SYNOPSIS
  Memory leak: cache lịch sử thanh toán theo orderId không giới hạn (TRƯỚC) so với Caffeine có maximumSize (SAU). Chạy
  tải -Minutes phút (mặc định 20), mỗi phút ép GC rồi đọc heap, in sẵn số "Heap sau 20 phút chạy tải" cho sổ đo, chụp
  heap dump của bản leak để mở bằng Eclipse MAT.

.DESCRIPTION
  Gọi API qua k6 (không chạy test, không chạy SQL). Script tự build và chạy app (java -jar), nên phải dừng app đang chạy
  ở cổng -Port (8080). Cần: PostgreSQL của docker compose đang chạy và đã có đơn (càng nhiều đơn càng tốt: bộ dữ liệu
  lớn 2 triệu đơn), JDK 21 (jcmd), git, k6 (nếu chưa cài thì chạy bằng Docker).

  Mỗi bản (TRƯỚC rồi SAU) chạy riêng một app:
    TRƯỚC  scripts/bugs/19-payment-cache-unbounded.patch: Map theo orderId không giới hạn, không hết hạn. Script copy
           code sang thư mục tạm, áp patch, build, chép jar về target/memory-leak/.
    SAU    code hiện tại: Caffeine, maximumSize 10.000, expireAfterWrite 1 phút (PaymentHistoryCache).
  Với mỗi bản: đo heap lúc đầu, rồi -Minutes lần: k6 1 phút (-Vus người dùng ảo gọi trang chi tiết của đơn NGẪU NHIÊN
  trong mọi đơn, ORDER_PICK=all, nên gần như mỗi request một đơn mới), sau đó ép GC (jcmd <pid> GC.run, full GC: chỉ
  còn đối tượng đang được giữ) và đọc heap đang dùng (jvm.memory.used, area=heap) cùng số đơn trong cache.
  Cuối mỗi bản: 8 lớp chiếm nhiều heap nhất (jcmd GC.class_histogram); bản TRƯỚC thêm heap dump
  target/memory-leak/before.hprof (jcmd <pid> GC.heap_dump ...), mở bằng Eclipse MAT.

  Hết khoảng (Minutes + 1) x 2 phút cộng build, vd 45 phút với 20 phút. Log của app: target/memory-leak/<bản>.log.
  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu đo xong và khớp thẻ (TRƯỚC tăng dần, SAU đi ngang); 1 nếu không; 2 nếu không chạy được.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\memory-leak.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\memory-leak.ps1 -Minutes 3      # chạy thử nhanh

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\memory-leak.ps1 -Case before -Minutes 20
#>
param(
    [ValidateSet('all', 'before', 'after')][string]$Case = 'all',
    [ValidateRange(1, 240)][int]$Minutes = 20,
    [ValidateRange(1, 5000)][int]$Vus = 200,
    [ValidateSet('auto', 'local', 'docker')][string]$K6 = 'auto',
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [switch]$NoHeapDump,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'target/memory-leak'
$vi = [System.Globalization.CultureInfo]::GetCultureInfo('vi-VN')

function Stop-Script([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 2
}

. (Join-Path $PSScriptRoot 'lib/shoplab-app.ps1')

function Format-Num([object]$value, [string]$format) {
    if ($null -eq $value) { return '–' }
    return ([double]$value).ToString($format, $vi)
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Kiểm tra trước khi chạy

Write-Host 'Memory leak: cache lịch sử thanh toán theo orderId, không giới hạn (TRƯỚC) và Caffeine maximumSize (SAU)'
Write-Host ''

$health = Invoke-Native 'docker' @('inspect', '-f', '{{.State.Health.Status}}', 'shoplab-postgres')
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script 'Container shoplab-postgres chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d'
}
if (Test-Port $Port) {
    Stop-Script ("Đã có app chạy ở cổng ${Port}: dừng nó trước (Ctrl+C). Script tự chạy app, bản TRƯỚC rồi bản SAU.")
}
$tools = Find-Java
if (-not $tools.Jcmd) {
    Stop-Script 'Không thấy jcmd cạnh java (đang dùng JRE?): cần JDK 21 để ép GC và chụp heap dump.'
}
Resolve-K6 $K6 $Port
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$phases = @()
if ($Case -in @('all', 'before')) { $phases += @{ Name = 'before'; Title = 'TRƯỚC: Map theo orderId không giới hạn (bản lỗi, patch 19)' } }
if ($Case -in @('all', 'after')) { $phases += @{ Name = 'after'; Title = 'SAU: Caffeine, maximumSize 10.000, expireAfterWrite 1 phút' } }
Write-Host ("{0} bản, mỗi bản {1} phút tải ({2} người dùng ảo, đơn ngẫu nhiên trong mọi đơn); hết khoảng {3} phút cộng build" -f
            $phases.Count, $Minutes, $Vus, ($phases.Count * ($Minutes + 2)))

# ---------------------------------------------------------------------------------------------------------------
# 2. Build

$jars = @{}
if ($Case -in @('all', 'before')) {
    Write-Host 'Build bản TRƯỚC: copy code sang thư mục tạm, áp scripts/bugs/19-payment-cache-unbounded.patch (khoảng 1 phút)...'
    $bug = New-BuggyBuild '19-payment-cache-unbounded.patch'
    $jars['before'] = Join-Path $outDir 'shoplab-before.jar'
    Copy-Item $bug.Jar $jars['before'] -Force
    Remove-Item -Recurse -Force $bug.Dir -ErrorAction SilentlyContinue
}
if ($Case -in @('all', 'after')) {
    $existing = Get-ChildItem (Join-Path $root 'target') -Filter 'shoplab-*.jar' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
    if ($SkipBuild -and $existing) {
        $jars['after'] = $existing.FullName
    } else {
        Write-Host 'Build bản SAU: mvnw -DskipTests package (khoảng 1 phút)...'
        $jars['after'] = Build-Jar $root
    }
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Đo

# Ép full GC rồi đọc heap đang dùng (MB) và số đơn trong cache
function Measure-Heap($process) {
    $gc = Invoke-Native $tools.Jcmd @([string]$process.Id, 'GC.run')
    if ($gc.ExitCode -ne 0) { Stop-Script ("jcmd GC.run lỗi:`n  " + ($gc.Lines -join "`n  ")) }
    $heap = Get-AppMetric $Port 'jvm.memory.used' '?tag=area:heap'
    $cache = Get-AppMetric $Port 'order.payment-history.cache.size' ''
    return @{ HeapMb = $heap['VALUE'] / 1MB; Cache = $cache['VALUE'] }
}

function Invoke-Phase($phase) {
    $name = $phase.Name
    Write-Host ''
    Write-Host "== $($phase.Title)" -ForegroundColor Cyan
    $log = Join-Path $outDir "$name.log"
    $process = Start-ShoplabApp $tools.Java $jars[$name] $root $Port $log @() @()
    $samples = @()
    try {
        $start = Measure-Heap $process
        $samples += [pscustomobject]@{ Minute = 0; HeapMb = $start.HeapMb; Cache = $start.Cache; Rps = $null; Errors = $null }
        Write-Host ("  phút 0: heap sau GC {0} MB (vừa khởi động)" -f (Format-Num $start.HeapMb '0.0'))
        for ($m = 1; $m -le $Minutes; $m++) {
            $k = Invoke-K6Summary $Port @{ VUS = $Vus; DURATION = '60s'; ORDER_PICK = 'all' }
            $h = Measure-Heap $process
            $samples += [pscustomobject]@{ Minute = $m; HeapMb = $h.HeapMb; Cache = $h.Cache; Rps = [double]$k.rps; Errors = [int]$k.not200 }
            Write-Host ("  phút {0}/{1}: heap sau GC {2} MB, cache {3} đơn; {4} req/s, lỗi {5}" -f $m, $Minutes,
                        (Format-Num $h.HeapMb '0.0'), (Format-Num $h.Cache '#,0'), (Format-Num $k.rps '0'), $k.not200)
        }

        # Lớp chiếm nhiều heap nhất (sau full GC: chỉ còn đối tượng đang được giữ)
        $histogram = Invoke-Native $tools.Jcmd @([string]$process.Id, 'GC.class_histogram')
        Write-Host '  8 lớp chiếm nhiều heap nhất (jcmd GC.class_histogram):'
        $rows = @($histogram.Lines | Where-Object { $_ -match '^\s*\d+:\s+\d+\s+\d+\s+\S+' } | Select-Object -First 8)
        foreach ($row in $rows) {
            if ($row -match '^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)') {
                Write-Host ("    {0,12} đối tượng {1,10} MB  {2}" -f (Format-Num $Matches[1] '#,0'),
                            (Format-Num ([double]$Matches[2] / 1MB) '0.0'), $Matches[3])
            }
        }

        if ($name -eq 'before' -and -not $NoHeapDump) {
            $dump = 'target/memory-leak/before.hprof'   # tương đối: app (thư mục làm việc là gốc repo) tự ghi
            Remove-Item (Join-Path $root $dump) -ErrorAction SilentlyContinue
            $result = Invoke-Native $tools.Jcmd @([string]$process.Id, 'GC.heap_dump', $dump)
            $file = Get-Item (Join-Path $root $dump) -ErrorAction SilentlyContinue
            if ($result.ExitCode -eq 0 -and $file) {
                Write-Host ("  Heap dump: {0} ({1} MB), lệnh: jcmd {2} GC.heap_dump {3}" -f $file.FullName,
                            (Format-Num ($file.Length / 1MB) '0'), $process.Id, $dump)
            } else {
                Write-Host ("  Không chụp được heap dump: " + ($result.Lines -join ' ')) -ForegroundColor Yellow
            }
        }
    } finally {
        Stop-ShoplabApp $process
    }
    return $samples
}

$results = @{}
foreach ($p in $phases) {
    $results[$p.Name] = Invoke-Phase $p
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Bảng theo phút, số cho sổ đo

Write-Host ''
Write-Host 'Heap sau GC theo phút (MB; cache: số đơn trong cache lịch sử thanh toán):' -ForegroundColor Cyan
$table = for ($m = 0; $m -le $Minutes; $m++) {
    $row = [ordered]@{ 'Phút' = $m }
    foreach ($p in $phases) {
        $s = $results[$p.Name][$m]
        $label = 'TRƯỚC'
        if ($p.Name -eq 'after') { $label = 'SAU' }
        $row["$label heap"] = Format-Num $s.HeapMb '0.0'
        $row["$label cache"] = Format-Num $s.Cache '#,0'
    }
    [pscustomobject]$row
}
$table | Format-Table -AutoSize | Out-String -Width 140 | Write-Host

$before = $results['before']
$after = $results['after']
$ok = $true
foreach ($p in $phases) {
    $s = $results[$p.Name]
    $growth = $s[-1].HeapMb - $s[1].HeapMb
    $perMinute = 0
    if ($Minutes -gt 1) { $perMinute = $growth / ($Minutes - 1) }
    $label = 'TRƯỚC'
    if ($p.Name -eq 'after') { $label = 'SAU' }
    Write-Host ("{0}: phút 1 → phút {1}: {2} → {3} MB, {4} MB mỗi phút; cache {5} đơn" -f $label, $Minutes,
                (Format-Num $s[1].HeapMb '0.0'), (Format-Num $s[-1].HeapMb '0.0'), (Format-Num $perMinute '0.0'),
                (Format-Num $s[-1].Cache '#,0'))
}
if ($before -and $after) {
    Write-Host ''
    Write-Host ("Nhập vào sổ (Heap sau {0} phút chạy tải, MB): TRƯỚC {1}   SAU {2}" -f $Minutes,
                (Format-Num $before[-1].HeapMb '0.0'), (Format-Num $after[-1].HeapMb '0.0')) -ForegroundColor Cyan
    if ($Minutes -lt 2) {
        Write-Host 'Cần ít nhất 2 phút (-Minutes 2) để thấy heap tăng hay đi ngang.' -ForegroundColor Yellow
        $ok = $false
    } else {
        # Thẻ: SAU đi ngang, TRƯỚC tăng dần. Tính từ phút 1 (phút 0 là lúc vừa khởi động, chưa có tải), theo MB mỗi phút
        $beforeRate = ($before[-1].HeapMb - $before[1].HeapMb) / ($Minutes - 1)
        $afterRate = ($after[-1].HeapMb - $after[1].HeapMb) / ($Minutes - 1)
        $ok = ($afterRate -le 0.5) -and ($beforeRate -ge 2)
        if ($ok) {
            Write-Host ("Khớp thẻ: SAU đi ngang ({0} MB mỗi phút), TRƯỚC tăng dần ({1} MB mỗi phút): mỗi đơn từng được xem nằm lại trong Map mãi mãi." -f
                        (Format-Num $afterRate '0.0'), (Format-Num $beforeRate '0.0')) -ForegroundColor Green
        } else {
            Write-Host ("KHÁC THẺ: TRƯỚC {0} MB mỗi phút, SAU {1} MB mỗi phút (mong đợi TRƯỚC từ 2 MB, SAU không quá 0,5 MB)." -f
                        (Format-Num $beforeRate '0.0'), (Format-Num $afterRate '0.0')) -ForegroundColor Yellow
        }
    }
}
if ($before -and -not $NoHeapDump) {
    Write-Host ''
    Write-Host 'Tìm leak trong heap dump bằng Eclipse MAT (File > Open Heap Dump > target/memory-leak/before.hprof):'
    Write-Host '  - Leak Suspects Report: nghi phạm số 1 là com.shoplab.order.internal.PaymentHistoryCache, giữ một'
    Write-Host '    java.util.concurrent.ConcurrentHashMap chiếm phần lớn heap đang dùng.'
    Write-Host '  - Dominator Tree: PaymentHistoryCache → ConcurrentHashMap → Node[] → hàng trăm nghìn List12 → PaymentEvent.'
    Write-Host '  - Histogram: PaymentEvent, ConcurrentHashMap$Node, Instant đều hàng trăm nghìn đối tượng (như bảng ở trên).'
}
if ($ok) { exit 0 }
exit 1
