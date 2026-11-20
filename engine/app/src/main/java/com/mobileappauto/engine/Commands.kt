package com.mobileappauto.engine

import android.util.Base64
import org.json.JSONObject

/**
 * 命令分发：HTTP 网关和反连通道共用同一套语义，避免两边行为漂移。
 */
object Commands {

    private val NO_A11Y_REQUIRED = setOf("ping", "capabilities", "shell")

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

                "hierarchy" -> {
                    val tree = NodeDump.dump()
                    JSONObject().put("ok", !tree.has("error")).put("data", tree)
                }

                "find" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val node = Engine.find(selector, p.optLong("timeoutMs", 0L))
                    if (node == null) {
                        JSONObject().put("ok", false).put("error", "NOT_FOUND")
                    } else {
                        JSONObject().put("ok", true).put("node", Engine.summary(node))
                    }
                }

                "findall" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val limit = p.optInt("limit", 200)
                    val nodes = Engine.findAll(selector, p.optLong("timeoutMs", 0L), limit)
                    val arr = org.json.JSONArray()
                    nodes.forEach { arr.put(Engine.summary(it)) }
                    JSONObject()
                        .put("ok", true)
                        .put("count", nodes.size)
                        .put("nodes", arr)
                }

                "wait" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val state = p.optString("state", "appear")
                    val timeout = p.optLong("timeoutMs", 10_000L)
                    val started = System.currentTimeMillis()
                    val wantPresent = state != "gone"
                    val hit = Engine.waitFor(selector, wantPresent, timeout)
                    JSONObject()
                        .put("ok", hit)
                        .put("state", if (hit) state else "timeout")
                        .put("elapsedMs", System.currentTimeMillis() - started)
                }

                "waitstable" -> {
                    val started = System.currentTimeMillis()
                    val hit = Engine.waitStable(
                        p.optLong("timeoutMs", 5000L),
                        p.optLong("quietMs", 300L)
                    )
                    JSONObject()
                        .put("ok", hit)
                        .put("elapsedMs", System.currentTimeMillis() - started)
                }

                "scroll" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    val node = Engine.scrollIntoView(
                        selector,
                        p.optBoolean("down", true),
                        p.optInt("maxSwipes", 8),
                        p.optLong("timeoutMs", 2000L)
                    )
                    if (node == null) {
                        JSONObject().put("ok", false).put("error", "NOT_FOUND")
                    } else {
                        JSONObject().put("ok", true).put("node", Engine.summary(node))
                    }
                }

                "click" -> {
                    val selector = p.optJSONObject("selector")?.let { Selector.fromJson(it) }
                    val x = if (p.has("x")) p.getInt("x") else null
                    val y = if (p.has("y")) p.getInt("y") else null
                    JSONObject().put("ok", Engine.click(selector, x, y, p.optLong("timeoutMs", 3000L)))
                }

                "longclick" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    JSONObject().put("ok", Engine.longClick(selector, p.optLong("timeoutMs", 3000L)))
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

                "input" -> {
                    val selector = Selector.fromJson(p.optJSONObject("selector") ?: JSONObject())
                    JSONObject().put(
                        "ok",
                        Engine.setText(selector, p.optString("text", ""), p.optLong("timeoutMs", 3000L))
                    )
                }

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

                "capabilities" -> JSONObject()
                    .put("ok", true)
                    .put("ui", JSONObject().put("available", AutoAccessibilityService.isReady()))
                    .put("accessibility", AutoAccessibilityService.isReady())
                    .put("shell", com.mobileappauto.engine.shell.ShellManager.capabilities())

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

                else -> JSONObject().put("ok", false).put("error", "UNKNOWN_COMMAND").put("cmd", cmd)
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.toString())
        }
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
