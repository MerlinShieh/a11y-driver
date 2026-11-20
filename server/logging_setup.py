"""统一日志配置（工程规范第 3 条）。

PC 侧所有模块通过 `setup()` 拿到 logger，禁止裸 print。
级别可用 `--log-level` 或环境变量 `MAE_LOG_LEVEL` 控制。
"""

from __future__ import annotations

import logging
import os
import sys

_FORMAT = "%(asctime)s %(levelname)-5s %(name)-16s %(message)s"
_DATEFMT = "%H:%M:%S"


def setup(name: str = "mae", level: str | None = None) -> logging.Logger:
    lvl = (level or os.environ.get("MAE_LOG_LEVEL", "INFO")).upper()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(_FORMAT, _DATEFMT))

    root = logging.getLogger()
    for old in list(root.handlers):
        root.removeHandler(old)
    root.addHandler(handler)
    root.setLevel(getattr(logging, lvl, logging.INFO))

    return logging.getLogger(name)


def add_level_arg(parser) -> None:
    parser.add_argument("--log-level", default=None,
                        help="DEBUG / INFO / WARNING / ERROR，默认 INFO")
