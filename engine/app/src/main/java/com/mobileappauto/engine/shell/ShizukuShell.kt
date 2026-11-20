package com.mobileappauto.engine.shell

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * 通过 Shizuku 以 shell 身份执行命令。
 *
 * 前提：用户装有 Shizuku 并已激活（Android 11+ 可用"无线调试"在手机上完成，无需电脑），
 * 且本应用已获得 Shizuku 授权。
 *
 * 说明：13.x 的 SDK 把 Shizuku.newProcess 收成了 private，这里用反射调用；
 * 拿不到就明确返回 SHIZUKU_PROCESS_UNSUPPORTED，不做静默降级。
 */
object ShizukuShell : ShellProvider {

    override val kind = "shizuku"

    private fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int, onResult: (Boolean) -> Unit) {
        if (!binderAlive()) {
            onResult(false)
            return
        }
        try {
            Shizuku.addRequestPermissionResultListener { code, grantResult ->
                if (code == requestCode) onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            onResult(false)
        }
    }

    override fun isAvailable(context: Context): Boolean = hasPermission()

    private fun newProcess(command: Array<String>): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        m.invoke(null, command, null, null) as? Process
    } catch (t: Throwable) {
        null
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val started = System.currentTimeMillis()
        if (!hasPermission()) {
            return ShellResult(false, -1, "", "SHIZUKU_PERMISSION_DENIED", kind, 0)
        }
        val process = newProcess(arrayOf("sh", "-c", command))
            ?: return ShellResult(false, -1, "", "SHIZUKU_PROCESS_UNSUPPORTED", kind, 0)

        return try {
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val t2 = Thread { process.errorStream.bufferedReader().use { err.append(it.readText()) } }
            t1.start()
            t2.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return ShellResult(
                    false, -1, out.toString(), "超时（${timeoutMs}ms）", kind,
                    System.currentTimeMillis() - started
                )
            }
            t1.join(1000)
            t2.join(1000)
            ShellResult(
                process.exitValue() == 0, process.exitValue(), out.toString(), err.toString(), kind,
                System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            ShellResult(false, -1, "", t.toString(), kind, System.currentTimeMillis() - started)
        }
    }
}
