# 编译 debug APK
# 依赖（都在环境变量里，不写死本机路径）：
#   JAVA_HOME   JDK 17+
#   ANDROID_HOME（或 ANDROID_SDK_ROOT） Android SDK，需 platform 34 + build-tools 34
#   gradle 在 PATH 中，或设置 MAE_GRADLE 指向 gradle.bat
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$Gradle = $env:MAE_GRADLE
)

$ErrorActionPreference = 'Stop'

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    throw "未找到 JDK。请设置 JAVA_HOME 指向 JDK 17+，或用 -JavaHome 指定。"
}
if (-not $Sdk -or -not (Test-Path $Sdk)) {
    throw "未找到 Android SDK。请设置 ANDROID_HOME，或用 -Sdk 指定。"
}
if (-not $Gradle) {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { $Gradle = $cmd.Source }
    else { throw "未找到 gradle。请把它加入 PATH，或设置 MAE_GRADLE 指向 gradle.bat。" }
}

$PROJECT = Join-Path $PSScriptRoot '..\engine'

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:PATH = "$JavaHome\bin;$env:PATH"

Write-Host "==> 编译 $PROJECT" -ForegroundColor Cyan
Write-Host "    JAVA_HOME = $JavaHome"
Write-Host "    SDK       = $Sdk"
Write-Host "    Gradle    = $Gradle"

& $Gradle -p $PROJECT assembleDebug --console=plain

$apk = Join-Path $PROJECT 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    Write-Host "`n==> 产物: $apk ($size MB)" -ForegroundColor Green
} else {
    throw '编译结束但没找到 APK'
}
