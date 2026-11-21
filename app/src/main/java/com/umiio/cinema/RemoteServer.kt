package com.umiio.cinema

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class RemoteServer(
    private val activity: MainActivity,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        return try {
            when {
                uri == "/" || uri == "/index.html" -> {
                    val html = activity.assets.open("remote.html").bufferedReader().use { it.readText() }
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
                }

                uri == "/api/status" -> {
                    val statusJson = activity.getStatusJson()
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", statusJson)
                }

                uri == "/api/play" -> {
                    val params = parseParams(session)
                    val url = params["url"]?.trim().orEmpty()
                    val mode = params["mode"]?.trim() ?: "auto"
                    if (url.isNotEmpty()) {
                        activity.runOnUiThread { activity.openMedia(url, mode) }
                    }
                    jsonOk()
                }

                uri == "/api/control" -> {
                    val params = parseParams(session)
                    val action = params["action"]?.trim().orEmpty()
                    val value = params["value"]?.trim().orEmpty()
                    activity.runOnUiThread { activity.handleControl(action, value) }
                    jsonOk()
                }

                uri == "/api/mouse" -> {
                    val params = parseParams(session)
                    val action = params["action"]?.trim().orEmpty()
                    val dx = params["dx"]?.toFloatOrNull() ?: 0f
                    val dy = params["dy"]?.toFloatOrNull() ?: 0f
                    val text = params["text"] ?: ""
                    activity.runOnUiThread { activity.handleMouse(action, dx, dy, text) }
                    jsonOk()
                }

                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            val err = JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err)
        }
    }

    private fun parseParams(session: IHTTPSession): Map<String, String> {
        if (session.method == Method.POST || session.method == Method.PUT) {
            try {
                session.parseBody(HashMap())
            } catch (_: Exception) {
            }
        }
        return session.parms ?: emptyMap()
    }

    private fun jsonOk(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
    }
}
