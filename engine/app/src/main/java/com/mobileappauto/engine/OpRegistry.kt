package com.mobileappauto.engine

/**
 * 操作注册表：让长任务可被外部取消。
 *
 * 每个长任务在开始时用 `opId` 注册一个取消位，执行过程中轮询 `isCancelled`；
 * 另一个 HTTP 请求调用 `/v1/cancel` 置位即可中断。
 *
 * 纯 JVM 实现，不依赖 Android，可单元测试。
 */
object OpRegistry {

    private val ops = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    /** 注册一个操作；id 为空则忽略（不允许取消） */
    fun begin(id: String?) {
        if (id.isNullOrBlank()) return
        ops[id] = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** 结束并清理 */
    fun end(id: String?) {
        if (id.isNullOrBlank()) return
        ops.remove(id)
    }

    /** 取消指定操作，返回是否命中 */
    fun cancel(id: String): Boolean {
        val flag = ops[id] ?: return false
        flag.set(true)
        return true
    }

    /** 取消全部，返回数量 */
    fun cancelAll(): Int {
        var n = 0
        ops.forEach { (_, flag) ->
            flag.set(true)
            n++
        }
        return n
    }

    fun isCancelled(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return ops[id]?.get() == true
    }

    /** 当前在跑的操作 id 列表（排查用） */
    fun running(): List<String> = ops.keys.toList()

    /** 仅测试用：清空 */
    fun clear() {
        ops.clear()
    }
}
