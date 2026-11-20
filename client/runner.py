"""极简用例框架：步骤 / 断言 / 证据 / 报告。

设计目标只有三个：
  1. 每一步都有耗时，失败能定位到具体步骤
  2. 失败自动抓截图，报告里能直接看
  3. 输出 JSON（给 CI）+ HTML（给人看）

用法见 cases/demo_settings.py
"""

from __future__ import annotations

import json
import os
import time
import traceback
from dataclasses import dataclass, field, asdict


@dataclass
class Step:
    name: str
    status: str = "pass"          # pass | fail | skip
    elapsed_ms: int = 0
    detail: str = ""
    error: str = ""
    screenshot: str = ""


@dataclass
class CaseResult:
    name: str
    status: str = "pass"
    started_at: str = ""
    elapsed_ms: int = 0
    steps: list = field(default_factory=list)
    meta: dict = field(default_factory=dict)

    @property
    def passed(self) -> int:
        return sum(1 for s in self.steps if s.status == "pass")

    @property
    def failed(self) -> int:
        return sum(1 for s in self.steps if s.status == "fail")


class AssertionFailure(AssertionError):
    pass


class Case:
    def __init__(self, client, name: str, shots_dir: str = "reports/shots"):
        self.c = client
        self.result = CaseResult(name=name, started_at=time.strftime("%Y-%m-%d %H:%M:%S"))
        self.shots_dir = shots_dir
        os.makedirs(shots_dir, exist_ok=True)
        self._t0 = time.time()

    # ---------- 步骤 ----------

    def step(self, name: str):
        """上下文管理器：自动计时；失败时记录错误并抓截图，然后继续执行后续步骤。"""
        return _StepCtx(self, name)

    def check(self, name: str, cond, detail: str = "") -> bool:
        """一条断言，等价于一个 step。"""
        with self.step(name):
            ok = cond() if callable(cond) else bool(cond)
            if not ok:
                raise AssertionFailure(f"断言失败: {name}")
        return True

    def assert_visual(self, name: str, selector: dict | None = None,
                      max_diff: float = 0.02, pixel_tolerance: int = 16,
                      baseline_dir: str | None = None) -> float:
        """视觉断言：与基线比对，首次运行自动创建基线。

        必须在 `with case.step(...)` 内调用，失败会由 step 记录并截图。
        """
        from visual import compare_or_create

        width, height, pixels = self.c.screenshot_raw(selector)
        base = baseline_dir or os.path.join(os.path.dirname(self.shots_dir), "baselines")
        same, ratio, created = compare_or_create(
            base, name, width, height, pixels, pixel_tolerance)

        if created:
            print(f"   (基线已创建: {name} {width}x{height})")
            return 0.0
        if not same and ratio > max_diff:
            raise AssertionFailure(
                f"视觉差异 {ratio:.2%} 超过阈值 {max_diff:.2%}（{name}）")
        return ratio

    # ---------- 内部 ----------

    def _record(self, step: Step) -> None:
        self.result.steps.append(step)
        if step.status == "fail":
            self.result.status = "fail"

    def _shot(self, tag: str) -> str:
        safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in tag)[:40]
        path = os.path.join(self.shots_dir, f"{int(time.time()*1000)}_{safe}.png")
        try:
            self.c.screenshot(path)
            return path
        except Exception:
            return ""

    # ---------- 收尾 ----------

    def finish(self, out_dir: str = "reports", meta: dict | None = None) -> CaseResult:
        self.result.elapsed_ms = int((time.time() - self._t0) * 1000)
        if meta:
            self.result.meta.update(meta)
        os.makedirs(out_dir, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        base = os.path.join(out_dir, f"{self.result.name.replace(' ', '_')}-{stamp}")

        with open(base + ".json", "w", encoding="utf-8") as f:
            json.dump(asdict(self.result), f, ensure_ascii=False, indent=2)
        with open(base + ".html", "w", encoding="utf-8") as f:
            f.write(render_html(self.result, self.shots_dir))
        print(f"\n报告: {base}.html  ({base}.json)")
        return self.result


class _StepCtx:
    def __init__(self, case: Case, name: str):
        self.case = case
        self.step = Step(name=name)
        self.t0 = 0.0

    def __enter__(self):
        self.t0 = time.time()
        return self.step

    def __exit__(self, exc_type, exc, tb):
        self.step.elapsed_ms = int((time.time() - self.t0) * 1000)
        if exc_type is None:
            self.step.status = "pass"
        else:
            self.step.status = "fail"
            self.step.error = f"{exc_type.__name__}: {exc}"
            if not isinstance(exc, AssertionFailure):
                self.step.error += "\n" + "".join(
                    traceback.format_exception(exc_type, exc, tb)[-3:])
            self.step.screenshot = self.case._shot(self.step.name)
        self.case._record(self.step)
        # 失败不中断：继续跑完剩余步骤，报告里能看到全貌
        return True


def render_html(res: CaseResult, shots_dir: str) -> str:
    color = "#1a7f37" if res.status == "pass" else "#c62828"
    rows = []
    for i, s in enumerate(res.steps, 1):
        badge = {"pass": "✅", "fail": "❌", "skip": "⏭"}.get(s.status, "?")
        shot = ""
        if s.screenshot:
            rel = os.path.relpath(s.screenshot, os.path.dirname(shots_dir) or ".")
            shot = f'<br><img src="{rel}" style="max-width:320px;border:1px solid #ddd;margin-top:6px">'
        rows.append(
            f"<tr><td>{i}</td><td>{_esc(s.name)}</td><td>{badge} {s.status}</td>"
            f"<td style='text-align:right'>{s.elapsed_ms}</td>"
            f"<td><pre>{_esc(s.error)}</pre>{shot}</td></tr>"
        )

    return f"""<!doctype html><html lang="zh"><head><meta charset="utf-8">
<title>{_esc(res.name)}</title>
<style>
 body{{font-family:-apple-system,"Segoe UI",Roboto,sans-serif;margin:32px;color:#222}}
 h1{{font-size:20px;margin:0 0 4px}}
 .summary{{color:{color};font-weight:600;margin-bottom:16px}}
 table{{border-collapse:collapse;width:100%;font-size:13px}}
 th,td{{border-bottom:1px solid #eee;padding:8px;vertical-align:top;text-align:left}}
 th{{background:#fafafa;font-weight:600}}
 pre{{margin:0;white-space:pre-wrap;color:#c62828;font-size:12px}}
 .meta{{color:#666;font-size:12px;margin-bottom:16px}}
</style></head><body>
<h1>{_esc(res.name)}</h1>
<div class="summary">{'通过' if res.status == 'pass' else '失败'} ·
 {res.passed}/{len(res.steps)} 步通过 · 总耗时 {res.elapsed_ms} ms</div>
<div class="meta">开始时间 {res.started_at}　{_esc(json.dumps(res.meta, ensure_ascii=False)) if res.meta else ''}</div>
<table><thead><tr><th>#</th><th>步骤</th><th>结果</th><th>耗时(ms)</th><th>详情 / 证据</th></tr></thead>
<tbody>{''.join(rows)}</tbody></table>
</body></html>"""


def _esc(s: str) -> str:
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
