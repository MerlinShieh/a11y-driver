# A11y Driver

[![CI](https://github.com/MerlinShieh/a11y-driver/actions/workflows/ci.yml/badge.svg)](https://github.com/MerlinShieh/a11y-driver/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

用 **Android 无障碍服务** 实现手机端自动化执行引擎：不做 AI Agent，而是提供一套类似 uiautomator2 的
**确定性命令接口**，让脚本 / CI 能驱动真机做自动化测试与重复操作——**不依赖 ADB 也能跑**。

## 能力

- 节点树查询、语义点击、滑动、文本输入、截图
- 批量查询 `findall`、等待（出现/消失/界面静止）、滚动查找
- 双通道：手机反连 PC（NAT 友好）或 PC 直连手机
- PC 侧编排器统一入口：UI 命令走无障碍，`shell` / `logcat` 走 ADB
- 用例框架：步骤计时、断言、失败截图、HTML/JSON 报告

## 目录

```
engine/     Android 应用（Kotlin，无障碍执行引擎）
server/     PC 侧：hub（反连汇聚）、orchestrator（统一入口）
client/     Python 客户端与用例框架
cases/      用例
docs/       规范、需求、接口文档、兼容性、踩坑记录
scripts/    构建、状态检查、脱敏检查
```

## 下载

| 版本 | 说明 | 下载 |
|---|---|---|
| v0.3.0 | 最新稳定版（M1~M4 全部能力） | [Releases](https://github.com/MerlinShieh/a11y-driver/releases/latest) |

- 包名 `com.mobileappauto.engine`，minSdk 30（Android 11），targetSdk 34
- 使用独立密钥签名；SHA-256 见 Release 页面
- 首次安装需手动开启无障碍，注意 Android 13+ 的「受限设置」

## 快速开始

```bash
# 1. 编译（需 JDK 17 + Android SDK 34）
powershell -ExecutionPolicy Bypass -File scripts/build.ps1

# 2. 安装
adb install -r engine/app/build/outputs/apk/debug/app-debug.apk

# 3. 手机：开启无障碍 → 启动引擎（界面会显示 IP / 令牌）

# 4. PC 侧起 hub 与编排器
python server/hub.py --token <令牌>
python server/orchestrator.py --token <令牌> --port 7920

# 5. 验证
curl -H "X-Auth-Token: <令牌>" http://127.0.0.1:7920/v1/ping

# 6. 跑示例用例
python cases/demo_settings.py <令牌>
```

## 文档

| 文档 | 内容 |
|---|---|
| [工程规范](docs/工程规范.md) | 9 条项目基线（需求追踪 / 接口契约 / 日志 / 中文 / 兼容 / 单测 / Git / 开源 / 脱敏） |
| [需求与计划](docs/需求与计划.md) | REQ 清单、里程碑、变更记录、技术债 |
| [接口文档](docs/接口文档.md) | 全部端点、选择器、错误码、反连协议 |
| [OpenAPI](docs/openapi.yaml) | 机器可读接口契约 |
| [兼容性矩阵](docs/兼容性矩阵.md) | Android 版本 / ROM / 权限差异 |
| [踩坑记录](docs/踩坑记录-无障碍与引擎启动.md) | 无障碍授权与引擎启动的 5 个坑 |

## 排障

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-state.ps1 -Token <令牌>
```

一条命令输出无障碍三态、引擎状态、令牌一致性、反连通道，并直接给出该做什么。

## 许可证

[MIT](LICENSE) © 2026 MerlinShieh
