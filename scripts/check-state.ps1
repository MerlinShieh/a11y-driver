# 一条命令看清「引擎为什么没跑起来」
# 用法: powershell -File scripts/check-state.ps1 [-Token <令牌>]
param(
    [string]$Token = "",
    [string]$Serial = "emulator-5554",
    [int]$HubPort = 7912
)

$ErrorActionPreference = 'Continue'
# adb 从环境变量取，找不到就用 PATH 里的
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { 'adb' }
if (-not (Test-Path $adb) -and -not (Get-Command $adb -ErrorAction SilentlyContinue)) {
    Write-Host "找不到 adb：请设置 ANDROID_HOME 或把它加入 PATH" -ForegroundColor Red
    exit 1
}
$pkg = 'com.mobileappauto.engine'
$svc = "$pkg/$pkg.AutoAccessibilityService"

function Line($label, $value, $state = '') {
    $icon = switch ($state) { 'ok' { '✅' } 'warn' { '🟡' } 'bad' { '❌' } default { '  ' } }
    "{0} {1,-34} {2}" -f $icon, $label, $value
}

Write-Host "`n=== 设备 ===" -ForegroundColor Cyan
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Line "adb 设备" "无" 'bad'
    Write-Host "`n结论: 模拟器/手机没连上，先解决这个。" -ForegroundColor Red
    exit 1
}
Line "adb 设备" ($devices -join ', ') 'ok'

$online = & $adb -s $Serial shell echo ok 2>&1
if ("$online".Trim() -ne 'ok') { Line "shell 连接" "不可用" 'bad'; exit 1 }

Write-Host "`n=== 无障碍（三个独立事实，别混为一谈）===" -ForegroundColor Cyan

$master = ("$(& $adb -s $Serial shell settings get secure accessibility_enabled)").Trim()
$listed = ("$(& $adb -s $Serial shell settings get secure enabled_accessibility_services)").Trim()
$bound = (& $adb -s $Serial shell dumpsys accessibility 2>&1 | Select-String 'Bound services')

Line "总开关 accessibility_enabled" $master $(if ($master -eq '1') { 'ok' } else { 'bad' })
$hasListed = $listed -like "*$svc*"
Line "组件在启用列表里" $(if ($hasListed) { '是（设置页会显示"已开启"）' } else { '否' }) $(if ($hasListed) { 'warn' } else { 'bad' })
$isBound = "$bound" -like "*A11y Driver*"
Line "系统真的绑定了服务" $(if ($isBound) { '是' } else { '否' }) $(if ($isBound) { 'ok' } else { 'bad' })

Write-Host "`n=== 引擎 ===" -ForegroundColor Cyan
$pid_ = ("$(& $adb -s $Serial shell pidof $pkg)").Trim()
Line "应用进程" $(if ($pid_) { "pid=$pid_" } else { '未运行' }) $(if ($pid_) { 'ok' } else { 'warn' })

$engineSvc = & $adb -s $Serial shell dumpsys activity services $pkg 2>&1 | Select-String "$pkg/.EngineService"
Line "EngineService" $(if ($engineSvc) { '运行中' } else { '未启动' }) $(if ($engineSvc) { 'ok' } else { 'warn' })

$realToken = ""
if ($pid_) {
    $prefs = & $adb -s $Serial shell "run-as $pkg cat /data/data/$pkg/shared_prefs/engine.xml" 2>&1
    $m = [regex]::Match("$prefs", '<string name="token">([^<]+)</string>')
    if ($m.Success) { $realToken = $m.Groups[1].Value }
}
Line "设备内令牌" $(if ($realToken) { $realToken } else { '读不到' })

if ($Token) {
    $same = $Token -eq $realToken
    Line "与传入令牌一致" $(if ($same) { '是' } else { '不一致（hub 会拒连，表现为反复重连）' }) $(if ($same) { 'ok' } else { 'bad' })
}

Write-Host "`n=== 反连通道 ===" -ForegroundColor Cyan
$probe = $realToken; if (-not $probe) { $probe = $Token }
if ($probe) {
    # 用 curl 而不是 Invoke-WebRequest：PowerShell 5.1 在某些环境会走系统代理导致假失败
    $code = (& curl.exe -s -o NUL -w "%{http_code}" -m 6 -H "X-Auth-Token: $probe" "http://127.0.0.1:$HubPort/v1/ping" 2>&1).Trim()
    switch ($code) {
        '200' { Line "hub /v1/ping" "200 正常" 'ok' }
        '401' { Line "hub /v1/ping" "401 令牌不匹配（hub 启动参数与手机不一致）" 'bad' }
        '503' { Line "hub /v1/ping" "503 设备未连接（引擎没启动 / 反连没建立）" 'bad' }
        default { Line "hub /v1/ping" "无响应 code=$code（hub 没启动？）" 'bad' }
    }
} else {
    Line "hub /v1/ping" "无令牌，跳过" 'warn'
}

Write-Host "`n=== 结论 ===" -ForegroundColor Cyan
if (-not $isBound) {
    if ($master -ne '1') {
        Write-Host "总开关是关的（这台 ROM 重启后会自动关）。" -ForegroundColor Yellow
        Write-Host "恢复: 设置 → 无障碍 → 点「A11y Driver 执行引擎」→ 系统弹窗「允许」"
    } elseif ($hasListed) {
        Write-Host "组件在列表里但没绑定（厂商门禁/状态失效）。" -ForegroundColor Yellow
        Write-Host "恢复: 设置 → 无障碍 → 进入该服务 → 关掉再打开 → 弹窗「允许」"
    } else {
        Write-Host "从没授权过。" -ForegroundColor Yellow
        Write-Host "恢复: 设置 → 无障碍 → 点该应用 → 弹窗「允许」"
    }
} elseif (-not $engineSvc) {
    Write-Host "无障碍正常，只是引擎没启动。" -ForegroundColor Yellow
    Write-Host "启动: adb shell am start-foreground-service -n $pkg/.EngineService"
} else {
    Write-Host "无障碍 + 引擎都正常。" -ForegroundColor Green
}
Write-Host ""
