package com.mobileappauto.engine.shell

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** 有 su 的设备（测试机、工程机） */
object RootShell : ShellProvider {

    override val kind = "root"

    override fun isAvailable(context: Context): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val done = p.waitFor(2, TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            false
        } else {
            val out = p.inputStream.bufferedReader().readText()
            out.contains("uid=0")
        }
    } catch (t: Throwable) {
        false
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val started = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", command).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val t2 = Thread { p.errorStream.bufferedReader().use { err.append(it.readText()) } }
            t1.start(); t2.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                return ShellResult(false, -1, out.toString(), "超时（${timeoutMs}ms）", kind,
                    System.currentTimeMillis() - started)
            }
            t1.join(1000); t2.join(1000)
            ShellResult(
                p.exitValue() == 0, p.exitValue(), out.toString(), err.toString(), kind,
                System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            ShellResult(false, -1, "", t.toString(), kind, System.currentTimeMillis() - started)
        }
    }
}
