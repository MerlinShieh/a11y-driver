package com.mobileappauto.engine

import android.util.Log

/**
 * 统一日志入口（工程规范第 3 条）。
 *
 * 约定：
 *   - TAG 固定为 MAE/<模块>
 *   - D 级默认不输出，只在调试时打开
 *   - 不得输出令牌、账号、完整个人信息
 */
object Logx {

    private const val PREFIX = "MAE/"

    @Volatile
    var debugEnabled: Boolean = false

    private fun tag(module: String) = PREFIX + module

    fun d(module: String, msg: String) {
        if (debugEnabled) Log.d(tag(module), msg)
    }

    fun i(module: String, msg: String) {
        Log.i(tag(module), msg)
    }

    fun w(module: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.w(tag(module), msg) else Log.w(tag(module), msg, tr)
    }

    fun e(module: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(tag(module), msg) else Log.e(tag(module), msg, tr)
    }
}
