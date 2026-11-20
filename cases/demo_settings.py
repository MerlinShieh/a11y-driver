"""示例用例：系统设置页导航 + 断言 + 证据。

跑法（先启动 hub 和 orchestrator）：
    python server/hub.py --token <T> &
    python server/orchestrator.py --token <T> --port 7920 &
    python cases/demo_settings.py <token>

用例连的是 orchestrator(7920)：UI 命令被转发到手机引擎，
/v1/shell 直接走 adb，用来做测试隔离。
"""

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "client"))

from engine_client import EngineClient      # noqa: E402
from runner import Case                     # noqa: E402

ANDROID_SETTINGS = "com.android.settings"


def main(token: str, base: str = "http://127.0.0.1:7920") -> int:
    host, _, port = base.replace("http://", "").partition(":")
    c = EngineClient(host, int(port or 80), token)

    case = Case(c, "设置页导航", shots_dir=os.path.join(HERE, "..", "reports", "shots"))

    with case.step("环境就绪（UI + shell 通道）"):
        caps = c.capabilities()
        ch = caps["channels"]
        assert ch["ui"]["available"], "UI 通道不可用"
        assert ch["shell"]["available"], "shell 通道不可用，无法做测试隔离"

    with case.step("测试隔离：force-stop 被测应用"):
        r = c.shell(f"am force-stop {ANDROID_SETTINGS}")
        assert r["ok"], f"force-stop 失败: {r}"

    with case.step("启动被测应用"):
        assert c.app_start(ANDROID_SETTINGS), "拉起失败"

    with case.step("等待首页就绪"):
        c.wait_for({"text": "在设置中搜索"}, timeout=8)
        c.wait_stable(5, 0.3)

    with case.step("断言：首页可点击项 >= 5"):
        n = len(c.find_all({"clickable": True}, limit=100))
        assert n >= 5, f"只找到 {n} 个可点击项"

    with case.step("点击「网络和互联网」"):
        assert c.click({"text": "网络和互联网"}, timeout=3), "点击失败"

    with case.step("断言：进入二级页"):
        c.wait_for({"text": "互联网"}, timeout=8)

    with case.step("断言：首页搜索框已消失"):
        assert c.wait_gone({"text": "在设置中搜索"}, timeout=3), "搜索框仍在"

    with case.step("返回并通过滚动查找「系统」"):
        c.global_action("back")
        time.sleep(1)
        node = c.scroll_into_view({"text": "系统"}, down=True, max_swipes=8, timeout=1.5)
        assert node is not None, "滚动后仍找不到"

    with case.step("采集证据截图"):
        path = c.screenshot(os.path.join(HERE, "..", "reports", "shots", "final.png"))
        assert os.path.getsize(path) > 10000, "截图异常"

    with case.step("收尾：回到桌面"):
        c.global_action("home")

    res = case.finish()
    return 0 if res.status == "pass" else 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python cases/demo_settings.py <token> [http://127.0.0.1:7920]")
        raise SystemExit(1)
    raise SystemExit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:7920"))
