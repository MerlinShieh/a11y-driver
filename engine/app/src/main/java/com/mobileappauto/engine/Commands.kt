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

                "gesture" -> withOp(p) { opId ->
                    val strokes = parseStrokes(p.optJSONArray("strokes"))
                        ?: return@withOp JSONObject().put("ok", false).put("error", "BAD_ARGS")
                    val r = Engine.gesture(strokes, opId)
                    JSONObject()
                        .put("ok", r.ok)
                        .put("elapsedMs", r.elapsedMs)
                        .put("strokes", strokes.size)
                        .also { if (!r.ok) it.put("error", r.error) }
                }

                "drag" -> {
                    val from = p.optJSONArray("from")
                    val to = p.optJSONArray("to")
                    if (from == null || to == null || from.length() < 2 || to.length() < 2) {
                        JSONObject().put("ok", false).put("error", "BAD_ARGS")
                    } else {
                        val started = System.currentTimeMillis()
                        val ok = Engine.drag(
                            from.getInt(0), from.getInt(1),
                            to.getInt(0), to.getInt(1),
                            p.optLong("holdMs", 500L),
                            p.optLong("durationMs", 400L)
                        )
                        JSONObject().put("ok", ok)
                            .put("elapsedMs", System.currentTimeMillis() - started)
                            .also { if (!ok) it.put("error", "ACTION_FAILED") }
                    }
                }

                "pinch" -> {
                    val center = p.optJSONArray("center")
                    if (center == null || center.length() < 2) {
                        JSONObject().put("ok", false).put("error", "BAD_ARGS")
                    } else {
                        val started = System.currentTimeMillis()
                        val ok = Engine.pinch(
                            center.getInt(0), center.getInt(1),
                            p.optInt("fromRadius", 200),
                            p.optInt("toRadius", 600),
                            p.optLong("durationMs", 400L)
                        )
                        JSONObject().put("ok", ok)
                            .put("elapsedMs", System.currentTimeMillis() - started)
                            .also { if (!ok) it.put("error", "ACTION_FAILED") }
                    }
                }

                "global" -> JSONObject().put("ok", Engine.global(p.optString("action", "")))

                "app.current" -> JSONObject().put("ok", true).put("package", Engine.currentPackage())

                "app.start" -> JSONObject().put("ok", Engine.appStart(p.optString("package", "")))

                "screenshot" -> {
                    val selector = p.optJSONObject("selector")?.let { Selector.fromJson(it) }
                    val padding = p.optInt("padding", 0)
                    val raw = p.optString("format", "png") == "raw"

                    // 先找节点，再截图——这样错误码能区分"没找到"和"截不了"
                    var node: android.view.accessibility.AccessibilityNodeInfo? = null
                    if (selector != null) {
                        if (selector.isEmpty()) {
                            return JSONObject().put("ok", false).put("error", "BAD_ARGS")
                        }
                        node = Engine.find(selector, p.optLong("timeoutMs", 3000L))
                        if (node == null) {
                            return JSONObject().put("ok", false).put("error", "NODE_NOT_FOUND")
                        }
                    }

                    val bmp = if (node != null) {
                        Engine.cropBitmap(node, padding)
                    } else {
                        Engine.screenshotBitmap()
                    }

                    if (bmp == null) {
                        JSONObject().put("ok", false).put("error", "SCREENSHOT_FAILED")
                    } else {
                        try {
                            val width = bmp.width
                            val height = bmp.height
                            if (raw) {
                                // 原始 RGBA：PC 侧无需图像库即可做像素级对比
                                val size = bmp.byteCount
                                val buffer = java.nio.ByteBuffer.allocate(size)
                                bmp.copyPixelsToBuffer(buffer)
                                JSONObject()
                                    .put("ok", true)
                                    .put("format", "raw")
                                    .put("width", width)
                                    .put("height", height)
                                    .put("base64", Base64.encodeToString(buffer.array(), Base64.NO_WRAP))
                            } else {
                                val bos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                                JSONObject()
                                    .put("ok", true)
                                    .put("format", "png")
                                    .put("width", width)
                                    .put("height", height)
                                    .put("base64", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                            }
                        } finally {
                            bmp.recycle()
                        }
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

    /** 解析 strokes：[{"points":[[x,y],...],"startDelayMs":0,"durationMs":300}, ...] */
    private fun parseStrokes(arr: JSONArray?): List<Stroke>? {
        if (arr == null || arr.length() == 0) return null
        val out = ArrayList<Stroke>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: return null
            val pts = obj.optJSONArray("points") ?: return null
            val points = ArrayList<Pair<Int, Int>>()
            for (j in 0 until pts.length()) {
                val pt = pts.optJSONArray(j) ?: return null
                if (pt.length() < 2) return null
                points.add(pt.getInt(0) to pt.getInt(1))
            }
            if (points.isEmpty()) return null
            out.add(
                Stroke(
                    points,
                    obj.optLong("startDelayMs", 0L),
                    obj.optLong("durationMs", 300L)
                )
            )
        }
        return out
    }

    fun decodeScreenshot(result: JSONObject): ByteArray? {        if (!result.optBoolean("ok")) return null
        val b64 = result.optString("base64", "")
        if (b64.isEmpty()) return null
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        }
    }
}
