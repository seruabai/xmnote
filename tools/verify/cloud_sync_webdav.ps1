param(
    [int]$Port = 8087,
    [string]$Root = (Join-Path $env:TEMP "purenote-webdav-verify"),
    [string]$Target = "10.0.2.2"
)

# 本机临时 WebDAV 服务器，用于云同步（阶段 H）的端到端验证。
# 这是**真实**的 WebDAV 服务器（wsgidav），不是 mock：PROPFIND 返回真正的 207 Multi-Status，
# PUT 真的落盘。验收要求"独立验证"，用假服务器证明不了协议交互。
#
# 依赖：python -m pip install --user wsgidav cheroot
# 用法：powershell -ExecutionPolicy Bypass -File tools/verify/cloud_sync_webdav.ps1

$ErrorActionPreference = "Stop"

if (Test-Path $Root) { Remove-Item -Recurse -Force $Root }
New-Item -ItemType Directory -Force -Path $Root | Out-Null

# 注意：wsgidav 4.x 没有 __main__，必须走 wsgidav.server.server_cli（入口脚本 wsgidav.exe 常不在 PATH）。
# 服务器日志写到临时文件里：起不来时要能看见原因，而不是只报"未就绪"。
$serverLog = Join-Path $env:TEMP "purenote-webdav-server.log"
$server = Start-Process -PassThru -WindowStyle Hidden -FilePath "python" -RedirectStandardOutput $serverLog -RedirectStandardError "$serverLog.err" -ArgumentList @(
    "-m", "wsgidav.server.server_cli",
    "--host", "0.0.0.0",
    "--port", "$Port",
    "--root", "$Root",
    "--auth", "anonymous",
    "--server", "cheroot"
)

Write-Host "已启动临时 WebDAV 服务器 pid=$($server.Id) 端口=$Port 目录=$Root"
# 先确认服务器进程还活着：起不来时最常见的原因是参数没传对（脚本路径含空格会被切开）
Start-Sleep -Seconds 2
if ($server.HasExited) {
    $tail = ""
    foreach ($log in @($serverLog, "$serverLog.err")) { if (Test-Path $log) { $tail += (Get-Content $log -Tail 15 | Out-String) } }
    throw "WebDAV 服务器进程已退出（退出码 $($server.ExitCode)）。日志：`n$tail"
}

try {
    # 等端口就绪（最多 20 秒）。
    # 只探测"端口是否在监听"：PROPFIND 属于自定义动词，Invoke-WebRequest 不接受它，
    # 而 .NET 静态调用在受限语言模式下会被拒。真正的协议验证交给设备测试——
    # 它跑的是产品自己的 WebDavTransport，比脚本发一次探测请求更有说服力。
    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
            $ready = $true
            break
        }
    }
    if (-not $ready) {
        $tail = ""
        foreach ($log in @($serverLog, "$serverLog.err")) {
            if (Test-Path $log) { $tail += (Get-Content $log -Tail 15 | Out-String) }
        }
        throw "WebDAV 服务器未在 20 秒内就绪。日志尾部：`n$tail"
    }
    Write-Host "服务器端口已就绪：127.0.0.1:$Port"

    Push-Location (Resolve-Path (Join-Path $PSScriptRoot "..\.."))
    try {
        # 用真实服务器跑设备上的云同步用例（模拟器里 10.0.2.2 就是宿主机的 127.0.0.1）
        # 只跑模拟器：USB 连接的实机在 MIUI 上需要手动允许安装，否则整个任务会失败
        $env:ANDROID_SERIAL = "emulator-5554"
        $args = @(
            ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.class=com.purenote.local.sync.CloudSyncWebDavTest",
            "-Pandroid.testInstrumentationRunnerArguments.webdavPort=$Port",
            "-Pandroid.testInstrumentationRunnerArguments.webdavHost=$Target",
            "--console=plain"
        )
        & ".\gradlew.bat" @args
        if ($LASTEXITCODE -ne 0) { throw "设备测试失败，退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    # 独立核对：文件真的落到服务器目录里了，且大小非零
    $files = Get-ChildItem -Recurse -File $Root -ErrorAction SilentlyContinue
    if (-not $files) { throw "服务器目录里没有任何文件：同步没有真正落盘" }
    foreach ($f in $files) {
        if ($f.Length -le 0) { throw "落盘文件为空：$($f.FullName)" }
        Write-Host ("落盘核对：" + $f.FullName.Substring($Root.Length) + " " + $f.Length + " 字节  sha256=" + (Get-FileHash $f.FullName -Algorithm SHA256).Hash.Substring(0, 16))
    }
    Write-Host "端到端验证通过：设备 -> 本机 WebDAV 往返成功"
    exit 0
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
    Write-Host "已关闭临时服务器"
}
