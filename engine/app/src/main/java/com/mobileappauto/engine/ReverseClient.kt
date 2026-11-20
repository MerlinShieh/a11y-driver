package com.mobileappauto.engine

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 反连通道：由手机主动连到 PC 上的 hub，再收发 JSON 行协议。
 *
 * 为什么需要它：模拟器 / 很多真实网络环境是 NAT，PC 无法直连手机端口，
 * 但手机可以连出去（模拟器里 PC 的地址固定是 10.0.2.2）。
 *
 * 协议（一行一个 JSON）：
 *   设备 → hub : {"type":"hello","token":"...","device":{"model":"...","sdk":34}}
 *   hub → 设备 : {"id":"1","cmd":"click","payload":{"selector":{"text":"设置"}}}
 *   设备 → hub : {"id":"1","ok":true,...}
 */
class ReverseClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val onState: (String, Boolean) -> Unit
) {

    @Volatile
    private var running = true

    @Volatile
    private var socket: Socket? = null

    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "reverse-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (t: Throwable) {
            // ignore
        }
        socket = null
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                onState("正在连接 $host:$port", false)
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s

                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))

                sendLine(
                    writer,
                    JSONObject()
                        .put("type", "hello")
                        .put("token", token)
                        .put("device", JSONObject()
                            .put("model", android.os.Build.MODEL)
                            .put("brand", android.os.Build.BRAND)
                            .put("sdk", android.os.Build.VERSION.SDK_INT))
                )

                onState("已连接 $host:$port", true)

                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleLine(line, writer)
                }
            } catch (t: Throwable) {
                if (running) onState("断开：${t.message ?: t.toString()}", false)
            } finally {
                try {
                    socket?.close()
                } catch (t: Throwable) {
                    // ignore
                }
                socket = null
            }

            if (!running) break
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                break
            }
        }
        onState("已停止", false)
    }

    private fun handleLine(line: String, writer: BufferedWriter) {
        val id: String
        val result: JSONObject
        try {
            val msg = JSONObject(line)
            id = msg.optString("id", "")
            val cmd = msg.optString("cmd", "")
            val payload = msg.optJSONObject("payload") ?: JSONObject()
            result = Commands.exec(cmd, payload)
        } catch (t: Throwable) {
            try {
                sendLine(writer, JSONObject().put("ok", false).put("error", "BAD_REQUEST: ${t.message}"))
            } catch (ignored: Throwable) {
                // connection is gone
            }
            return
        }

        result.put("id", id)
        try {
            sendLine(writer, result)
        } catch (t: Throwable) {
            // 连接已断，交给外层重连
        }
    }

    private fun sendLine(writer: BufferedWriter, obj: JSONObject) {
        writer.write(obj.toString())
        writer.write("\n")
        writer.flush()
    }
}
