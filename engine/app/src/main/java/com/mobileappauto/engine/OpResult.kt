package com.mobileappauto.engine

/**
 * 动作结果：替代原来只有 bool 的返回值。
 *
 * error 取值：
 *   NOT_FOUND      选择器未命中
 *   ACTION_FAILED  找到节点但动作执行失败
 *   CANCELLED      被 /v1/cancel 中断
 *   TIMEOUT        超时
 *   BAD_ARGS       参数不合法
 */
data class OpResult(
    val ok: Boolean,
    val error: String? = null,
    val elapsedMs: Long = 0
) {
    companion object {
        fun ok(elapsedMs: Long = 0) = OpResult(true, null, elapsedMs)
        fun fail(error: String, elapsedMs: Long = 0) = OpResult(false, error, elapsedMs)
    }
}

/** 重试策略：纯逻辑，可单测 */
object RetryPolicy {

    /** 这些错误值得重试（通常是时序问题，等一下就好） */
    val RETRYABLE = setOf("NOT_FOUND", "ACTION_FAILED", "TIMEOUT")

    fun attempts(retries: Int): Int = retries.coerceIn(0, 5) + 1

    fun shouldRetry(attemptIndex: Int, retries: Int, error: String?): Boolean =
        attemptIndex < retries.coerceIn(0, 5) && error in RETRYABLE
}
