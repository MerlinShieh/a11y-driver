"""示例：用编排脚本驱动手机（无障碍执行引擎）。

运行前：
  1. 手机装好 app-debug.apk，开启无障碍服务，启动执行引擎
  2. 把手机界面上的 IP / 端口 / 令牌填进来
  3. python client/example.py
"""

import sys
import time

from engine_client import EngineClient, EngineError

HOST = "127.0.0.1"
PORT = 7912
TOKEN = "在这里填手机界面上的令牌"


def demo(c: EngineClient) -> None:
    print("1. 连通性检查")
    c.wait_ready(timeout=10)
    print("   ", c.ping())

    print("2. 回到桌面，打开系统设置")
    c.global_action("home")
    time.sleep(0.5)
    if not c.app_start("com.android.settings"):
        raise EngineError("无法拉起 com.android.settings（不同 ROM 包名可能不同）")
    time.sleep(1.5)

    print("3. 抓当前界面节点树")
    tree = c.hierarchy()
    root = tree.get("data", {}).get("root", {})
    print(f"    nodeCount={tree.get('data', {}).get('nodeCount')} package={tree.get('data', {}).get('package')}")

    print("4. 找第一个可点击的文本节点并点击")
    target = _first_clickable_with_text(root)
    if target:
        print(f"    点击: {target.get('text')!r} @ {target.get('bounds')}")
        if not c.click({"text": target["text"]}, timeout=3):
            print("    文本点击失败，改用坐标点击")
            bx = target["bounds"]
            c.click(x=(bx[0] + bx[2]) // 2, y=(bx[1] + bx[3]) // 2)
        time.sleep(1.2)

    print("5. 截图存档")
    path = c.screenshot("screen.png")
    print(f"    saved -> {path}")

    print("6. 返回两次")
    c.global_action("back")
    time.sleep(0.4)
    c.global_action("back")


def _first_clickable_with_text(node: dict) -> dict | None:
    if node.get("clickable") and node.get("text"):
        return node
    for child in node.get("children", []) or []:
        hit = _first_clickable_with_text(child)
        if hit:
            return hit
    return None


if __name__ == "__main__":
    client = EngineClient(HOST, PORT, TOKEN)
    try:
        demo(client)
        print("\n完成")
    except EngineError as e:
        print(f"\n失败: {e}")
        sys.exit(1)
