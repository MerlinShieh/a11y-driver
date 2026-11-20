"""像素级视觉对比（不依赖 Pillow / numpy）。

设备返回原始 RGBA 字节，这里做两件事：
  1. 计算差异像素比例与最大单通道差
  2. 基线管理：首次运行生成基线，之后逐个比对
"""

from __future__ import annotations

import json
import os


def diff_ratio(a: bytes, b: bytes, pixel_tolerance: int = 16) -> tuple[float, int]:
    """返回 (差异像素比例, 最大单通道差)。

    pixel_tolerance：单通道差超过该值才算"变了"，用来吸收渲染抖动。
    """
    if len(a) != len(b) or len(a) % 4 != 0:
        return 1.0, 255

    changed = 0
    max_delta = 0
    total = len(a) // 4

    for i in range(0, len(a), 4):
        d = abs(a[i] - b[i])
        d1 = abs(a[i + 1] - b[i + 1])
        if d1 > d:
            d = d1
        d2 = abs(a[i + 2] - b[i + 2])
        if d2 > d:
            d = d2
        if d > max_delta:
            max_delta = d
        if d > pixel_tolerance:
            changed += 1

    return changed / total, max_delta


def baseline_paths(base_dir: str, name: str) -> tuple[str, str]:
    safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in name)
    return os.path.join(base_dir, safe + ".rgba"), os.path.join(base_dir, safe + ".json")


def load_baseline(base_dir: str, name: str) -> tuple[int, int, bytes] | None:
    rgba_path, meta_path = baseline_paths(base_dir, name)
    if not (os.path.exists(rgba_path) and os.path.exists(meta_path)):
        return None
    with open(meta_path, encoding="utf-8") as f:
        meta = json.load(f)
    with open(rgba_path, "rb") as f:
        pixels = f.read()
    return meta["width"], meta["height"], pixels


def save_baseline(base_dir: str, name: str, width: int, height: int, pixels: bytes) -> None:
    os.makedirs(base_dir, exist_ok=True)
    rgba_path, meta_path = baseline_paths(base_dir, name)
    with open(rgba_path, "wb") as f:
        f.write(pixels)
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump({"width": width, "height": height, "bytes": len(pixels)}, f, ensure_ascii=False)


def compare_or_create(base_dir: str, name: str, width: int, height: int, pixels: bytes,
                      pixel_tolerance: int = 16) -> tuple[bool, float, bool]:
    """返回 (是否一致, 差异比例, 是否新建了基线)。

    首次运行没有基线 → 生成基线并返回 (True, 0.0, True)。
    """
    baseline = load_baseline(base_dir, name)
    if baseline is None:
        save_baseline(base_dir, name, width, height, pixels)
        return True, 0.0, True

    bw, bh, bpixels = baseline
    if (bw, bh) != (width, height):
        return False, 1.0, False

    ratio, _ = diff_ratio(bpixels, pixels, pixel_tolerance)
    return ratio == 0.0, ratio, False
