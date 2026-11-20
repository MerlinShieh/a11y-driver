package com.mobileappauto.engine

import org.json.JSONObject

/**
 * 事件出口：无障碍事件 → 反连通道。
 *
 * 只做一个转发动作，具体发给谁由 ReverseClient 决定（没连上就丢弃）。
 * 事件量不大，不做队列，避免积压后一次性冲刷。
 */
object EventSink {

    @Volatile
    private var sender: ((JSONObject) -> Unit)? = null

    fun attach(fn: ((JSONObject) -> Unit)?) {
        sender = fn
    }

    fun emit(event: JSONObject) {
        val fn = sender ?: run {
            Logx.d("Events", "无订阅者，丢弃 ${event.optString("type")}")
            return
        }
        try {
            fn(event)
        } catch (t: Throwable) {
            Logx.w("Events", "事件发送失败：${t.message}")
        }
    }

    private fun base(type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("ts", System.currentTimeMillis())

    fun window(pkg: String?, cls: String?) {
        emit(base("window").put("package", pkg ?: "").put("class", cls ?: ""))
    }

    fun notification(pkg: String?, text: String?) {
        emit(base("notification").put("package", pkg ?: "").put("text", text ?: ""))
    }

    fun toast(pkg: String?, text: String?) {
        emit(base("toast").put("package", pkg ?: "").put("text", text ?: ""))
    }
}
