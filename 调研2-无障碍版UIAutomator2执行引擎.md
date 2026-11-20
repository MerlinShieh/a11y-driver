# 调研（二）：做一个「无障碍版 uiautomator2」执行引擎

> 调研时间：2026-11-19
> 需求澄清：不要 AI Agent，要的是**手机端的执行程序/守护进程**——
> ① 能被编排好的自动化脚本驱动；② 能通过网络接收命令或脚本并执行；
> ③ 把 uiautomator2 的 ADB 通道换成 AccessibilityService。

---

## 一、先说结论

**这个位置基本是空的。**

市面上的开源项目可以归成四类，但**没有一个是"无障碍版 uiautomator2"**：

| 类别 | 代表 | 为什么不是你要的 |
|---|---|---|
| 脚本 IDE / 运行时 | AutoJs6、AutoX、Auto.js | 强在"人在手机上写脚本"，弱在"外部程序远程驱动"；远程调试基本靠 `adb forward` |
| AI Agent | Mika、GoBal、opendroid | 决策层是 LLM，不是可编排的确定性脚本 |
| 点按规则引擎 | GKD | 只做"匹配到就点"，没有查询/等待/断言/流程控制 |
| ADB 自动化框架 | uiautomator2、Appium、droidrun | 正是你要摆脱的那条路 |

**但拼图是齐的**：HTTP 服务骨架、节点树序列化、WebSocket 下发协议、脚本引擎，各有一两个高质量参考实现，拼起来就是一个完整的执行引擎。

---

## 二、四块拼图（按协议形态分类）

### 拼图 1：手机端 HTTP 命令服务器（最小可用骨架）

| 项目 | ★ | 许可证 | 形态 | 借鉴点 |
|---|---|---|---|---|
| [Jasgun9/Reel-Remote](https://github.com/Jasgun9/Reel-Remote) | 个位数 | MIT | Kotlin App 内起 HTTP server（`java.net` + `org.json`，约 300 行），端口 8787，**token 鉴权**，`curl -H "X-Auth-Token: ..." http://<ip>:8787/ping` | **教科书级的"手机当服务器"最小实现**：端口/令牌/错误码（`SCREEN_LOCKED`、`GESTURE_FAILED`）/手势几何自适应，全是你要处理的边界 |
| [jwlilly/Accessibility-Inspector-Service](https://github.com/jwlilly/Accessibility-Inspector-Service) | 14 | GPL-3.0 | 无障碍服务里起 **WebSocket server（硬编码 38301）**，把整棵无障碍节点树以 JSON 推出去 | **节点树 → JSON 的序列化方案**；命令协议极简：`{"message":"capture"}` / `ping` / `version`；作者踩过的坑（服务被杀、屏幕刷新时 dump 崩溃）都写在 README 里 |

> 这两个项目加起来不到 2000 行，但把"手机端暴露接口"这件事的所有坑都趟过了：端口占用、鉴权、屏幕锁定时的手势失败、dump 时机的竞态。

### 拼图 2：WebSocket 长连接 + 服务端下发命令（多机/跨网）

| 项目 | ★ | 许可证 | 形态 | 借鉴点 |
|---|---|---|---|---|
| [pang-qin/YUNKONG](https://github.com/pang-qin/YUNKONG) | 7 | MIT | 手机端 `WebSocketService` 长连服务端，`MyAccessibilitySvc` 执行；服务端 FastAPI + Vue 面板，`/api/v1/commands/send`、`/ws` | **完整的"服务端 → 手机"命令协议**（`start_task` / `stop_task` / `ping` / 状态上报）；README 里明确论证了"为什么不用 ADB"：跨网、风控（ADB 是明显特征）、集中管理。也把"手机做 client"的取舍讲清楚了 |
| [Hamibot](https://github.com/hamibot/hamibot) | 3.1k | 未标注 | 客户端连云端，脚本从市场/云端下发到手机执行 | 「云端下发 JS 脚本 + 本地无障碍执行」的商业化闭环；可参考其脚本包格式与权限模型 |

### 拼图 3：MCP / JSON-RPC 风格的设备控制服务

| 项目 | ★ | 许可证 | 形态 | 借鉴点 |
|---|---|---|---|---|
| [zephyr7030/DroidBridge](https://github.com/zephyr7030/DroidBridge) | 26 | Apache-2.0 | **手机端 MCP server**，`127.0.0.1:8765/mcp` + Bearer token；无障碍 / Shizuku / root 三档能力 | 现成的**工具清单与安全注解**（`readOnlyHint` / `destructiveHint`），等于一份"设备控制 API 设计稿"；分档权限的设计值得抄 |
| [ven-coder/assists](https://github.com/ven-coder/assists) + [assistsx-js](https://github.com/ven-coder/assistsx-js) | 999 / 48 | **GPL-3.0** / MIT | 无障碍封装框架 + **JS 运行时（Web 端写脚本，手机端跑插件）** + Assists MCP | **插件本地安装 / 局域网安装 / 在线加载**三种分发方式，正好是"网络下发脚本"的三种形态；`assistsx-js` 的 API 名单就是一份脚本层设计清单 |
| [SuperMonster003/AutoJs6](https://github.com/SuperMonster003/AutoJs6) | 6.7k | MPL-2.0 | Rhino JS 引擎 + `automator`/`selector`/`floaty`/`http`/`webSocket` 模块 + 可打包成独立 APK | **脚本层 API 的事实标准**（`click()/text()/id()/waitFor()`）；如果要做脚本编排，直接对齐它的命名，用户迁移成本为零 |

### 拼图 4：可商用的无障碍封装基座

| 项目 | ★ | 许可证 | 说明 |
|---|---|---|---|
| [Krosxx/Android-Auto-Api](https://github.com/Krosxx/Android-Auto-Api) | 654 | **Apache-2.0** | Kotlin DSL：`click { text = "登录" }`、`scroll`、`input`，把 `AccessibilityNodeInfo` 的模板代码全包了。**闭源商用首选** |
| [littleRich/AutoInteraction-Library](https://github.com/littleRich/AutoInteraction-Library) | 61 | Apache-2.0 | "仿真人机交互"库：模拟点击指定控件、滚屏，带随机化 |
| [ven-coder/assists](https://github.com/ven-coder/assists) | 999 | GPL-3.0 | 能力最全（浮窗、截图、输入法、图像识别、Web 桥接），但 **GPL 传染，闭源项目只能读不能链** |

---

## 三、核心工作：把 uiautomator2 的 API 映射到无障碍

这是整个项目的技术主干。uiautomator2 之所以能当"移动端执行程序"，是因为它暴露的这套能力：

| uiautomator2 能力 | 底层（ADB 路线） | 无障碍替代方案 | 可行性 |
|---|---|---|---|
| `dump_hierarchy()` → XML | `uiautomator` instrumentation | `getRootInActiveWindow()` 递归遍历 `AccessibilityNodeInfo`，自己序列化成 XML/JSON | ✅ 完全可行，字段甚至更全（多了 `contentDescription`、`hintText`、`inputType`） |
| `click(x, y)` | `input tap` / injection | `dispatchGesture()` 或目标节点 `performAction(ACTION_CLICK)` | ✅ 语义点击更稳，坐标点击做兜底 |
| `swipe` / `drag` / 多指 | `input swipe` / minitouch | `dispatchGesture()` + `GestureDescription.StrokeDescription` | ✅ 支持多指、贝塞尔曲线 |
| `set_text` | `input text`（需转义） | 节点 `ACTION_SET_TEXT`，失败退回自建输入法 / 剪贴板粘贴 | ⚠️ 部分自定义 EditText 会拒绝 `SET_TEXT`，要有输入法兜底 |
| `press("home"/"back"/"recent")` | `input keyevent` | `performGlobalAction(GLOBAL_ACTION_*)` | ✅ |
| `screenshot()` | `screencap` | `takeScreenshot()`（**API 30+**） | ⚠️ Android 10 及以下要 `MediaProjection` |
| `app_start(pkg)` | `am start` | `getLaunchIntentForPackage()` + `startActivity()` | ✅ 但如果 App 没有 launcher entry 就拉不起来 |
| `app_current()` | `dumpsys window` | **拿不到**。只能靠 `TYPE_WINDOW_STATE_CHANGED` 事件缓存当前包名 | ⚠️ 事件没来时不准，需要兜底轮询 |
| `app_list()` | `pm list packages` | `PackageManager.getInstalledApplications()` | ✅ 但看不到被 `QUERY_ALL_PACKAGES` 限制的包 |
| `wait_activity()` | `dumpsys` 轮询 | 监听窗口事件 + 超时 | ⚠️ 语义弱化，建议改成 `wait_for(selector)` |
| `shell(cmd)` | `sh -c` | **做不到** | ❌ 只能接 Shizuku / root |
| `push/pull` 文件 | `adb push/pull` | 只能读写自己沙箱 + SAF | ❌ |
| `toast` | 反射/通知 | 无障碍事件 `TYPE_NOTIFICATION_STATE_CHANGED` 可捕获 | ✅ 部分机型 |
| `open_notification()` | `cmd statusbar` | `GLOBAL_ACTION_NOTIFICATIONS` | ✅ |
| `screen_on/off` | `input keyevent` | `GLOBAL_ACTION_LOCK_SCREEN`（只锁不开）；唤醒需 `PowerManager` + 前台 Activity | ⚠️ |

**结论**：除了 `shell` 和文件传输这两类"系统级"能力，**其余全部可以纯无障碍实现**，而且语义点击（按 selector 找节点）比坐标点击稳定得多——这恰恰是无障碍方案相对 uiautomator2 的优势。

需要的选择器语义（对齐 uiautomator2 的写法即可）：

```
text=登录        textContains=登录      textMatches=^登\s*录$
id=com.foo:id/btn   className=android.widget.Button
description=关闭    packageName=com.foo
xpath=//Button[@text="登录"]
```

底层就是遍历节点树做谓词匹配 + 支持 `parent/child/sibling` 关系选择器（这块 GKD 的选择器设计可以直接抄思路）。

---

## 四、建议的架构：双通道 + 三层

```
┌──────────────────────── 外部 ────────────────────────┐
│  脚本编排器 / CLI / 测试平台 / 另一个 App / MCP 客户端  │
└───────────────┬───────────────────────┬──────────────┘
        HTTP REST（请求-响应）      WebSocket（事件推送/长任务）
                │                       │
┌───────────────▼───────────────────────▼──────────────┐
│                  手机端执行引擎（你的 App）              │
│  ┌────────────────────────────────────────────────┐  │
│  │ 接入层：端口/token/TLS/并发排队/限流              │  │
│  ├────────────────────────────────────────────────┤  │
│  │ 会话层：dump 快照、选择器解析、等待与重试、超时     │  │
│  ├────────────────────────────────────────────────┤  │
│  │ 动作层：click/swipe/input/global/dump/screenshot │  │
│  ├────────────────────────────────────────────────┤  │
│  │ 无障碍服务（唯一系统入口）+ 截图兜底 + 前台服务保活  │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

### 传输选型：手机做 server 还是 client？

| | 手机做 server（监听端口） | 手机做 client（外连服务端） |
|---|---|---|
| 局域网可用 | ✅ 直接连 IP:port | 需服务端 |
| 跨网络 | ❌ 要端口映射/内网穿透 | ✅ 天然支持 |
| 需要知道手机 IP | ✅ 要 | ❌ 不要 |
| 手机端复杂度 | 中（端口冲突、多网卡、休眠断连） | 低（断线重连即可） |
| 延迟 | 最低 | 多一跳 |
| 适合场景 | 本机调试、局域网群控、CI 测试机 | 远程托管、SaaS、多设备面板 |

**建议：两条都做，但先做 server。** 手机做 server 更接近"uiautomator2 体验"（`curl` 就能调），调试期效率高；等要做远程托管时再加 client 模式——YUNKONG 和 A11yInspector 的作者最后都倒向 client 模式，这是有原因的（NAT、休眠、IP 漂移）。

### 协议草案（对齐 uiautomator2 的习惯，好迁移）

```
POST /v1/session            → 建立会话，返回 sessionId（隔离并发）
GET  /v1/hierarchy?session= → 返回节点树（XML 或 JSON）
POST /v1/find               → {selector:{text:"登录"}, timeout:5} → 返回节点引用
POST /v1/click              → {selector:{...}} 或 {x:540,y:1200}
POST /v1/swipe              → {from:[x1,y1], to:[x2,y2], duration:300}
POST /v1/input              → {selector:{...}, text:"hello"}
POST /v1/global             → {action:"back"|"home"|"recents"|"notifications"}
POST /v1/app/start          → {package:"com.tencent.mm"}
GET  /v1/screenshot         → image/png（API<30 时降级 MediaProjection）
WS   /v1/events             → 窗口变化、Toast、通知、任务进度
```

一律带 `X-Auth-Token`；错误码用结构化 JSON（`{"code":"SCREEN_LOCKED"}`），别用 HTTP 200 + 错误文本——Reel-Remote 的做法值得抄。

### 脚本层怎么选

| 方案 | 优点 | 缺点 |
|---|---|---|
| **JSON 步骤 DSL**（自研） | 确定性、可审阅、跨语言、易做版本管理 | 表达能力有限，复杂分支要设计 |
| **嵌入式 JS**（QuickJS / Rhino） | 表达力强，用户上手快，生态可复用 AutoJs6 习惯 | 体积大、要防越权、调试难 |
| **只暴露 RPC，脚本在 PC 侧** | 手机端最轻，逻辑全在可控环境 | 依赖网络，断网即停 |

**推荐：手机端只做 RPC 引擎，脚本编排放在外部（PC/服务端），手机端额外提供一个 JSON DSL 的"回放器"** 处理离线场景。这样手机端代码量可控，也符合你说的"通过编排的自动化脚本进行自动化行为操作"。

---

## 五、必须提前处理掉的工程坑

1. **Android 13+ 受限设置**：侧载 APK 的无障碍开关置灰，要先在「应用信息 → ⋮ → 允许受限设置」放行；这个菜单项只有在你尝试开启被拒后才出现。做一个分步引导页，别指望用户自己摸索。
2. **前台服务与保活**：Android 14+ 对前台服务类型有强约束（`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 或 `CONNECTED_DEVICE`），必须声明并在启动时校验；再加 WakeLock + 电池优化白名单引导，否则息屏 10 分钟连接就断。
3. **明文 HTTP**：Android 9+ 默认禁 cleartext，要么配 `networkSecurityConfig`，要么本机自签 TLS。局域网调试建议做「配对码 + 自签证书」二选一。
4. **监听地址**：只监听 `127.0.0.1` 意味着又回到 `adb forward`；要真脱离 ADB 就得监听 `0.0.0.0` 或 WiFi 网卡地址，并处理多网卡/IP 变化（Android 16 起本地网络访问有收紧趋势，需持续关注）。
5. **截图**：`takeScreenshot()` 在部分 ROM 上被限流（每秒 1~2 帧），高频截图必须走 `MediaProjection`，而后者需要用户每次授权 + 常驻通知。
6. **dump 的性能与竞态**：整棵树序列化 1000+ 节点约 50~200ms，屏幕动画期间 dump 容易拿到半截树（A11yInspector 就在这里崩过）。建议加「稳定帧检测」（连续两次 dump 一致才返回）。
7. **输入法**：`ACTION_SET_TEXT` 对自定义控件失效时，唯一可靠的兜底是自带一个输入法（IME），通过 `InputConnection.commitText()` 注入——这会多一个"切换输入法"的用户步骤，要在产品设计里预留。
8. **风控对抗**：微信/支付宝/银行类 App 会检测无障碍服务列表。纯无障碍方案反而比 ADB 更隐蔽（YUNKONG 明确指出这一点），但仍需随机化点击间隔、避免固定坐标。

---

## 六、给你的落地建议

### 三条路线，按投入排序

| 路线 | 做法 | 工期 | 适合 |
|---|---|---|---|
| **A. 借壳** | fork AutoJs6 或 assists，砍掉 UI，加一个 HTTP/WS 网关暴露 selector/action | 1~2 周 | 想最快验证可行性；但受制于 GPL/MPL 与既有架构包袱 |
| **B. 自研（推荐）** | Kotlin 自写：AccessibilityService + 节点树序列化 + 选择器 + 动作层 + Ktor/NanoHTTPD 网关；基座用 Android-Auto-Api（Apache-2.0） | 3~4 周 | 要长期维护、要商用、要干净的架构 |
| **C. 标准协议** | 直接实现 MCP server（参考 DroidBridge），让 Claude Code / 各种客户端直接驱动 | 2~3 周 | 你的使用者本来就是"写脚本/用 Agent 的人" |

**我的建议：B 打底 + C 做外壳。** 内核是一套干净的「无障碍执行引擎」，对外同时暴露 REST（给脚本/CI/curl）和 MCP（给 AI 工具链）。这样既满足你说的"网络发送命令/脚本"，又顺带接上了 2026 年的工具生态，而内核不被任何一方绑死。

### 可以直接复用的清单

| 要什么 | 抄谁 | 理由 |
|---|---|---|
| 手机端 HTTP server 骨架 + 鉴权 + 错误码 | Reel-Remote | 300 行，坑都趟平了，MIT |
| 节点树 JSON 序列化 + WS 推送 | Accessibility-Inspector-Service | 字段覆盖到 API 36，GPL 只看不链 |
| 服务端下发命令的协议设计 | YUNKONG | 命令/心跳/状态上报三件套齐全，MIT |
| 设备控制 API 的工具清单与安全注解 | DroidBridge | Apache-2.0，直接可用 |
| 选择器语法 | GKD | 关系选择器 + 正则，比 XPath 好用 |
| Kotlin 无障碍封装 | Android-Auto-Api | Apache-2.0，可商用 |
| 脚本 API 命名 | AutoJs6 | `click/text/id/waitFor`，用户零迁移成本 |
| 插件/脚本分发（本地/局域网/在线） | assistsx | 三种分发形态的现成设计 |

---

## 附：一句话定位

> 你要做的，本质是 **"WebDriverAgent for Android，但跑在无障碍上"**。
> iOS 那边 WDA 证明了"手机端常驻 HTTP 服务 + selector 语义 + 动作原语"这套抽象的可行性；
> Android 这边 uiautomator2 证明了 API 形态；
> 唯独"不用 ADB"这一格，目前还是空的。
