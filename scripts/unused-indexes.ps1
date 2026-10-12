<#
.SYNOPSIS
  Tìm index không ai dùng (pg_stat_user_indexes.idx_scan = 0) và index bị index khác làm thay việc trong schema public
  của DB shoplab, rồi xoá nếu có -Drop.

.DESCRIPTION
  Chỉ chạy SQL qua docker exec psql (không gọi API, không chạy test). Mặc định chỉ liệt kê; -Drop mới xoá, sau khi
  hỏi lại (trừ khi có -Force), bằng DROP INDEX CONCURRENTLY (không khoá ghi bảng). Trước khi xoá, lưu lệnh dựng lại
  từng index vào một file trong thư mục tạm.

  Mỗi index được xếp vào một trong các trường hợp:
    GIỮ  UNIQUE / khoá chính: giữ ràng buộc dữ liệu, không chỉ để đọc nhanh.
    GIỮ  bảng flyway_schema_history: của Flyway.
    GIỮ  do migration của app tạo (tìm tên index trong src/main/resources/db/migration): xoá tay chỉ có tác dụng trên DB
         này, DB mới lại có nó. Muốn bỏ thì viết migration DROP INDEX, sau khi xem câu nào cần nó.
    XOÁ  index hỏng: dựng bằng CREATE INDEX CONCURRENTLY bị lỗi giữa chừng, không câu nào dùng được.
    XOÁ  trùng: một index khác (được giữ) có cùng các cột, hoặc các cột của index này là phần đầu các cột của nó, nên làm
         được việc của index này. Hai index trùng hệt thì planner thường dùng index tạo sau, index tạo trước có
         idx_scan = 0: chỉ nhìn idx_scan sẽ xoá nhầm index gốc, giữ bản trùng.
    XOÁ  không câu nào dùng: idx_scan = 0, tức không lần đọc nào dùng nó kể từ khi thống kê bắt đầu. Chỉ xoá khi thống
         kê đã có ít nhất -MinHours giờ (mặc định 24); trước đó ghi "chưa đủ lâu".
    GIỮ  được dùng (idx_scan > 0), hoặc là index duy nhất phục vụ một khoá ngoại (xoá thì mỗi lần xoá / sửa dòng ở bảng
         cha, PostgreSQL phải đọc cả bảng con).

  idx_scan chưa đủ để kết luận: nó chỉ đếm từ lần reset thống kê gần nhất (pg_stat_reset()), mất khi PostgreSQL tắt
  không sạch, chỉ đếm trên server này (không tính replica), và câu hiếm khi chạy (báo cáo cuối tháng) vẫn cần index.
  Script in khoảng thời gian thống kê chắc chắn đã đếm. Kết nối đang rảnh (vd kết nối của app trong pool) ghi số đếm
  của mình chậm tới khoảng 10 giây: vừa chạy tải xong thì đợi một chút rồi mới chạy script.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7+.
  Thoát với mã 0 nếu chạy xong (đã xoá, hoặc chỉ liệt kê), 1 nếu xoá một index bị lỗi, 2 nếu không chạy được hoặc huỷ.

.PARAMETER Drop
  Xoá các index được đánh dấu XOÁ.

.PARAMETER Force
  Không hỏi lại trước khi xoá.

.PARAMETER MinHours
  Thống kê phải đếm được ít nhất bao nhiêu giờ thì mới xoá index có idx_scan = 0 (mặc định 24). Index trùng và index
  hỏng không phụ thuộc thống kê nên không cần. Trên DB thí nghiệm vừa chạy app vài phút: -MinHours 0.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\unused-indexes.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\unused-indexes.ps1 -Drop -MinHours 0
#>
param(
    [switch]$Drop,
    [switch]$Force,
    [ValidateRange(0, 100000)][double]$MinHours = 24
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$container = 'shoplab-postgres'
$psql = @('exec', $container, 'psql', '-X', '-U', 'shoplab', '-d', 'shoplab')
$migrationDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'src/main/resources/db/migration'

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
    return @($result.Lines | Where-Object { $_ -ne '' })
}

function Format-Size([long]$bytes) {
    if ($bytes -ge 1MB) { return '{0:N0} MB' -f ($bytes / 1MB) }
    if ($bytes -ge 1KB) { return '{0:N0} kB' -f ($bytes / 1KB) }
    return "$bytes B"
}

# ---------------------------------------------------------------------------------------------------------------
# 1. Container, migration, khoảng thời gian thống kê đã đếm

$health = Invoke-Docker @('inspect', '-f', '{{.State.Health.Status}}', $container)
if ($health.ExitCode -ne 0 -or ($health.Lines -join '') -ne 'healthy') {
    Stop-Script "Container $container chưa chạy (hoặc chưa healthy). Chạy: docker compose up -d"
}
if (-not (Test-Path $migrationDir)) {
    Stop-Script "Không thấy thư mục migration ${migrationDir}: chạy script từ trong repo shoplab."
}

# Tên index do migration tạo → file migration (file sau cùng nhắc tới nó)
$migrationIndexes = @{}
Get-ChildItem $migrationDir -Filter '*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') } | ForEach-Object {
    $file = $_.Name
    foreach ($m in [regex]::Matches((Get-Content $_.FullName -Raw), '(?i)CREATE\s+(UNIQUE\s+)?INDEX\s+(CONCURRENTLY\s+)?(IF\s+NOT\s+EXISTS\s+)?([a-z0-9_]+)')) {
        $migrationIndexes[$m.Groups[4].Value.ToLower()] = $file
    }
}

# Thống kê chắc chắn đã đếm từ lúc này: lần reset gần nhất, hoặc lúc PostgreSQL khởi động (tắt không sạch thì thống kê
# chỉ có từ lần khởi động đó; tắt sạch thì còn giữ, nên khoảng thật có thể dài hơn)
$window = @(Invoke-Sql ("SELECT to_char(w AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || '|' || " +
                       "round((extract(epoch FROM now() - w) / 3600)::numeric, 1) FROM (SELECT greatest(pg_postmaster_start_time(), " +
                       "coalesce(stats_reset, '-infinity')) AS w FROM pg_stat_database WHERE datname = current_database()) s"))[0] -split '\|'
$windowHours = [double]::Parse($window[1], [System.Globalization.CultureInfo]::InvariantCulture)

# ---------------------------------------------------------------------------------------------------------------
# 2. Mọi index của schema public: cột, chiều, thống kê, khoá ngoại mà nó là index duy nhất phục vụ

$sql = @'
WITH idx AS (
  SELECT i.indexrelid AS oid, i.indrelid AS tbl, t.relname AS table_name, c.relname AS index_name,
         i.indkey::text AS keys, i.indoption::text AS opts, i.indnkeyatts AS nkeys,
         i.indisunique AS uniq, i.indisvalid AS valid,
         (i.indexprs IS NULL AND i.indpred IS NULL AND am.amname = 'btree') AS plain,
         coalesce(s.idx_scan, 0) AS scans, s.last_idx_scan, pg_relation_size(i.indexrelid) AS bytes,
         pg_get_indexdef(i.indexrelid) AS def
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_class t ON t.oid = i.indrelid
  JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = 'public'
  JOIN pg_am am ON am.oid = c.relam
  LEFT JOIN pg_stat_user_indexes s ON s.indexrelid = i.indexrelid
),
fk AS (
  SELECT f.conrelid AS tbl, f.conname, (SELECT array_agg(x ORDER BY x) FROM unnest(f.conkey) x) AS cols
  FROM pg_constraint f WHERE f.contype = 'f'
),
fk_idx AS (
  SELECT f.conname, i.oid
  FROM fk f JOIN idx i ON i.tbl = f.tbl AND i.valid
  WHERE (SELECT array_agg(x::int2 ORDER BY x::int2)
         FROM unnest((string_to_array(i.keys, ' '))[1:cardinality(f.cols)]) x) = f.cols
)
SELECT i.oid, i.table_name, i.index_name, i.keys, i.opts, i.nkeys, i.uniq, i.valid, i.plain, i.scans,
       coalesce(to_char(i.last_idx_scan AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), ''), i.bytes,
       coalesce((SELECT string_agg(x.conname, ', ') FROM fk_idx x WHERE x.oid = i.oid
                 AND NOT EXISTS (SELECT 1 FROM fk_idx y WHERE y.conname = x.conname AND y.oid <> i.oid)), ''),
       i.def
FROM idx i ORDER BY i.table_name, i.index_name
'@
$indexes = foreach ($line in (Invoke-Sql (($sql -split "`r?`n") -join ' '))) {
    $p = $line -split '\|', 14
    [pscustomobject]@{
        Oid       = [long]$p[0]
        Table     = $p[1]
        Name      = $p[2]
        Keys      = @($p[3] -split ' ')
        Opts      = @($p[4] -split ' ')
        NKeys     = [int]$p[5]
        Unique    = $p[6] -eq 't'
        Valid     = $p[7] -eq 't'
        Plain     = $p[8] -eq 't'
        Scans     = [long]$p[9]
        LastScan  = $p[10]
        Bytes     = [long]$p[11]
        OnlyForFk = $p[12]
        Def       = $p[13]
        Migration = $migrationIndexes[$p[2]]
        Action    = ''
        Reason    = ''
    }
}
$indexes = @($indexes)
if ($indexes.Count -eq 0) {
    Stop-Script 'Schema public chưa có index nào (app chưa chạy lần nào?).'
}

# b làm được việc của a: cùng bảng, các cột khoá của a là phần đầu (hoặc toàn bộ) các cột khoá của b, cùng chiều
# (index một cột: chiều nào cũng được, B-tree đọc được cả hai chiều). Bỏ qua index có biểu thức / WHERE / không phải B-tree.
function Test-Covers($b, $a) {
    if ($b.Oid -eq $a.Oid -or $b.Table -ne $a.Table -or -not ($a.Plain -and $b.Plain -and $b.Valid)) { return $false }
    if ($b.NKeys -lt $a.NKeys) { return $false }
    for ($k = 0; $k -lt $a.NKeys; $k++) {
        if ($b.Keys[$k] -ne $a.Keys[$k]) { return $false }
        if ($a.NKeys -gt 1 -and $b.Opts[$k] -ne $a.Opts[$k]) { return $false }
    }
    return $true
}

# Index chắc chắn được giữ, không cần thống kê
function Test-Fixed($x) {
    return $x.Unique -or $x.Table -eq 'flyway_schema_history' -or [bool]$x.Migration
}

# ---------------------------------------------------------------------------------------------------------------
# 3. Kết luận cho từng index

foreach ($a in $indexes) {
    if ($a.Unique) { $a.Action = 'GIỮ'; $a.Reason = 'UNIQUE / khoá chính: giữ ràng buộc'; continue }
    if ($a.Table -eq 'flyway_schema_history') { $a.Action = 'GIỮ'; $a.Reason = 'bảng của Flyway'; continue }

    # Index được giữ (hoặc đang được dùng) làm được việc của a. Hai index trùng hệt cùng được dùng: xoá index tạo sau.
    $cover = $indexes | Where-Object {
        (Test-Covers $_ $a) -and ((Test-Fixed $_) -or $_.Scans -gt 0) -and
        -not ($_.NKeys -eq $a.NKeys -and -not (Test-Fixed $_) -and -not (Test-Fixed $a) -and $a.Scans -gt 0 -and $a.Oid -lt $_.Oid)
    } | Sort-Object @{ Expression = { -not (Test-Fixed $_) } }, @{ Expression = { $_.Scans }; Descending = $true } |
        Select-Object -First 1    # nêu index chắc chắn được giữ trước
    # Bản trùng hệt đang lấy lượt dùng của a (planner dùng index tạo sau)
    $twin = $indexes | Where-Object { (Test-Covers $_ $a) -and $_.NKeys -eq $a.NKeys -and $_.Scans -gt 0 } | Select-Object -First 1

    if ($a.Migration) {
        $a.Action = 'GIỮ'
        if ($a.Scans -eq 0 -and $twin) {
            $a.Reason = "do $($a.Migration) tạo; idx_scan = 0 vì planner dùng bản trùng $($twin.Name) thay"
        } elseif ($a.Scans -eq 0 -and $windowHours -lt $MinHours) {
            $a.Reason = "do $($a.Migration) tạo; idx_scan = 0 nhưng thống kê mới đếm $windowHours giờ"
        } elseif ($a.Scans -eq 0) {
            $a.Reason = "do $($a.Migration) tạo, chưa thấy câu nào dùng: muốn bỏ thì viết migration DROP INDEX"
        } else {
            $a.Reason = "do $($a.Migration) tạo, được dùng"
        }
        continue
    }
    if (-not $a.Valid) { $a.Action = 'XOÁ'; $a.Reason = 'index hỏng (CREATE INDEX CONCURRENTLY lỗi giữa chừng)'; continue }
    if ($cover) {
        $a.Action = 'XOÁ'
        if ($cover.NKeys -eq $a.NKeys) { $a.Reason = "trùng hệt $($cover.Name)" } else { $a.Reason = "các cột là phần đầu của $($cover.Name)" }
        continue
    }
    if ($a.Scans -gt 0) { $a.Action = 'GIỮ'; $a.Reason = 'được dùng'; continue }
    if ($a.OnlyForFk) { $a.Action = 'GIỮ'; $a.Reason = "index duy nhất phục vụ khoá ngoại $($a.OnlyForFk)"; continue }
    if ($windowHours -lt $MinHours) {
        $a.Action = 'GIỮ'
        $a.Reason = "idx_scan = 0 nhưng thống kê mới đếm $windowHours giờ (cần $MinHours, xem -MinHours)"
        continue
    }
    $a.Action = 'XOÁ'
    $a.Reason = 'không câu nào dùng (idx_scan = 0)'
}

Write-Host ("Thống kê chắc chắn đã đếm từ {0} UTC ({1} giờ): lần reset thống kê gần nhất, hoặc lần PostgreSQL khởi động gần nhất." -f
            $window[0], $windowHours)
Write-Host '  (Kết nối đang rảnh ghi số đếm chậm tới khoảng 10 giây: vừa gọi API xong thì đợi một chút rồi chạy lại.)'
if ($windowHours -lt 24) {
    Write-Host '  Chưa tới một ngày: idx_scan = 0 chưa nói được index không cần. Chỉ nên xoá theo idx_scan trên DB thí nghiệm.' -ForegroundColor Yellow
}
Write-Host ''
$indexes | Sort-Object @{ Expression = { $_.Action -ne 'XOÁ' } }, Table, Name | ForEach-Object {
    [pscustomobject]@{
        'Kết luận'     = $_.Action
        'Bảng'         = $_.Table
        'Index'        = $_.Name
        'idx_scan'     = $_.Scans
        'Dùng lần cuối' = $_.LastScan
        'Kích thước'   = Format-Size $_.Bytes
        'Lý do'        = $_.Reason
    }
} | Format-Table -AutoSize | Out-String -Width 220 | Write-Host

$toDrop = @($indexes | Where-Object { $_.Action -eq 'XOÁ' })
$freed = ($toDrop | Measure-Object -Property Bytes -Sum).Sum
if ($toDrop.Count -eq 0) {
    Write-Host 'Không có index nào để xoá.' -ForegroundColor Green
    exit 0
}
if (-not $Drop) {
    Write-Host ("{0} index có thể xoá ({1}). Chạy lại với -Drop để xoá (hỏi lại trước khi xoá)." -f $toDrop.Count, (Format-Size $freed)) -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------------------------------------------------------
# 4. Xoá

$odd = @($toDrop | Where-Object { $_.Name -notmatch '^[a-z_][a-z0-9_]*$' })
if ($odd.Count -gt 0) {
    Stop-Script "Tên index lạ, không xoá tự động: $(($odd | ForEach-Object { $_.Name }) -join ', ')"
}
if (-not $Force) {
    Write-Host ("Script sẽ XOÁ {0} index: {1}." -f $toDrop.Count, (($toDrop | ForEach-Object { $_.Name }) -join ', ')) -ForegroundColor Yellow
    $answer = Read-Host 'Gõ yes để tiếp tục'
    if ($answer -ne 'yes') {
        Write-Host 'Đã huỷ, không đổi gì.'
        exit 2
    }
}

# Lệnh dựng lại, lưu trước khi xoá
$backup = Join-Path ([System.IO.Path]::GetTempPath()) ('shoplab-dropped-indexes-{0}.sql' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$recreate = $toDrop | ForEach-Object { ($_.Def -replace '^CREATE INDEX ', 'CREATE INDEX CONCURRENTLY ') + ';' }
[System.IO.File]::WriteAllLines($backup, [string[]]$recreate, (New-Object System.Text.UTF8Encoding($false)))

$failed = 0
foreach ($x in $toDrop) {
    # Xoá vì không ai dùng: kiểm tra lại ngay trước khi xoá, phòng vừa có câu dùng tới
    if ($x.Reason -like 'không câu nào dùng*') {
        $now = (Invoke-Sql "SELECT idx_scan FROM pg_stat_user_indexes WHERE schemaname = 'public' AND indexrelname = '$($x.Name)'") -join ''
        if ($now -ne '0') {
            Write-Host "  bỏ qua $($x.Name): vừa được dùng (idx_scan = $now)" -ForegroundColor Yellow
            continue
        }
    }
    # Hai -c riêng: DROP INDEX CONCURRENTLY không chạy được trong transaction; lock_timeout giữ cho cả phiên
    $result = Invoke-Docker ($psql + @('-c', "SET lock_timeout = '10s'", '-c', "DROP INDEX CONCURRENTLY IF EXISTS public.$($x.Name)"))
    if ($result.ExitCode -eq 0) {
        Write-Host ("  đã xoá {0} ({1}): {2}" -f $x.Name, (Format-Size $x.Bytes), $x.Reason) -ForegroundColor Green
    } else {
        $failed++
        Write-Host "  LỖI khi xoá $($x.Name): $($result.Lines -join ' ')" -ForegroundColor Red
    }
}
Write-Host ''
Write-Host "Lệnh dựng lại các index vừa xoá (nếu cần): $backup"
$recreate | ForEach-Object { Write-Host "  $_" }
if ($failed -gt 0) {
    exit 1
}
exit 0
