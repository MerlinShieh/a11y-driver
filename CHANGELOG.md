# 变更记录

遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格；版本号遵循语义化。

## [Unreleased]

### 新增
- 工程规范体系：需求追踪、接口契约、脱敏检查、兼容性矩阵
- 统一日志入口 `Logx`（Android）与 `logging_setup`（PC）
- 单元测试基础设施

## [0.2.0] - 2026-11-19

### 新增
- `findall` 批量查询；选择器扩展至 22 个字段（正则、状态谓词、`index`）
- 等待语义：`wait`（出现/消失）、`waitstable`（界面静止）、`scroll`（滚动查找）
- PC 侧编排器：UI 代理 + ADB `shell` / `logcat` 双通道
- 设备侧 shell 抽象：`ShizukuShell` / `RootShell` / `NoShell`
- 用例框架 `runner.py`：步骤计时、断言、失败截图、HTML/JSON 报告
- 令牌支持手动输入，运行时锁定

### 修复
- 输入法窗口导致 `rootInActiveWindow` 取错树，改为按 `TYPE_APPLICATION` 选择窗口
- `hierarchy.package` 改为取根节点包名，不再用事件缓存

## [0.1.0] - 2026-11-19

### 新增
- 无障碍执行引擎骨架：节点树 dump、选择器、点击/滑动/输入/全局动作/截图
- HTTP 服务端模式（7912）与反连模式（7913）
- 三态无障碍诊断（已绑定 / 已授权未绑定 / 总开关关闭）
- 开机自启

### 修复
- 重启后误报"未开启"：补齐 `onRebind`，`onUnbind` 不再清空实例
