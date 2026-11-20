# 脱敏检查（Windows 便捷入口，实际逻辑在 desensitize.py）
$ErrorActionPreference = 'Stop'
$py = if ($env:PYTHON) { $env:PYTHON } else { 'python' }
& $py (Join-Path $PSScriptRoot 'desensitize.py') --root (Join-Path $PSScriptRoot '..') @args
exit $LASTEXITCODE
