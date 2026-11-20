package com.mobileappauto.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启执行引擎。
 * 注意：只负责把引擎拉起来，无障碍能不能用取决于系统（见 A11yState）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            EngineService.start(context)
        } catch (t: Throwable) {
            // Android 15 对开机启动前台服务有限制，失败就等用户手动启动
        }
    }
}
