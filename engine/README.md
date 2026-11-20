# MobileAppAuto Engine

手机端「无障碍执行引擎」：把 uiautomator2 的那套能力（dump / find / click / swipe / input / screenshot）
从 ADB 通道搬到 **AccessibilityService** 上，由手机自己对外提供执行接口。

- 不需要电脑、不需要 `adb`、不需要 root
- 支持两种网络方向：**server 模式**（PC 连手机）和 **reverse 模式**（手机连 PC，适配 NAT / 模拟器）
- 默认端口：HTTP **7912**、反连 **7913**，全部需要 `X-Auth-Token`

---

## 1. 环境（本机已配好）

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `%JAVA_HOME%` | JDK 17+（Temurin 17 已验证） |
| Android SDK | `%ANDROID_HOME%` | platform 34 + build-tools 34.0.0 |
| Gradle | PATH 中的 `gradle`，或 `%MAE_GRADLE%` | 8.9+ |
| adb | `%ANDROID_HOME%\platform-tools\adb` | 随 SDK |

## 2. 编译

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

产物：`engine\app\build\outputs\apk\debug\app-debug.apk`

## 3. 安装与首次配置

```powershell
adb install -r engine/app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App → **① 开启无障碍服务** → 找到「MobileAppAuto 执行引擎」→ 开启
   - 开关置灰 / 提示「受限制的设置」：先去「应用信息 → ⋮ → 允许受限制的设置」
2. 选网络模式（见第 4 节）→ **② 启动执行引擎**
3. 建议加入电池优化白名单

### 无障碍状态怎么看

界面会显示三种状态，而不是简单的开/关：

| 显示 | 含义 | 处理 |
|---|---|---|
| ✅ 已就绪 | 系统已授权 且 本进程已拿到服务实例 | 正常 |
| 🟡 系统已授权，等待连接 | 设置里是开的，但服务还没 bind | 自动重试约 10 秒；仍不行就关开一次开关 |
| ❌ 未授权 | 设置里没开 | 点「① 开启无障碍服务」 |

> **v0.2.0 修复**：旧版本重启后必然显示「未连接」。根因是只处理了 `onServiceConnected`，
> 而系统重启时通常走 `onUnbind → onRebind`，`instance` 被置空后再没人恢复。
> 现在 `onCreate / onServiceConnected / onRebind` 三处都会恢复实例，且 `onUnbind` 不再清空。
>
> ⚠️ **遇到「已授权但连不上 / 点了没反应」，先看 [踩坑记录](../docs/踩坑记录-无障碍与引擎启动.md)，
> 或直接跑 `scripts/check-state.ps1`，不要凭感觉写 settings。**

---

## 4. 两种网络模式

### server 模式（手机监听，PC 连进来）

适合：手机和 PC 在同一局域网。

```
手机 0.0.0.0:7912  ←── PC 脚本
```

手机界面显示 `http://<手机IP>:7912`，PC 直接访问。

**模拟器注意**：Android 模拟器默认是 QEMU 用户态 NAT，**PC 的 `127.0.0.1:7912` 打不到模拟器**，
必须做端口映射，两种办法：

```powershell
# 办法 A：adb 端口转发（立刻可用，但用了 adb）
adb forward tcp:7912 tcp:7912
# 之后 PC 访问 http://127.0.0.1:7912 即可

# 办法 B：模拟器控制台的重定向（同理，仍需连上模拟器）
adb emu redir add tcp:7912:7912
```

> 反过来，**模拟器里的手机可以直连 PC**，地址固定是 `10.0.2.2`——这正是 reverse 模式的基础。

### reverse 模式（手机连 PC，推荐给模拟器 / NAT 环境）

适合：模拟器、跨网、手机拿不到公网 IP。手机主动外连，PC 不需要任何端口映射。

```
手机 ──TCP 7913──▶ server/hub.py ──HTTP 7912──▶ 你的脚本
```

PC 端：

```powershell
python server/hub.py --token <手机上的令牌>
```

手机端：反连地址填 `10.0.2.2:7913`（模拟器）或 `PC的局域网IP:7913`（真机），点「反连模式」。

然后脚本照旧访问 `http://127.0.0.1:7912`，完全无感。

---

## 5. 接口

所有请求带 `X-Auth-Token: <界面上的令牌>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/ping` | 存活检查，返回无障碍是否就绪 |
| GET | `/v1/capabilities` | 能力清单（UI / shell 是否可用） |
| GET | `/v1/hierarchy` | 当前界面节点树（JSON） |
| POST | `/v1/find` | `{"selector":{...},"timeoutMs":3000}` |
| POST | `/v1/findall` | `{"selector":{...},"limit":200}` → `count` + `nodes[]` |
| POST | `/v1/wait` | `{"selector":{...},"state":"appear\|gone","timeoutMs":10000}` |
| POST | `/v1/waitstable` | `{"timeoutMs":5000,"quietMs":300}` → 界面静止 |
| POST | `/v1/scroll` | `{"selector":{...},"down":true,"maxSwipes":8}` 滚动查找 |
| POST | `/v1/click` | `{"selector":{...}}` 或 `{"x":540,"y":1200}` |
| POST | `/v1/longclick` | `{"selector":{...}}` |
| POST | `/v1/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}` |
| POST | `/v1/input` | `{"selector":{...},"text":"hello"}` |
| POST | `/v1/global` | `{"action":"back\|home\|recents\|notifications\|quick_settings\|lock"}` |
| POST | `/v1/app/start` | `{"package":"com.tencent.mm"}` |
| GET | `/v1/app/current` | 当前前台包名 |
| GET | `/v1/screenshot` | PNG 二进制 |
| POST | `/v1/shell` | 设备侧 shell（需 Shizuku/root），无则明确 `SHELL_UNAVAILABLE` |

### 选择器

字段全部可选，彼此是「与」关系：

```json
{"text":"登录"}
{"textContains":"登"}
{"textMatches":"^登\\s*录$"}
{"textStartsWith":"登"}
{"desc":"关闭"}
{"id":"com.foo:id/btn_ok","enabled":true}
{"className":"android.widget.Button","clickable":true,"index":2}
{"editable":true,"scrollable":false}
```

可用字段：`text / textContains / textMatches / textStartsWith`、`desc / descContains / descMatches`、
`id / idMatches / className / classNameMatches / packageName`、
`clickable / enabled / checked / selected / focused / scrollable / editable`、`index`（第 N 个匹配）、`visibleOnly`。

### 反连协议（供自己写 hub 时参考）

一行一个 JSON：

```
手机 → hub : {"type":"hello","token":"...","device":{"model":"...","sdk":34}}
hub → 手机 : {"id":"1","cmd":"click","payload":{"selector":{"text":"设置"}}}
手机 → hub : {"id":"1","ok":true,...}
```

---

## 6. 已知限制

| 限制 | 说明 |
|---|---|
| 需要 Android 11+ | `takeScreenshot()` 从 API 30 才有 |
| `shell` / 文件传输 | 无障碍做不到，需要 Shizuku/root |
| 应用包名 | 靠窗口切换事件推断，切换瞬间可能滞后 |
| 输入法 | `ACTION_SET_TEXT` 对部分自定义输入框无效，后续加自建 IME 兜底 |
| FLAG_SECURE | 银行/支付类页面截图是黑屏，系统安全边界 |
| 明文传输 | 目前是明文 TCP/HTTP，只适合可信局域网；跨公网需加 TLS |
| 后台存活 | 前台服务 + 电池白名单，厂商 ROM 仍可能杀 |
| 反连的断线检测 | hub 靠调用失败判断设备离线，没有主动心跳 |

## 7. 用例与报告

用例写在 PC 侧，连编排器（7920），这样同一条用例既能调 UI 也能做测试隔离。

```python
from engine_client import EngineClient
from runner import Case

c = EngineClient("127.0.0.1", 7920, token)
case = Case(c, "设置页导航")

with case.step("测试隔离：force-stop 被测应用"):
    assert c.shell("am force-stop com.android.settings")["ok"]

with case.step("断言：首页可点击项 >= 5"):
    n = len(c.find_all({"clickable": True}))
    assert n >= 5, f"只找到 {n} 个"

res = case.finish()   # 产出 reports/<用例>-<时间>.json / .html
```

- 每步自动计时
- 失败自动抓截图并写进 HTML
- 失败**不中断**，跑完全部步骤后汇总

跑示例：`python cases/demo_settings.py <token>`

## 8. 代码结构


```
app/src/main/java/com/mobileappauto/engine/
├── AutoAccessibilityService.kt   无障碍服务（onCreate/onServiceConnected/onRebind 三处恢复实例）
├── A11yState.kt                  区分「系统已授权」与「进程已绑定」
├── NodeDump.kt                   节点树 → JSON
├── Selector.kt                   选择器解析与匹配
├── Engine.kt                     动作层（点击/滑动/输入/全局动作/截图）
├── Commands.kt                   命令分发（HTTP 与反连共用）
├── ApiServer.kt                  server 模式：NanoHTTPD 网关
├── ReverseClient.kt              reverse 模式：TCP JSON-lines 客户端
├── EngineService.kt              前台服务，按模式启动 server 或 reverse
└── MainActivity.kt               状态面板 + 模式切换

server/hub.py                     PC 端反连 hub（纯标准库）
client/engine_client.py           Python 客户端
```
