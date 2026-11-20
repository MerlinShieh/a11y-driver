#!/usr/bin/env python3
"""推送前脱敏检查（工程规范第 9 条）。

跨平台实现，Windows / Linux / macOS 行为一致，可直接用于 CI。

用法：
    python scripts/desensitize.py                 # 扫当前仓库，有 BLOCK 则退出码 1
    python scripts/desensitize.py --fail-on warn  # WARN 也算失败
    python scripts/desensitize.py --root <目录>
"""

from __future__ import annotations

import argparse
import os
import re
import sys

EXCLUDE_DIRS = {".git", "build", ".gradle", "reports", "__pycache__",
                ".idea", ".vscode", "node_modules", ".venv", "venv"}

INCLUDE_EXT = {".kt", ".java", ".py", ".ps1", ".md", ".yaml", ".yml",
               ".json", ".xml", ".kts", ".properties", ".txt", ".gradle", ".toml"}

# 规则文件自身含有示例串，跳过
EXCLUDE_FILES = {"desensitize.py", "desensitize-check.ps1"}

# 白名单：命中这些片段的不算敏感
ALLOW_PATTERNS = [
    r"127\.0\.0\.1", r"0\.0\.0\.0", r"10\.0\.2\.2",
    r"192\.168\.x\.x", r"<token>", r"<令牌>", r"<设备型号>",
    r"example\.com", r"<user>", r"<工作目录>",
]
ALLOW_RE = re.compile("|".join(ALLOW_PATTERNS))


class Rule:
    def __init__(self, name: str, level: str, pattern: str, hint: str):
        self.name = name
        self.level = level
        self.regex = re.compile(pattern)
        self.hint = hint

    def find(self, line: str):
        for m in self.regex.finditer(line):
            hit = m.group(0)
            if ALLOW_RE.search(hit):
                continue
            # 形如 D:\<工作目录> 的文档占位符不算泄露
            if "<" in hit or ">" in hit:
                continue
            yield hit


RULES = [
    Rule("疑似真实令牌（16 位十六进制）", "BLOCK", r"\b[0-9a-f]{16}\b", "换成 <token> 占位符"),
    Rule("GitHub 令牌", "BLOCK",
         r"(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}",
         "立即吊销并从历史中移除"),
    Rule("私钥", "BLOCK", r"BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY", "严禁入库"),
    Rule("个人目录", "BLOCK", r"[A-Za-z]:\\Users\\[A-Za-z0-9._-]+", "改成相对路径或环境变量"),
    Rule("开发机绝对路径", "BLOCK",
         r"[A-Za-z]:\\(?:data|tools|work|vibeCoding)\\[^\s\"']+", "改成相对路径"),
    Rule("真实邮箱", "WARN",
         r"[A-Za-z0-9._%+-]+@(?!users\.noreply)[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
         "用 noreply 或占位符"),
    Rule("私网 IP", "WARN",
         r"\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b",
         "文档里用 x.x 或 127.0.0.1"),
    Rule("设备型号", "WARN", r"\b(?:23127PN0CC|Pixel \d|SM-[A-Z0-9]{4,})\b", "真机型号不写进仓库"),
]


def iter_files(root: str):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for name in filenames:
            if name in EXCLUDE_FILES:
                continue
            if os.path.splitext(name)[1].lower() not in INCLUDE_EXT:
                continue
            yield os.path.join(dirpath, name)


def scan(root: str):
    findings = []
    counted = 0
    for path in iter_files(root):
        counted += 1
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for lineno, line in enumerate(f, 1):
                    for rule in RULES:
                        for hit in rule.find(line):
                            findings.append({
                                "level": rule.level,
                                "rule": rule.name,
                                "file": os.path.relpath(path, root),
                                "line": lineno,
                                "hit": hit,
                                "hint": rule.hint,
                            })
        except OSError:
            continue
    return counted, findings


def main() -> int:
    ap = argparse.ArgumentParser(description="脱敏检查")
    ap.add_argument("--root", default=os.path.join(os.path.dirname(__file__), ".."))
    ap.add_argument("--fail-on", choices=["block", "warn", "none"], default="block")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    counted, findings = scan(root)

    block = sum(1 for f in findings if f["level"] == "BLOCK")
    warn = sum(1 for f in findings if f["level"] == "WARN")

    if not findings:
        print(f"✅ 脱敏检查通过：扫描 {counted} 个文件，未发现敏感信息。")
        return 0

    if not args.quiet:
        print(f"{'级别':<6}{'规则':<26}{'文件':<38}{'行':>5}  命中")
        print("-" * 100)
        for f in sorted(findings, key=lambda x: (x["level"] != "BLOCK", x["file"], x["line"])):
            print(f"{f['level']:<6}{f['rule']:<26}{f['file']:<38}{f['line']:>5}  {f['hit'][:40]}")
        print("-" * 100)

    print(f"扫描 {counted} 个文件：BLOCK {block} 处，WARN {warn} 处")

    failed = block > 0 or (args.fail_on == "warn" and warn > 0)
    if failed:
        print("❌ 存在需要处理的项，处理完再提交/推送。")
        return 1
    print("⚠️ 仅 WARN，可提交，建议处理。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
