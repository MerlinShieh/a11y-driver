package com.mobileappauto.engine

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * 手机端的 HTTP 网关（server 模式）。端口默认 7912。
 * 所有请求都要带 X-Auth-Token。
 * 命令语义统一走 Commands，和反连通道保持一致。
 */
class ApiServer(port: Int, private val token: String) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method?.name ?: "GET"

        if (session.headers["x-auth-token"] != token) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "UNAUTHORIZED"))
        }

        return try {
            route(session, uri, method)
        } catch (t: Throwable) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", t.toString()))
        }
    }

    private fun route(session: IHTTPSession, uri: String, method: String): Response {
        if (uri == "/v1/screenshot") {
            val result = Commands.exec("screenshot", JSONObject())
            val png = Commands.decodeScreenshot(result)
                ?: return json(Response.Status.INTERNAL_ERROR, result)
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(png),
                png.size.toLong()
            )
        }

        val cmd = when (uri) {
            "/v1/ping" -> "ping"
            "/v1/hierarchy" -> "hierarchy"
            "/v1/find" -> "find"
            "/v1/findall" -> "findall"
            "/v1/wait" -> "wait"
            "/v1/waitstable" -> "waitstable"
            "/v1/scroll" -> "scroll"
            "/v1/click" -> "click"
            "/v1/longclick" -> "longclick"
            "/v1/swipe" -> "swipe"
            "/v1/input" -> "input"
            "/v1/global" -> "global"
            "/v1/app/current" -> "app.current"
            "/v1/app/start" -> "app.start"
            "/v1/capabilities" -> "capabilities"
            "/v1/shell" -> "shell"
            "/v1/cancel" -> "cancel"
            "/v1/ops" -> "ops"
            else -> null
        }

        if (cmd == null) {
            return json(
                Response.Status.NOT_FOUND,
                JSONObject().put("ok", false).put("error", "NO_ROUTE").put("uri", uri).put("method", method)
            )
        }

        val payload = if (method == "POST") body(session) else JSONObject()
        val result = Commands.exec(cmd, payload)
        val status = if (result.optBoolean("ok")) Response.Status.OK else Response.Status.OK
        return json(status, result)
    }

    private fun body(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            val raw = map["postData"]
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
}
