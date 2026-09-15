# 规范 §16：「崩溃测试使用独立测试进程与外部重启脚本，在明确阶段注入终止」。
# 针对自动备份路径做真·进程终止注入：在备份进行中的不同时刻强杀应用进程，
# 然后重启并断言「至少一代完整数据可用」且「没有半成品被当成有效备份发布」。
#
# 为什么盯这条路径：规范 §14 要求执行顺序严格为
#   新包成功 -> 校验成功 -> 记录发布成功 -> 才决定旧包是否清理。
# 顺序写反（先删后建）时，一次强杀就能把唯一可用的备份清掉。
#
# 判定「半成品」的简易而有效的办法：manifest.json 是**最后**写入的条目，
# 所以任何被截断的包必然缺少 manifest.json。已发布的包必须都带它。
param(
  # 备份在启动后约 1-2 秒才开始、数秒内完成；延迟太短只会"杀在它开始之前"，
  # 那样虽然也是有效的崩溃点，但覆盖不到"正在写包"这一最关键的时刻。
  [int[]]$KillDelaysMs = @(1200, 1800, 2400, 3000, 4000, 6000),
  [int]$SeedNotes = 4000
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
$pkg = 'com.purenote.local'
$failures = 0

function Run-Adb {
  param([string]$Cmd)
  (& $adb shell $Cmd 2>&1) -join ' '
}

Write-Output '=== 0) 安装 + 清数据 ==='
Push-Location 'E:\ox\xmnote-apple'
& cmd /c gradlew.bat assembleDebug --console=plain 2>&1 | Select-String -Pattern 'BUILD|FAILED' | Select-Object -First 2
Pop-Location
& $adb install -r 'E:\ox\xmnote-apple\app\build\outputs\apk\debug\app-debug.apk' | Out-Null
Run-Adb "pm grant $pkg android.permission.POST_NOTIFICATIONS" | Out-Null
Run-Adb "am force-stop $pkg" | Out-Null
Run-Adb "pm clear $pkg" | Out-Null

Write-Output '=== 1) 启动一次，建立库与存储指针 ==='
$startOut = Run-Adb "am start -n $pkg/.MainActivity"
Write-Output "am start -> $startOut"
# pm clear 之后是冷启动，建库要走 IO 协程。轮询 ready.json 而不是 active.json：
# 指针是在"解析出活动存储"时就落盘的，库文件要到真正打开时才创建 ——
# 盯着指针就会在"库还没建好"时把应用停掉，留下一个只有头没有表的半成品文件。
# ready.json 由 DatabaseProvider 在成功打开数据库之后才写入。
$active = ''
for ($i = 0; $i -lt 25; $i++) {
  Start-Sleep -Seconds 1
  $ready = Run-Adb "run-as $pkg cat files/store-control/ready.json"
  if ($ready -match '"epoch"') {
    $active = Run-Adb "run-as $pkg cat files/store-control/active.json"
    if ($active -match '"dbName":"([^"]+)"') { break }
  }
}
if ($active -notmatch '"dbName":"([^"]+)"') {
  $diag = Run-Adb "pidof $pkg"
  Write-Output "诊断: pidof=[$diag]"
  Write-Output "诊断: files/ -> $(Run-Adb 'run-as com.purenote.local ls files/')"
  Write-Output "诊断: 最近崩溃 -> $((& $adb logcat -d -b crash 2>&1 | Select-String 'purenote' | Select-Object -Last 3) -join ' / ')"
  throw "找不到活动库名: [$active]"
}
Run-Adb "am force-stop $pkg" | Out-Null
Start-Sleep -Seconds 1
$db = $Matches[1]
Write-Output "active db = $db"

# 指针是在"解析出活动存储"时落盘的，库文件要到真正打开时才创建。
# 所以"指针存在"不等于"库已建好"；急着用 sqlite3 去开，SQLite 会新建一个空文件，
# 于是拿到 "no such table: notes" —— 上一版正是这样把假绿喂给断言的。
$size = '0'
for ($i = 0; $i -lt 20; $i++) {
  $size = (Run-Adb "run-as $pkg sh -c 'wc -c < databases/$db'").Trim()
  if ($size -match '^\d+$' -and [int]$size -gt 0) { break }
  Start-Sleep -Seconds 1
}
if (-not ($size -match '^\d+$') -or [int]$size -le 0) { throw "数据库未建立，size=[$size]" }
Write-Output "db size = $size bytes"

# 兜底：万一还是撞上了半成品，反复"启动 -> 停止 -> 试读"直到 notes 真的可读。
$schemaReady = $false
$probe = ''
for ($attempt = 0; $attempt -lt 8; $attempt++) {
  $probe = (Run-Adb "run-as $pkg sqlite3 databases/$db 'SELECT COUNT(*) FROM notes;'").Trim()
  if ($probe -match '^\d+$') { $schemaReady = $true; break }
  Run-Adb "am start -n $pkg/.MainActivity" | Out-Null
  Start-Sleep -Seconds 8
  Run-Adb "am force-stop $pkg" | Out-Null
  Start-Sleep -Seconds 1
}
if (-not $schemaReady) { throw "数据库 schema 始终未建立（最后一次探测: [$probe]）" }
Write-Output "schema ready (notes 可读)"

Write-Output "=== 2) 灌入 $SeedNotes 条笔记（让导出耗时可观测）==="
$sql = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < $SeedNotes) INSERT INTO notes(uuid,kind,title,body,images,color,pinned,trashed,repeat_type,all_day,revision,body_format_version,created_at,updated_at) SELECT 'crash-'||x,0,'标题 '||x,printf('%.*c',200+(x%200),'x'),'',0,0,0,0,0,1,2,1789000000000+x,1789000000000+x FROM c;"
Set-Content -Path 'E:\DSH\tmp-xmnote\crash_seed.sql' -Value $sql -Encoding ASCII
& $adb push 'E:\DSH\tmp-xmnote\crash_seed.sql' /data/local/tmp/crash_seed.sql | Out-Null
Run-Adb "run-as $pkg sh -c 'sqlite3 databases/$db < /data/local/tmp/crash_seed.sql'" | Out-Null
$before = (Run-Adb "run-as $pkg sqlite3 databases/$db 'SELECT COUNT(*) FROM notes;'").Trim()
# 防"空跑通过"：读不到数字就直接失败。
# 上一版没有这个守卫，两次错误字符串互等也被判成 OK，是典型的假绿。
if ($before -notmatch '^\d+$') { throw "灌数据失败，读到的不是数字：[$before]" }
if ([int]$before -ne $SeedNotes) { throw "灌入数量不符：期望 $SeedNotes，实际 $before" }
Write-Output "seeded notes = $before"

Write-Output ''
Write-Output '=== 3) 强杀注入 ==='
foreach ($delay in $KillDelaysMs) {
  Run-Adb "am force-stop $pkg" | Out-Null
  Start-Sleep -Milliseconds 300
  # 让本次启动确实会去做备份：
  # runImmediatelyIfDue 要求"距上次成功超过 30 分钟且内容变过"，
  # 而上一轮刚成功过一次，不清掉状态就会被正确地跳过 —— 那样这一节什么也没注入到。
  Run-Adb "run-as $pkg rm -f no_backup/local-backups/state.json" | Out-Null
  Run-Adb "am start -n $pkg/.MainActivity" | Out-Null
  Start-Sleep -Milliseconds $delay
  Run-Adb "am force-stop $pkg" | Out-Null
  Start-Sleep -Milliseconds 300

  Run-Adb "am start -n $pkg/.MainActivity" | Out-Null
  Start-Sleep -Seconds 5
  $alive = ((& $adb shell pidof $pkg 2>&1) -join ' ').Trim()
  # 先停掉应用再查库：应用活着时它的连接占着锁，外部 sqlite3 会报 database is locked，
  # 于是拿到的是错误字符串而不是行数（上一版正是这样把假绿喂给了断言）。
  Run-Adb "am force-stop $pkg" | Out-Null
  Start-Sleep -Milliseconds 600
  $after = (Run-Adb "run-as $pkg sqlite3 databases/$db 'SELECT COUNT(*) FROM notes;'").Trim()

  $ok = ($alive -ne '') -and ($after -eq $before)
  if (-not $ok) { $failures++ }
  $verdict = if ($ok) { 'OK' } else { 'FAILED' }
  if ($after -notmatch '^\d+$') { throw "读取笔记数失败：[$after]" }
  Write-Output ("kill@{0,5}ms  ->  存活={1}  笔记数 {2} -> {3}  {4}" -f $delay, ($alive -ne ''), $before, $after, $verdict)
  Run-Adb "am force-stop $pkg" | Out-Null
  Start-Sleep -Milliseconds 300
}

Write-Output ''
Write-Output '=== 4) 已发布的备份必须都完整（半成品必然缺 manifest.json）==='
$listing = Run-Adb "run-as $pkg ls no_backup/local-backups/"
$zips = @(($listing -split ' ') | Where-Object { $_ -match '\.purenote\.zip$' })
Write-Output "已发布备份数: $($zips.Count)"
# 同样防空跑：一次都没产出备份的话，这一节什么也没验证
if ($zips.Count -eq 0) { throw "注入期间没有产出任何备份，本节断言是空的" }
$bad = 0
foreach ($z in $zips) {
  $entries = Run-Adb "run-as $pkg sh -c 'cd no_backup/local-backups && unzip -l $z'"
  $hasManifest = $entries -match 'manifest\.json'
  $hasJson = $entries -match 'backup\.json'
  if (-not ($hasManifest -and $hasJson)) {
    $bad++
    Write-Output "  半成品被发布: $z"
  }
}
Write-Output "不完整的已发布备份数: $bad"
if ($bad -gt 0) { $failures++ }

Write-Output ''
if ($failures -eq 0) {
  Write-Output "CRASH INJECTION: PASS（$($KillDelaysMs.Count) 次强杀，数据与备份均完整）"
} else {
  Write-Output "CRASH INJECTION: FAIL（$failures 项）"
  exit 1
}
