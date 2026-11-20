package com.mobileappauto.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 唯一的系统入口：无障碍服务。
 *
 * 关于生命周期（这是「重启后显示未开启」的根因）：
 *  - 服务被系统 bind 时会回调 onServiceConnected
 *  - 但**重启后系统常常是先 unbind 再 rebind**，rebind 走的是 onRebind，
 *    而不是 onServiceConnected。旧代码只处理了前者，于是 instance 一直是 null。
 *  - onUnbind 里把 instance 置空同样有害：服务其实还活着，只是这一次连接断了。
 *
 * 所以这里：onCreate / onServiceConnected / onRebind 三处都恢复 instance，
 * 只在 onDestroy（服务被真正销毁）时清空。
 */
class AutoAccessibilityService : AccessibilityService() {

    companion object {
        private const val MODULE = "A11y"

        @Volatile
        var instance: AutoAccessibilityService? = null
            private set

        @Volatile
        var currentPackage: String = ""
            private set

        @Volatile
        var currentWindowTitle: String = ""
            private set

        fun isReady(): Boolean = instance != null

        private fun bind(service: AutoAccessibilityService, from: String) {
            instance = service
            Logx.i(MODULE, "bound via $from")
        }
    }

    override fun onCreate() {
        super.onCreate()
        bind(this, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 主动刷新一次配置，避免系统缓存了旧的服务能力（canTakeScreenshot 等）
        try {
            serviceInfo = serviceInfo
        } catch (t: Throwable) {
            Logx.w(MODULE, "refresh serviceInfo failed", t)
        }
        bind(this, "onServiceConnected")
    }

    /** 关键补齐：从 unbind 状态重新 bind 时会走这里 */
    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        bind(this, "onRebind")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                e.packageName?.let { currentPackage = it.toString() }
                e.className?.let { currentWindowTitle = it.toString() }
            }
        }
    }

    /**
     * 取「应用窗口」的根节点。
     *
     * 不能直接用 rootInActiveWindow：输入法弹出时它是 IME 窗口，
     * 拿到的整棵树都是键盘按键，所有选择器都会失效。
     * 这里优先在 interactive windows 里挑 TYPE_APPLICATION 的活动窗口。
     */
    fun appRoot(): AccessibilityNodeInfo? {
        try {
            val ws = windows
            if (ws != null && ws.isNotEmpty()) {
                val apps = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val target = apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
                val root = target?.root
                if (root != null) return root
            }
        } catch (t: Throwable) {
            Logx.w(MODULE, "enumerate windows failed", t)
        }
        return try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 返回 true 才会在下次绑定时回调 onRebind
        Logx.i(MODULE, "unbound")
        return true
    }

    override fun onDestroy() {
        Logx.i(MODULE, "destroyed")
        instance = null
        super.onDestroy()
    }
}
