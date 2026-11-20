package com.mobileappauto.engine

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var reverseInput: EditText
    private lateinit var modeHint: TextView
    private lateinit var a11yButton: Button
    private lateinit var shellStatus: TextView
    private lateinit var shellInput: EditText
    private lateinit var shellOutput: TextView
    private lateinit var tokenInput: EditText

    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollCount = 0
    private val pollTask = object : Runnable {
        override fun run() {
            refresh()
            val st = A11yState.state(this@MainActivity)
            if (st != A11yState.State.BOUND && pollCount < 20) {
                pollCount++
                pollHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "MobileAppAuto 执行引擎"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 32, 0, 12)
        }
        root.addView(status)

        address = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, 12)
            setTextIsSelectable(true)
        }
        root.addView(address)

        modeHint = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 16)
            setTextColor(Color.DKGRAY)
        }
        root.addView(modeHint)

        a11yButton = button("① 开启无障碍服务") { openA11ySettings() }
        root.addView(a11yButton)

        root.addView(button("② 启动执行引擎") {
            EngineService.setToken(this, tokenInput.text.toString())
            pollCount = 0
            EngineService.start(this)
            pollHandler.postDelayed(pollTask, 500)
        })

        root.addView(button("停止执行引擎") {
            EngineService.stop(this)
            pollHandler.postDelayed({ refresh() }, 500)
        })

        // ---------- 令牌 ----------
        root.addView(TextView(this).apply {
            text = "访问令牌（引擎运行中不可修改）"
            textSize = 13f
            setPadding(0, 28, 0, 4)
            setTextColor(Color.DKGRAY)
        })

        tokenInput = EditText(this).apply {
            setText(EngineService.token(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            hint = "留空则自动生成"
        }
        root.addView(tokenInput)

        root.addView(button("随机生成新令牌") {
            if (EngineService.running) {
                android.widget.Toast.makeText(
                    this, "请先停止执行引擎再修改令牌", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                tokenInput.setText(EngineService.randomToken())
            }
        })

        root.addView(TextView(this).apply {
            text = "反连地址（手机主动连 PC，NAT/模拟器用这个）"
            textSize = 13f
            setPadding(0, 28, 0, 4)
            setTextColor(Color.DKGRAY)
        })

        reverseInput = EditText(this).apply {
            setText(EngineService.reverseAddress(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            hint = "host:port，模拟器填 10.0.2.2:7913"
        }
        root.addView(reverseInput)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("反连模式") {
            EngineService.saveConfig(this, EngineService.MODE_REVERSE, reverseInput.text.toString().trim())
            restartEngine()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("服务端模式") {
            EngineService.saveConfig(this, EngineService.MODE_SERVER, reverseInput.text.toString().trim())
            restartEngine()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        // ---------- Shell 控制台 ----------
        root.addView(TextView(this).apply {
            text = "Shell 控制台（设备侧）"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 40, 0, 8)
        })

        shellStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        root.addView(shellStatus)

        shellInput = EditText(this).apply {
            hint = "例如：pm list packages -3"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(shellInput)

        val shellRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shellRow.addView(button("执行") { runShellCommand() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shellRow.addView(button("申请 Shizuku") { requestShizuku() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(shellRow)

        shellOutput = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 0)
            setTextIsSelectable(true)
            text = "（输出会显示在这里）"
        }
        root.addView(shellOutput)

        root.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, 32, 0, 0)
            setTextColor(Color.DKGRAY)
            text = """
                server 模式：手机监听端口，PC 直连（需同一局域网，或 adb forward）
                reverse 模式：手机连 PC 上的 hub，PC 端脚本访问 127.0.0.1:7912

                参考命令：
                python server/hub.py --token <令牌> --http-port 7912 --device-port 7913
            """.trimIndent()
        })

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        pollCount = 0
        pollHandler.removeCallbacks(pollTask)
        pollHandler.post(pollTask)
    }

    override fun onPause() {
        pollHandler.removeCallbacks(pollTask)
        super.onPause()
    }

    private fun restartEngine() {
        EngineService.stop(this)
        pollHandler.postDelayed({
            EngineService.start(this)
            pollHandler.postDelayed({ refresh() }, 800)
        }, 400)
    }

    private fun runShellCommand() {
        val cmd = shellInput.text.toString().trim()
        if (cmd.isEmpty()) {
            shellOutput.text = "请输入命令"
            return
        }
        shellOutput.text = "执行中…"
        Thread {
            val r = com.mobileappauto.engine.shell.ShellManager.exec(cmd, 30_000)
            runOnUiThread {
                shellOutput.text = buildString {
                    append("exit=${r.exitCode}  kind=${r.kind}  ${r.elapsedMs}ms\n")
                    if (r.stdout.isNotBlank()) append(r.stdout.trim()).append('\n')
                    if (r.stderr.isNotBlank()) append("[stderr] ").append(r.stderr.trim())
                }
            }
        }.start()
    }

    private fun requestShizuku() {
        shellStatus.text = "正在请求 Shizuku 授权…"
        com.mobileappauto.engine.shell.ShizukuShell.requestPermission(1001) { granted ->
            Thread {
                com.mobileappauto.engine.shell.ShellManager.init(applicationContext)
                runOnUiThread {
                    shellStatus.text = if (granted) {
                        "Shizuku 已授权，shell 通道：" + com.mobileappauto.engine.shell.ShellManager.kind()
                    } else {
                        "Shizuku 未授权或未安装（可改用 PC 侧 ADB 通道）"
                    }
                }
            }.start()
        }
    }

    /** 能直达服务详情页就直达，直达不了再退回无障碍总列表 */
    private fun openA11ySettings() {
        if (A11yState.componentListed(this)) {
            try {
                val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                detail.putExtra(
                    "android.provider.extra.ENABLED_ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                    A11yState.serviceId(this)
                )
                startActivity(detail)
                return
            } catch (t: Throwable) {
                // 退回总列表
            }
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun refresh() {
        val st = A11yState.state(this)
        val token = EngineService.token(this)
        val port = EngineService.port(this)
        val mode = EngineService.mode(this)

        status.text = buildString {
            when (st) {
                A11yState.State.BOUND -> append("✅ 无障碍服务：已就绪\n")
                A11yState.State.NOT_LISTED -> append("❌ 无障碍服务：未授权\n")
                A11yState.State.MASTER_OFF ->
                    append("🟠 无障碍总开关被系统关闭\n     （服务仍在列表里，所以设置页看起来是「已开启」）\n")
                A11yState.State.LISTED_NOT_BOUND ->
                    append("🟡 无障碍：已授权但系统未绑定\n")
            }
            append(if (EngineService.running) "✅ 执行引擎：运行中\n" else "❌ 执行引擎：未启动\n")
            if (EngineService.running) append("🔗 ${EngineService.linkState}\n")
            EngineService.lastError?.let { append("⚠️ $it\n") }

            when (st) {
                A11yState.State.MASTER_OFF ->
                    append("\n这是 MIUI/HyperOS 与部分模拟器镜像的开机行为：重启后系统会关掉无障碍总开关，" +
                        "组件列表却保持不变。点上面的按钮进入服务详情页，把开关重新打开即可。\n")
                A11yState.State.LISTED_NOT_BOUND ->
                    append("\n系统已记录本服务，但还没完成绑定。若 10 秒内没自动恢复，" +
                        "请进详情页关掉再打开一次（部分 ROM 需要过一遍系统确认弹窗）。\n")
                else -> Unit
            }
            append("\n当前前台应用：${AutoAccessibilityService.currentPackage.ifEmpty { "-" }}")
        }

        a11yButton.text = when (st) {
            A11yState.State.BOUND -> "① 无障碍已开启（点此查看）"
            A11yState.State.NOT_LISTED -> "① 去开启无障碍服务"
            else -> "① 去重新打开无障碍开关"
        }

        val ip = localIpv4()
        address.text = if (mode == EngineService.MODE_REVERSE) {
            "模式：反连 → ${EngineService.reverseAddress(this)}\n令牌：$token"
        } else {
            "模式：服务端\n地址：http://$ip:$port\n令牌：$token"
        }

        modeHint.text = if (mode == EngineService.MODE_REVERSE) {
            "反连模式下，PC 端脚本访问 127.0.0.1:7912（由 hub 转给手机）"
        } else {
            "服务端模式下，PC 直接访问手机 IP:$port"
        }

        val shellKind = com.mobileappauto.engine.shell.ShellManager.kind()
        shellStatus.text = if (shellKind == "none") {
            "shell 通道：不可用（未装 Shizuku、无 root）。PC 有 ADB 时用编排器的 /v1/shell"
        } else {
            "shell 通道：$shellKind（设备侧）"
        }

        // 令牌：运行中锁定并展示实际生效值，停止后才允许修改
        val engineRunning = EngineService.running
        tokenInput.isEnabled = !engineRunning
        tokenInput.alpha = if (engineRunning) 0.5f else 1.0f
        if (engineRunning) {
            tokenInput.setText(EngineService.token(this))
        } else if (tokenInput.text.isNullOrBlank()) {
            tokenInput.setText(EngineService.token(this))
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun localIpv4(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress ?: "127.0.0.1"
        } catch (t: Throwable) {
            "127.0.0.1"
        }
    }
}
