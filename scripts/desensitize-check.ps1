# 推送前脱敏检查（工程规范第 9 条）
# 用法: powershell -ExecutionPolicy Bypass -File scripts/desensitize-check.ps1
# 退出码: 0 = 通过（可能有 WARN），1 = 有 BLOCK 项
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

# 扫描范围：源码与文档；跳过构建产物、依赖、报告截图
$excludeDirs = @('\.git\', '\build\', '\.gradle\', '\reports\', '\__pycache__\', '\.idea\')
$excludeFiles = @('desensitize-check.ps1')   # 规则文件本身会包含示例串
$includeExt = @('.kt', '.java', '.py', '.ps1', '.md', '.yaml', '.yml', '.json', '.xml', '.kts', '.properties', '.txt')

# 白名单：这些不是敏感信息
$allowList = @(
    '127\.0\.0\.1',
    '0\.0\.0\.0',
    '10\.0\.2\.2',          # Android 模拟器里的宿主地址
    '192\.168\.x\.x',
    '<token>', '<令牌>',
    'example\.com'
)

$rules = @(
    @{ Name = '疑似真实令牌（16 位十六进制）'; Level = 'BLOCK'; Regex = '\b[0-9a-f]{16}\b'; Hint = '换成 <token> 占位符' },
    @{ Name = '个人目录 C:\Users\*';           Level = 'BLOCK'; Regex = 'C:\\Users\\[A-Za-z0-9._-]+'; Hint = '改成相对路径或环境变量' },
    @{ Name = '开发机绝对路径';                 Level = 'BLOCK'; Regex = '[A-Za-z]:\\[^\s"'']*vibeCoding'; Hint = '改成相对路径' },
    @{ Name = '真实邮箱';                       Level = 'WARN';  Regex = '[A-Za-z0-9._%+-]+@(?!users\.noreply)[A-Za-z0-9.-]+\.[A-Za-z]{2,}'; Hint = '用 noreply 或占位符' },
    @{ Name = '私网 IP';                        Level = 'WARN';  Regex = '\b(10\.\d{1,3}|192\.168|172\.(1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b'; Hint = '文档里用 x.x 或 127.0.0.1' },
    @{ Name = '设备型号白名单外';               Level = 'WARN';  Regex = '\b(23127PN0CC|Pixel \d|SM-[A-Z0-9]{4,})\b'; Hint = '真机型号不写进仓库' }
)

$files = Get-ChildItem -Path $Root -Recurse -File |
    Where-Object {
        $p = $_.FullName
        ($includeExt -contains $_.Extension) -and
        ($excludeFiles -notcontains $_.Name) -and
        -not ($excludeDirs | Where-Object { $p -like "*$_*" })
    }

$blocks = 0
$warns = 0
$report = @()

foreach ($f in $files) {
    $lines = Get-Content $f.FullName -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $lines) { continue }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if (-not $line) { continue }
        foreach ($r in $rules) {
            foreach ($m in [regex]::Matches($line, $r.Regex)) {
                $hit = $m.Value
                if ($allowList | Where-Object { $hit -match $_ }) { continue }
                $rel = $f.FullName.Substring($Root.Length).TrimStart('\')
                $level = $r.Level
                if ($level -eq 'BLOCK') { $blocks++ } else { $warns++ }
                $report += [pscustomobject]@{
                    Level = $level; Rule = $r.Name; File = $rel; Line = $i + 1; Hit = $hit; Hint = $r.Hint
                }
            }
        }
    }
}

if ($report.Count -eq 0) {
    Write-Host "`n✅ 脱敏检查通过：扫描 $($files.Count) 个文件，未发现敏感信息。`n" -ForegroundColor Green
    exit 0
}

$report | Sort-Object Level, File |
    Format-Table Level, Rule, File, Line, Hit, Hint -AutoSize -Wrap

Write-Host "`n扫描 $($files.Count) 个文件：BLOCK $blocks 处，WARN $warns 处" -ForegroundColor Yellow
if ($blocks -gt 0) {
    Write-Host "❌ 存在 BLOCK 项，处理完再提交/推送。`n" -ForegroundColor Red
    exit 1
}
Write-Host "⚠️ 仅 WARN，可提交，但建议处理。`n" -ForegroundColor Yellow
exit 0
