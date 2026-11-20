package com.mobileappauto.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 滚动查找的结果 */
data class ScrollResult(
    val node: AccessibilityNodeInfo?,
    val swipes: Int,
    val elapsedMs: Long,
    val error: String?
)

/**
 * 一条手势轨迹。
 * `startDelayMs` 相同的多条 stroke 会同时按下，可用于双指缩放、多指手势。
 */
data class Stroke(
    val points: List<Pair<Int, Int>>,
    val startDelayMs: Long = 0L,
    val durationMs: Long = 300L
)

/**
 * 动作层：所有对手机的操作都在这里，统一走无障碍服务。
 *
 * 每个可能耗时的操作都接受 `opId`，用于 `/v1/cancel` 中断；
 * 返回值带结构化 error，便于客户端区分"没找到"和"执行失败"。
 */
object Engine {

    private const val SCROLL_BUDGET_MS = 12_000L

    // ---------- 查询 ----------

    fun find(selector: Selector, timeoutMs: Long = 0L, opId: String? = null): AccessibilityNodeInfo? {
        if (selector.isEmpty()) return null
        val idx = selector.index ?: 0
        return findAll(selector, timeoutMs, idx + 1, opId).getOrNull(idx)
    }

    /** 收集所有匹配节点（DFS 顺序，超过 limit 即停） */
    fun findAll(
        selector: Selector,
        timeoutMs: Long = 0L,
        limit: Int = 500,
        opId: String? = null
    ): List<AccessibilityNodeInfo> {
        if (selector.isEmpty()) return emptyList()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (OpRegistry.isCancelled(opId)) return emptyList()

            val svc = AutoAccessibilityService.instance
            if (svc != null) {
                val root = try {
                    svc.appRoot()
                } catch (t: Throwable) {
                    null
                }
                if (root != null) {
                    val acc = ArrayList<AccessibilityNodeInfo>()
                    collect(root, selector, acc, limit)
                    if (acc.isNotEmpty()) return acc
                }
            }
            if (System.currentTimeMillis() >= deadline) return emptyList()
            sleepQuietly(120)
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        selector: Selector,
        acc: MutableList<AccessibilityNodeInfo>,
        limit: Int
    ) {
        if (acc.size >= limit) return
        if (selector.matches(node)) acc.add(node)
        if (acc.size >= limit) return
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            collect(child, selector, acc, limit)
            if (acc.size >= limit) return
        }
    }

    fun summary(node: AccessibilityNodeInfo): JSONObject {
        val r = Rect()
        node.getBoundsInScreen(r)
        return JSONObject()
            .put("class", node.className?.toString() ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("desc", node.contentDescription?.toString() ?: "")
            .put("id", node.viewIdResourceName ?: "")
            .put("pkg", node.packageName?.toString() ?: "")
            .put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
            .put("clickable", node.isClickable)
            .put("enabled", node.isEnabled)
    }

    // ---------- 动作 ----------

    fun click(
        selector: Selector?,
        x: Int?,
        y: Int?,
        timeoutMs: Long = 3000L,
        opId: String? = null
    ): OpResult {
        val started = System.currentTimeMillis()
        if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED")

        if (selector != null && !selector.isEmpty()) {
            val node = find(selector, timeoutMs, opId)
                ?: return OpResult.fail(
                    if (OpRegistry.isCancelled(opId)) "CANCELLED" else "NOT_FOUND",
                    System.currentTimeMillis() - started
                )

            val target = clickableAncestor(node)
            if (target != null) {
                try {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return OpResult.ok(System.currentTimeMillis() - started)
                    }
                } catch (t: Throwable) {
                    // 落到坐标点击
                }
            }
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val ok = tap(r.centerX().toFloat(), r.centerY().toFloat())
                return if (ok) OpResult.ok(System.currentTimeMillis() - started)
                else OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
            }
            return OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
        }

        if (x != null && y != null) {
            val ok = tap(x.toFloat(), y.toFloat())
            return if (ok) OpResult.ok(System.currentTimeMillis() - started)
            else OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
        }
        return OpResult.fail("BAD_ARGS")
    }

    fun longClick(selector: Selector, timeoutMs: Long = 3000L, opId: String? = null): OpResult {
        val started = System.currentTimeMillis()
        if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED")
        val node = find(selector, timeoutMs, opId)
            ?: return OpResult.fail(
                if (OpRegistry.isCancelled(opId)) "CANCELLED" else "NOT_FOUND",
                System.currentTimeMillis() - started
            )
        val target = clickableAncestor(node) ?: node
        val ok = try {
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (t: Throwable) {
            false
        }
        return if (ok) OpResult.ok(System.currentTimeMillis() - started)
        else OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
    }

    fun setText(
        selector: Selector,
        text: String,
        timeoutMs: Long = 3000L,
        opId: String? = null
    ): OpResult {
        val started = System.currentTimeMillis()
        if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED")
        val node = find(selector, timeoutMs, opId)
            ?: return OpResult.fail(
                if (OpRegistry.isCancelled(opId)) "CANCELLED" else "NOT_FOUND",
                System.currentTimeMillis() - started
            )
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
        return if (ok) OpResult.ok(System.currentTimeMillis() - started)
        else OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 5000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(svc, gesture)
    }

    /**
     * 多指 / 多段手势。
     *
     * 模型借鉴 AutoJs6 的 gestures()：每条 stroke 有独立的开始延迟与时长，
     * 相同 startDelay 的 stroke 会同时按下 → 多指手势。
     */
    fun gesture(strokes: List<Stroke>, opId: String? = null): OpResult {
        val started = System.currentTimeMillis()
        if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED")
        if (strokes.isEmpty()) return OpResult.fail("BAD_ARGS")

        val svc = AutoAccessibilityService.instance ?: return OpResult.fail("ACTION_FAILED")
        val builder = GestureDescription.Builder()
        for (s in strokes) {
            if (s.points.size < 1) return OpResult.fail("BAD_ARGS")
            val path = Path().apply {
                moveTo(s.points[0].first.toFloat(), s.points[0].second.toFloat())
                for (i in 1 until s.points.size) {
                    lineTo(s.points[i].first.toFloat(), s.points[i].second.toFloat())
                }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    s.startDelayMs.coerceIn(0L, 10_000L),
                    s.durationMs.coerceIn(1L, 10_000L)
                )
            )
        }
        val ok = dispatch(svc, builder.build())
        return if (ok) OpResult.ok(System.currentTimeMillis() - started)
        else OpResult.fail("ACTION_FAILED", System.currentTimeMillis() - started)
    }

    /**
     * 拖拽：先按住 holdMs，再移动到目标。
     *
     * 用 StrokeDescription 的 continuation 实现——单条 stroke 无法表达"停住再走"，
     * 这一点比 uiautomator2 的 drag() 更贴近真实手势。
     */
    fun drag(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        holdMs: Long = 500L,
        durationMs: Long = 400L
    ): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val hold = holdMs.coerceIn(50L, 5000L)
        val move = durationMs.coerceIn(50L, 5000L)

        // 首选：按住 → continuation 继续移动（最接近真实拖拽）
        val viaContinuation = try {
            // 零长度路径无法驱动手势，按住阶段给 1px 位移
            val holdPath = Path().apply {
                moveTo(fromX.toFloat(), fromY.toFloat())
                lineTo(fromX + 1f, fromY.toFloat())
            }
            val first = GestureDescription.StrokeDescription(holdPath, 0L, hold, true)
            val movePath = Path().apply {
                moveTo(fromX + 1f, fromY.toFloat())
                lineTo(toX.toFloat(), toY.toFloat())
            }
            val second = first.continueStroke(movePath, 0L, move, false)
            dispatch(svc, GestureDescription.Builder().addStroke(second).build())
        } catch (t: Throwable) {
            Logx.w("Gesture", "continuation 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
        if (viaContinuation) return true

        // 兜底：单条 stroke 走完全程（按住语义弱化，但保证能用）
        Logx.i("Gesture", "continuation 不可用，改用单段拖拽")
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, (hold + move).coerceAtMost(9000L))
        return dispatch(svc, GestureDescription.Builder().addStroke(stroke).build())
    }

    /** 双指缩放：两指从 fromRadius 移动到 toRadius */
    fun pinch(
        centerX: Int,
        centerY: Int,
        fromRadius: Int,
        toRadius: Int,
        durationMs: Long = 400L
    ): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        return try {
            val left = Path().apply {
                moveTo((centerX - fromRadius).toFloat(), centerY.toFloat())
                lineTo((centerX - toRadius).toFloat(), centerY.toFloat())
            }
            val right = Path().apply {
                moveTo((centerX + fromRadius).toFloat(), centerY.toFloat())
                lineTo((centerX + toRadius).toFloat(), centerY.toFloat())
            }
            val d = durationMs.coerceIn(50L, 5000L)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(left, 0L, d))
                .addStroke(GestureDescription.StrokeDescription(right, 0L, d))
                .build()
            dispatch(svc, gesture)
        } catch (t: Throwable) {
            false
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(svc, gesture)
    }

    private fun dispatch(svc: AccessibilityService, gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val handler = Handler(Looper.getMainLooper())
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                ok = false
                latch.countDown()
            }
        }
        val dispatched = try {
            svc.dispatchGesture(gesture, callback, handler)
        } catch (t: Throwable) {
            false
        }
        if (!dispatched) return false
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }

    fun global(action: String): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock", "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return try {
            svc.performGlobalAction(code)
        } catch (t: Throwable) {
            false
        }
    }

    fun appStart(pkg: String): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            svc.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun currentPackage(): String = AutoAccessibilityService.currentPackage

    // ---------- 截图 ----------

    fun screenshotPng(): ByteArray? {
        val bmp = screenshotBitmap() ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 整屏截图（ARGB_8888，可直接裁剪）。
     *
     * 系统对 takeScreenshot 有频率限制，连续调用会失败，这里做几次退避重试。
     */
    fun screenshotBitmap(retries: Int = 2): Bitmap? {
        var attempt = 0
        while (attempt <= retries) {
            val bmp = takeScreenshotOnce()
            if (bmp != null) return bmp
            attempt++
            if (attempt <= retries) {
                try {
                    Thread.sleep(350)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun takeScreenshotOnce(): Bitmap? {
        val svc = AutoAccessibilityService.instance ?: return null
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null

        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    bmp = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                } catch (t: Throwable) {
                    bmp = null
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }

        return try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, callback)
            latch.await(6, TimeUnit.SECONDS)
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 节点级截图：整屏截图后按节点 bounds 裁剪。
     *
     * 借鉴 assists-mp 的 node.screenshot()——测试报告里"这个控件长什么样"比整屏更有说服力。
     */
    fun cropBitmap(node: AccessibilityNodeInfo, padding: Int = 0): Bitmap? {
        val full = screenshotBitmap() ?: return null

        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            val left = (r.left - padding).coerceAtLeast(0)
            val top = (r.top - padding).coerceAtLeast(0)
            val right = (r.right + padding).coerceAtMost(full.width)
            val bottom = (r.bottom + padding).coerceAtMost(full.height)
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) {
                full.recycle()
                null
            } else {
                val cropped = Bitmap.createBitmap(full, left, top, w, h)
                if (cropped !== full) full.recycle()
                cropped
            }
        } catch (t: Throwable) {
            full.recycle()
            null
        }
    }

    // ---------- 等待 ----------

    fun waitFor(
        selector: Selector,
        wantPresent: Boolean,
        timeoutMs: Long,
        opId: String? = null
    ): OpResult {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var last = if (wantPresent) "NOT_FOUND" else "STILL_PRESENT"

        while (true) {
            if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED", System.currentTimeMillis() - started)

            val present = find(selector, 0, opId) != null
            if (present == wantPresent) return OpResult.ok(System.currentTimeMillis() - started)
            last = if (wantPresent) "NOT_FOUND" else "STILL_PRESENT"

            if (System.currentTimeMillis() >= deadline) {
                return OpResult.fail("TIMEOUT", System.currentTimeMillis() - started)
            }
            sleepQuietly(150)
        }
    }

    /** 等界面静止：连续两次 dump 一致且保持 quietMs */
    fun waitStable(timeoutMs: Long = 5000L, quietMs: Long = 300L, opId: String? = null): OpResult {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var lastSig = ""
        var lastChange = started

        while (System.currentTimeMillis() < deadline) {
            if (OpRegistry.isCancelled(opId)) return OpResult.fail("CANCELLED", System.currentTimeMillis() - started)

            val dump = NodeDump.dump()
            val rootJson = dump.optJSONObject("root")
            if (rootJson == null) {
                sleepQuietly(100)
                continue
            }
            val sig = rootJson.toString()
            val now = System.currentTimeMillis()
            if (sig != lastSig) {
                lastSig = sig
                lastChange = now
            } else if (now - lastChange >= quietMs) {
                return OpResult.ok(now - started)
            }
            sleepQuietly(120)
        }
        return OpResult.fail("TIMEOUT", System.currentTimeMillis() - started)
    }

    /** 滚动查找：找不到就按方向滑，最多 maxSwipes 次，总预算 12 秒 */
    fun scrollIntoView(
        selector: Selector,
        down: Boolean = true,
        maxSwipes: Int = 8,
        timeoutMs: Long = 2000L,
        opId: String? = null
    ): ScrollResult {
        val started = System.currentTimeMillis()

        find(selector, 0, opId)?.let { return ScrollResult(it, 0, 0, null) }

        val dm = AutoAccessibilityService.instance?.resources?.displayMetrics
        val w = dm?.widthPixels ?: 1080
        val h = dm?.heightPixels ?: 1920
        val cx = w / 2
        val fromY = (h * if (down) 0.75 else 0.25).toInt()
        val toY = (h * if (down) 0.30 else 0.70).toInt()

        var swipes = 0
        while (swipes < maxSwipes) {
            if (OpRegistry.isCancelled(opId)) {
                return ScrollResult(null, swipes, System.currentTimeMillis() - started, "CANCELLED")
            }
            if (System.currentTimeMillis() - started > SCROLL_BUDGET_MS) {
                return ScrollResult(null, swipes, System.currentTimeMillis() - started, "SCROLL_TIMEOUT")
            }
            if (!swipe(cx, fromY, cx, toY, 350L)) {
                return ScrollResult(null, swipes, System.currentTimeMillis() - started, "ACTION_FAILED")
            }
            swipes++
            sleepQuietly(400)

            val hit = find(selector, timeoutMs, opId)
            if (hit != null) {
                return ScrollResult(hit, swipes, System.currentTimeMillis() - started, null)
            }
        }
        return ScrollResult(null, swipes, System.currentTimeMillis() - started, "NOT_FOUND")
    }

    // ---------- 内部 ----------

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = start
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = try {
                cur.parent
            } catch (t: Throwable) {
                null
            }
        }
        return null
    }
}
