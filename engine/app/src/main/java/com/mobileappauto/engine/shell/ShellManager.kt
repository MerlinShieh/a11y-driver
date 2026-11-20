package com.mobileappauto.engine.shell

import android.content.Context
import com.mobileappauto.engine.Logx
import org.json.JSONObject

/**
 * 设备侧 shell 能力探测与路由。
 * 探测顺序：Shizuku → root → 无。
 */
object ShellManager {

    private const val MODULE = "Shell"

    @Volatile
    private var current: ShellProvider? = null

    /** 可能在主线程调用，内部做超时保护；建议在后台线程初始化 */
    fun init(context: Context) {
        val previous = current
        val detected = when {
            ShizukuShell.isAvailable(context) -> ShizukuShell
            RootShell.isAvailable(context) -> RootShell
            else -> null
        }
        current = detected
        if (previous?.kind != detected?.kind) {
            Logx.i(MODULE, "shell provider = ${detected?.kind ?: "none"}")
        }
    }

    fun provider(): ShellProvider? = current

    fun kind(): String = current?.kind ?: "none"

    fun available(): Boolean = current != null

    fun exec(command: String, timeoutMs: Long = 30_000): ShellResult {
        val p = current
            ?: return ShellResult(false, -1, "", "SHELL_UNAVAILABLE", "none", 0)
        return p.exec(command, timeoutMs)
    }

    fun capabilities(): JSONObject = JSONObject()
        .put("available", available())
        .put("kind", kind())
        .put("via", "on-device")
        .put(
            "hint",
            if (available()) "" else "未检测到 Shizuku 或 root；可改用 PC 侧 ADB 通道（/v1/shell）"
        )
}
