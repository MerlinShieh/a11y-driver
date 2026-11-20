package com.mobileappauto.engine.shell

import org.json.JSONObject

/** 一次 shell 执行的结果 */
data class ShellResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val kind: String,
    val elapsedMs: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("exitCode", exitCode)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("kind", kind)
        .put("elapsedMs", elapsedMs)
}
