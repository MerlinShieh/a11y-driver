package com.mobileappauto.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.UUID

/**
 * 前台服务：承载两种工作模式
 *   server  —— 手机监听端口，PC 主动连进来（同一局域网、模拟器端口映射）
 *   reverse —— 手机主动连 PC 的 hub（NAT / 模拟器默认网络，推荐）
 */
class EngineService : Service() {

    companion object {
        private const val MODULE = "Engine"
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 1001

        const val PREFS = "engine"
        const val KEY_TOKEN = "token"
        const val KEY_PORT = "port"
        const val KEY_MODE = "mode"
        const val KEY_REVERSE = "reverse"

        const val DEFAULT_PORT = 7912
        const val DEFAULT_REVERSE = "10.0.2.2:7913"
        const val MODE_SERVER = "server"
        const val MODE_REVERSE = "reverse"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var linkState: String = "-"
            private set

        @Volatile
        var linkConnected: Boolean = false
            private set

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun token(ctx: Context): String {
            val p = prefs(ctx)
            val existing = p.getString(KEY_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = randomToken()
            p.edit().putString(KEY_TOKEN, fresh).apply()
            return fresh
        }

        fun randomToken(): String =
            UUID.randomUUID().toString().replace("-", "").substring(0, 16)

        /** 用户手动指定令牌；传空则恢复为随机生成 */
        fun setToken(ctx: Context, value: String) {
            val t = value.trim()
            if (t.isEmpty()) {
                prefs(ctx).edit().putString(KEY_TOKEN, randomToken()).apply()
            } else {
                prefs(ctx).edit().putString(KEY_TOKEN, t).apply()
            }
        }

        fun port(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT)

        fun mode(ctx: Context): String = prefs(ctx).getString(KEY_MODE, MODE_SERVER) ?: MODE_SERVER

        fun reverseAddress(ctx: Context): String =
            prefs(ctx).getString(KEY_REVERSE, DEFAULT_REVERSE) ?: DEFAULT_REVERSE

        fun saveConfig(ctx: Context, mode: String, reverseAddress: String) {
            prefs(ctx).edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_REVERSE, reverseAddress)
                .apply()
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }

    private var server: ApiServer? = null
    private var reverse: ReverseClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        running = true
        lastError = null
        linkConnected = false
        linkState = "-"

        // shell 能力探测可能阻塞（su 探测），放到后台线程
        Thread {
            try {
                com.mobileappauto.engine.shell.ShellManager.init(applicationContext)
            } catch (t: Throwable) {
                Logx.w(MODULE, "shell probe failed", t)
            }
        }.start()

        when (mode(this)) {
            MODE_REVERSE -> startReverse()
            else -> startServer()
        }
    }

    private fun startServer() {
        val port = port(this)
        val token = token(this)
        try {
            val srv = ApiServer(port, token)
            srv.start(5000, false)
            server = srv
            linkState = "监听 0.0.0.0:$port"
            linkConnected = true
            Logx.i(MODULE, "server mode on $port")
        } catch (t: Throwable) {
            running = false
            lastError = t.message ?: t.toString()
            linkState = "启动失败"
            Logx.e(MODULE, "server start failed", t)
        }
    }

    private fun startReverse() {
        val addr = reverseAddress(this)
        val host: String
        val port: Int
        val idx = addr.lastIndexOf(':')
        if (idx <= 0 || idx == addr.length - 1) {
            running = false
            lastError = "反连地址格式应为 host:port，当前是 $addr"
            linkState = "配置错误"
            return
        }
        host = addr.substring(0, idx)
        port = addr.substring(idx + 1).toIntOrNull() ?: run {
            running = false
            lastError = "反连端口不是数字：$addr"
            linkState = "配置错误"
            return
        }

        val client = ReverseClient(host, port, token(this)) { state, connected ->
            linkState = state
            linkConnected = connected
        }
        client.start()
        reverse = client
        Logx.i(MODULE, "reverse mode to $host:$port")
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (t: Throwable) {
            Logx.w(MODULE, "server stop failed", t)
        }
        try {
            reverse?.stop()
        } catch (t: Throwable) {
            Logx.w(MODULE, "reverse stop failed", t)
        }
        server = null
        reverse = null
        running = false
        linkConnected = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "执行引擎", NotificationManager.IMPORTANCE_LOW)
        channel.description = "MobileAppAuto 执行引擎运行状态"
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val text = if (mode(this) == MODE_REVERSE) {
            "反连 ${reverseAddress(this)}"
        } else {
            "监听端口 ${port(this)}"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("执行引擎运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }
}
