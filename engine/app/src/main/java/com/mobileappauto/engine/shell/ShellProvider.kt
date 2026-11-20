package com.mobileappauto.engine.shell

import android.content.Context

/**
 * 设备侧 shell 通道的抽象。
 *
 * 三种实现：
 *   ShizukuShell —— 用户装了 Shizuku 并授权，等价于 uid=2000(shell)
 *   RootShell    —— 设备有 su
 *   NoShell      —— 都没有，明确返回不可用（PC 侧 ADB 通道仍然可用）
 *
 * 注意：这类能力只服务于"设备独立运行"或"手动在引擎里敲命令"的场景。
 * 如果 PC 已经连着 ADB，直接用 server/orchestrator.py 的 /v1/shell 更简单。
 */
interface ShellProvider {
    val kind: String
    fun isAvailable(context: Context): Boolean
    fun exec(command: String, timeoutMs: Long): ShellResult
}
