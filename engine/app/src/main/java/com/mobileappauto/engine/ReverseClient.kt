package com.mobileappauto.engine

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 反连通道：由手机主动连到 PC 上的 hub，再收发 JSON 行协议。
 *
 * 并发模型：
 *   读线程只负责收包，命令交给固定线程池执行，回包用写锁串行化。
 *   否则一条长任务（wait/scroll）会把整条连接占死，`/v1/cancel` 永远递不进去。
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

    /** 连接代号：重连后旧线程的迟到回包必须丢弃 */
    @Volatile
    private var generation = 0

    private var thread: Thread? = null
    private val workers = Executors.newFixedThreadPool(4)

    /** 事件单独一个线程：无障碍回调在主线程，直接写 Socket 会抛 NetworkOnMainThreadException */
    private val eventWorker = Executors.newSingleThreadExecutor()
    private val writeLock = Any()

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
        workers.shutdownNow()
        eventWorker.shutdownNow()
    }

    private fun loop() {
        while (running) {
            try {
                onState("正在连接 $host:$port", false)
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                generation++

                val gen = generation
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

                // 无障碍事件 → 上行推送
                EventSink.attach { ev -> pushEvent(ev, writer, gen) }
                Logx.i("Events", "事件通道已挂载")

                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    dispatch(line, writer, gen)
                }
            } catch (t: Throwable) {
                if (running) onState("断开：${t.message ?: t.toString()}", false)
            } finally {
                EventSink.attach(null)
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

    /** 读线程只做解析，命令丢给线程池，避免长任务阻塞收包 */
    private fun dispatch(line: String, writer: BufferedWriter, gen: Int) {
        val id: String
        val cmd: String
        val payload: JSONObject
        try {
            val msg = JSONObject(line)

            // 非命令消息：心跳等
            if (msg.has("type")) {
                when (msg.optString("type")) {
                    "ping" -> {
                        val pong = JSONObject()
                            .put("type", "pong")
                            .put("ts", System.currentTimeMillis())
                        synchronized(writeLock) {
                            if (gen == generation && running) {
                                try {
                                    sendLine(writer, pong)
                                } catch (t: Throwable) {
                                    // 连接已断
                                }
                            }
                        }
                    }
                }
                return
            }

            id = msg.optString("id", "")
            cmd = msg.optString("cmd", "")
            payload = msg.optJSONObject("payload") ?: JSONObject()
        } catch (t: Throwable) {
            return
        }

        workers.submit {
            if (gen != generation || !running) return@submit
            val result = try {
                Commands.exec(cmd, payload)
            } catch (t: Throwable) {
                JSONObject().put("ok", false).put("error", t.toString())
            }
            result.put("id", id)
            synchronized(writeLock) {
                if (gen == generation && running) {
                    try {
                        sendLine(writer, result)
                    } catch (t: Throwable) {
                        // 连接已断，交给外层重连
                    }
                }
            }
        }
    }

    private fun sendLine(writer: BufferedWriter, obj: JSONObject) {
        writer.write(obj.toString())
        writer.write("\n")
        writer.flush()
    }

    /** 事件上行：复用同一条连接，用 type=event 与命令响应区分 */
    private fun pushEvent(ev: JSONObject, writer: BufferedWriter, gen: Int) {
        if (gen != generation || !running) return
        val msg = JSONObject().put("type", "event").put("event", ev)

        // 必须在后台线程写，否则主线程网络 I/O 会抛 NetworkOnMainThreadException
        try {
            eventWorker.submit {
                if (gen != generation || !running) return@submit
                synchronized(writeLock) {
                    if (gen == generation && running) {
                        try {
                            sendLine(writer, msg)
                        } catch (t: Throwable) {
                            Logx.w("Events", "发送失败：${t.javaClass.simpleName}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Logx.w("Events", "事件队列已关闭：${t.javaClass.simpleName}")
        }
    }
}
