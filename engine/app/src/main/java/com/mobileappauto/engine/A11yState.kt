package com.mobileappauto.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍有三种彼此独立的事实，混为一谈就会得出错误结论：
 *
 *  1. 组件在「已启用无障碍服务」列表里  —— 设置页据此显示"已开启"，重启后仍保留
 *  2. 无障碍总开关 accessibility_enabled —— 部分 ROM（MIUI/HyperOS、模拟器镜像）
 *                                          会在开机时把它关掉，而列表不动
 *  3. 本进程真的绑定上了服务            —— 只有这一步才能干活
 *
 * 小米系 ROM 还多一道"要允许…完全控制您的设备吗？"的厂商确认，
 * 只有从设置界面点过这个弹窗，系统才肯真正 bind；直接写 settings 没用。
 */
object A11yState {

    enum class State {
        /** 已绑定，可以干活 */
        BOUND,
        /** 列表里有、总开关也开着，但系统没 bind（厂商门禁 / 状态失效） */
        LISTED_NOT_BOUND,
        /** 列表里有，但总开关被系统关掉了 */
        MASTER_OFF,
        /** 列表里没有，就是没授权 */
        NOT_LISTED
    }

    fun serviceId(ctx: Context): String =
        "${ctx.packageName}/${AutoAccessibilityService::class.java.name}"

    fun isBound(): Boolean = AutoAccessibilityService.instance != null

    fun masterEnabled(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    } catch (t: Throwable) {
        false
    }

    fun componentListed(ctx: Context): Boolean {
        val id = serviceId(ctx)

        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (list != null && list.any { it.id.equals(id, ignoreCase = true) }) return true
        } catch (t: Throwable) {
            // 走兜底
        }

        return try {
            val raw = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            raw.split(':').any { it.trim().equals(id, ignoreCase = true) }
        } catch (t: Throwable) {
            false
        }
    }

    fun state(ctx: Context): State = when {
        isBound() -> State.BOUND
        !componentListed(ctx) -> State.NOT_LISTED
        !masterEnabled(ctx) -> State.MASTER_OFF
        else -> State.LISTED_NOT_BOUND
    }
}
