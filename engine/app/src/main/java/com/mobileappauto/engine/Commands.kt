package com.mobileappauto.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 命令分发：HTTP 网关和反连通道共用同一套语义。
 *
 * 约定：
 *   - 所有可能耗时的命令都接受 `opId`，可通过 `/v1/cancel` 中断
 *   - `click` / `longclick` / `input` 支持 `retries` + `retryIntervalMs`
 *   - 失败一律带结构化 `error`
 */
object Commands {

    private val NO_A11Y_REQUIRED = setOf("ping", "capabilities", "shell", "cancel", "ops")

    fun exec(cmd: String, p: JSONObject): JSONObject {
        if (cmd !in NO_A11Y_REQUIRED && !AutoAccessibilityService.isReady()) {
            return JSONObject().put("ok", false).put("error", "ACCESSIBILITY_NOT_READY")
        }

        return try {
            when (cmd) {
                "ping" -> JSONObject()
                    .put("ok", true)
                    .put("accessibility", AutoAccessibilityService.isReady())
                    .put("package", AutoAccessibilityService.currentPackage)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)

                "capabilities" -> JSONObject()
                    .put("ok", true)
                    .put("ui", JSONObject().put("available", AutoAccessibilityService.isReady()))
                    .put("accessibility", AutoAccessibilityService.isReady())
                    .put("shell", com.mobileappauto.engine.shell.ShellManager.capabilities())

                "hierarchy" -> {
                    val tree = NodeDump.dump()
                    JSONObject().put("ok", !tree.has("error")).put("data", tree)
                }

                "find" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val node = Engine.find(selector, p.optLong("timeoutMs", 0L), opId)
                    if (node == null) {
                        JSONObject().put("ok", false).put(
                            "error",
                            if (OpRegistry.isCancelled(opId)) "CANCELLED" else "NOT_FOUND"
                        )
                    } else {
                        JSONObject().put("ok", true).put("node", Engine.summary(node))
                    }
                }

                "findall" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val limit = p.optInt("limit", 200)
                    val nodes = Engine.findAll(selector, p.optLong("timeoutMs", 0L), limit, opId)
                    val arr = JSONArray()
                    nodes.forEach { arr.put(Engine.summary(it)) }
                    JSONObject().put("ok", true).put("count", nodes.size).put("nodes", arr)
                }

                "wait" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val state = p.optString("state", "appear")
                    val wantPresent = state != "gone"
                    val r = Engine.waitFor(selector, wantPresent, p.optLong("timeoutMs", 10_000L), opId)
                    JSONObject()
                        .put("ok", r.ok)
                        .put("state", if (r.ok) state else (r.error ?: "timeout").lowercase())
                        .put("elapsedMs", r.elapsedMs)
                        .also { if (!r.ok) it.put("error", r.error) }
                }

                "waitstable" -> withOp(p) { opId ->
                    val r = Engine.waitStable(
                        p.optLong("timeoutMs", 5000L),
                        p.optLong("quietMs", 300L),
                        opId
                    )
                    JSONObject()
                        .put("ok", r.ok)
                        .put("elapsedMs", r.elapsedMs)
                        .also { if (!r.ok) it.put("error", r.error) }
                }

                "scroll" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val r = Engine.scrollIntoView(
                        selector,
                        p.optBoolean("down", true),
                        p.optInt("maxSwipes", 8),
                        p.optLong("timeoutMs", 2000L),
                        opId
                    )
                    if (r.node == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("error", r.error ?: "NOT_FOUND")
                            .put("swipes", r.swipes)
                            .put("elapsedMs", r.elapsedMs)
                    } else {
                        JSONObject()
                            .put("ok", true)
                            .put("node", Engine.summary(r.node))
                            .put("swipes", r.swipes)
                            .put("elapsedMs", r.elapsedMs)
                    }
                }

                "click" -> withOp(p) { opId ->
                    val selector = p.optJSONObject("selector")?.let { Selector.fromJson(it) }
                    val x = if (p.has("x")) p.getInt("x") else null
                    val y = if (p.has("y")) p.getInt("y") else null
                    val timeout = p.optLong("timeoutMs", 3000L)
                    retrying(p) { Engine.click(selector, x, y, timeout, opId) }
                }

                "longclick" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val timeout = p.optLong("timeoutMs", 3000L)
                    retrying(p) { Engine.longClick(selector, timeout, opId) }
                }

                "input" -> withOp(p) { opId ->
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val text = p.optString("text", "")
                    val timeout = p.optLong("timeoutMs", 3000L)
                    retrying(p) { Engine.setText(selector, text, timeout, opId) }
                }

                "swipe" -> JSONObject().put(
                    "ok",
                    Engine.swipe(
                        p.optInt("x1", 0),
                        p.optInt("y1", 0),
                        p.optInt("x2", 0),
                        p.optInt("y2", 0),
                        p.optLong("durationMs", 300L)
                    )
                )

                "global" -> JSONObject().put("ok", Engine.global(p.optString("action", "")))

                "app.current" -> JSONObject().put("ok", true).put("package", Engine.currentPackage())

                "app.start" -> JSONObject().put("ok", Engine.appStart(p.optString("package", "")))

                "screenshot" -> {
                    val png = Engine.screenshotPng()
                    if (png == null) {
                        JSONObject().put("ok", false).put("error", "SCREENSHOT_FAILED")
                    } else {
                        JSONObject()
                            .put("ok", true)
                            .put("format", "png")
                            .put("base64", Base64.encodeToString(png, Base64.NO_WRAP))
                    }
                }

                "shell" -> {
                    val cmd = p.optString("cmd", "")
                    if (cmd.isBlank()) {
                        JSONObject().put("ok", false).put("error", "EMPTY_CMD")
                    } else {
                        com.mobileappauto.engine.shell.ShellManager
                            .exec(cmd, p.optLong("timeoutMs", 30_000L))
                            .toJson()
                    }
                }

                "cancel" -> {
                    if (p.optBoolean("all", false)) {
                        JSONObject().put("ok", true).put("cancelled", OpRegistry.cancelAll())
                    } else {
                        val id = p.optString("opId", "")
                        if (id.isBlank()) {
                            JSONObject().put("ok", false).put("error", "BAD_ARGS")
                        } else {
                            val hit = OpRegistry.cancel(id)
                            JSONObject().put("ok", hit).put("cancelled", if (hit) 1 else 0)
                        }
                    }
                }

                "ops" -> {
                    val arr = JSONArray()
                    OpRegistry.running().forEach { arr.put(it) }
                    JSONObject().put("ok", true).put("running", arr)
                }

                else -> JSONObject().put("ok", false).put("error", "UNKNOWN_COMMAND").put("cmd", cmd)
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.toString())
        }
    }

    /** 注册/注销 opId，保证异常时也能清理 */
    private inline fun withOp(p: JSONObject, block: (String?) -> JSONObject): JSONObject {
        val opId = p.optString("opId", "").ifBlank { null }
        OpRegistry.begin(opId)
        return try {
            block(opId)
        } finally {
            OpRegistry.end(opId)
        }
    }

    /** 在重试策略下执行一个动作 */
    private fun retrying(p: JSONObject, block: () -> OpResult): JSONObject {
        val retries = p.optInt("retries", 0)
        val interval = p.optLong("retryIntervalMs", 300L)
        val maxAttempts = RetryPolicy.attempts(retries)

        var attempt = 0
        var last: OpResult = OpResult.fail("UNKNOWN")
        var totalElapsed = 0L

        while (attempt < maxAttempts) {
            last = block()
            totalElapsed += last.elapsedMs
            if (last.ok) {
                return JSONObject()
                    .put("ok", true)
                    .put("attempts", attempt + 1)
                    .put("elapsedMs", totalElapsed)
            }
            if (!RetryPolicy.shouldRetry(attempt, retries, last.error)) break
            attempt++
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(interval.coerceIn(0L, 5000L))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        return JSONObject()
            .put("ok", false)
            .put("error", last.error ?: "UNKNOWN")
            .put("attempts", attempt + 1)
            .put("elapsedMs", totalElapsed)
    }

    fun decodeScreenshot(result: JSONObject): ByteArray? {
        if (!result.optBoolean("ok")) return null
        val b64 = result.optString("base64", "")
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        }
    }
}
