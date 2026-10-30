package app.embertv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A tiny web page served by the TV on the home network, so the login can be typed on a phone.
 * Nothing leaves the network: the phone's browser posts straight to the TV, and the server only
 * runs while the sign-in screen is showing. The random [token] in the address keeps stray
 * requests out.
 */
class PhoneLoginServer(
    /** Signs in with what the phone sent; returns an error message, or null on success. */
    private val onLogin: suspend (server: String, user: String, pass: String) -> String?,
) {
    val token: String = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
    @Volatile private var socket: ServerSocket? = null

    /** Serves until [stop]. Calls [onReady] with the address to show once listening. */
    suspend fun run(onReady: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val ip = localAddress() ?: return@withContext
        val server = listOf(8765, 8766, 8767, 0).firstNotNullOfOrNull { port ->
            try {
                ServerSocket(port)
            } catch (e: Exception) {
                null
            }
        } ?: return@withContext
        socket = server
        val port = server.localPort
        onReady(if (port == 80) "http://$ip/$token" else "http://$ip:$port/$token")
        try {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                try {
                    client.soTimeout = 15_000
                    handle(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken request from the phone; carry on listening.
                } finally {
                    runCatching { client.close() }
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?').trimEnd('/')
        var length = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0 && header.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = header.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        val out = client.getOutputStream()
        fun respond(status: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        if (path != "/$token") {
            respond("404 Not Found", page("Not found", "<p class=\"msg\">Open the exact address shown on your TV.</p>"))
            return
        }
        if (method != "POST") {
            respond("200 OK", page("Sign in", form("", "", null)))
            return
        }
        val body = ByteArray(length.coerceIn(0, 16_384))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val fields = String(body, 0, read, Charsets.UTF_8).split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        var server = fields["server"].orEmpty().trim()
        var user = fields["username"].orEmpty().trim()
        var pass = fields["password"].orEmpty()
        // A pasted playlist link carries the username and password too.
        credentialsFromLink(server)?.let { (u, p) ->
            if (user.isEmpty()) user = u
            if (pass.isEmpty()) pass = p
        }
        if (server.isEmpty() || user.isEmpty()) {
            respond("200 OK", page("Sign in", form(server, user, "Enter the server address and username.")))
            return
        }
        val error = onLogin(server, user, pass)
        if (error == null) {
            respond(
                "200 OK",
                page("Signed in", "<div class=\"done\">✓</div><h1>You're signed in</h1><p class=\"msg\">Your TV is ready. You can close this page.</p>"),
            )
        } else {
            server = server.substringBefore('?')
            respond("200 OK", page("Sign in", form(server, user, error)))
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8_192) break
        }
        return buf.toString("UTF-8")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun form(server: String, user: String, error: String?): String = """
        <h1>Sign in on your TV</h1>
        <p class="msg">Type your provider's details here and they'll go straight to your TV over your Wi-Fi.</p>
        ${if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""}
        <form method="post" action="/$token" autocomplete="off">
          <label>Server address<input name="server" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://example.com:8080" value="${esc(server)}" required></label>
          <label>Username<input name="username" autocapitalize="off" autocorrect="off" spellcheck="false" value="${esc(user)}"></label>
          <label>Password<input name="password" type="password" autocapitalize="off" autocorrect="off"></label>
          <button type="submit">Sign in on TV</button>
        </form>
        <p class="hint">Tip: you can paste the whole playlist link (…get.php?username=…&amp;password=…) into the server box.</p>
    """.trimIndent()

    private fun page(title: String, content: String): String = """
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Streammania · $title</title>
        <style>
          :root { color-scheme: dark; }
          body { margin: 0; min-height: 100vh; background: radial-gradient(circle at 30% 0%, #3a1a0a, #09090c 60%); color: #f5f5f7;
                 font: 16px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
          main { max-width: 420px; margin: 0 auto; padding: 36px 20px 48px; }
          .brand { font-weight: 700; font-size: 22px; letter-spacing: -0.4px; margin-bottom: 28px; }
          .brand span { color: #ff7a3d; }
          h1 { font-size: 26px; margin: 0 0 8px; letter-spacing: -0.3px; }
          .msg { color: #a1a1aa; margin: 0 0 22px; }
          .err { background: rgba(255,90,80,.14); color: #ff9a90; padding: 12px 14px; border-radius: 12px; margin: 0 0 18px; }
          label { display: block; font-size: 13px; color: #a1a1aa; margin-bottom: 14px; }
          input { display: block; box-sizing: border-box; width: 100%; margin-top: 6px; padding: 14px; font-size: 17px; color: #f5f5f7;
                  background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.12); border-radius: 12px; outline: none; }
          input:focus { border-color: #ff7a3d; }
          button { width: 100%; margin-top: 8px; padding: 15px; font-size: 17px; font-weight: 600; color: #000; background: #fff;
                   border: 0; border-radius: 14px; }
          .hint { color: #71717a; font-size: 13px; margin-top: 18px; }
          .done { width: 64px; height: 64px; border-radius: 32px; background: #ff7a3d; color: #000; font-size: 34px; font-weight: 700;
                  display: flex; align-items: center; justify-content: center; margin-bottom: 18px; }
        </style></head>
        <body><main><div class="brand">stream<span>mania</span></div>$content</main></body></html>
    """.trimIndent()

    companion object {
        /** This device's address on the home network (Wi-Fi or Ethernet). */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .let { addresses -> addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull { !it.isLinkLocalAddress } }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
