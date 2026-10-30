package app.embertv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/** The network name Streammania TVs announce themselves under on the home Wi-Fi. */
const val TV_SERVICE_TYPE = "_streammania._tcp."

/**
 * Runs on the TV while Streammania is open: lets paired phones start things playing and press
 * remote buttons. Phones find it on the home network (NSD); nothing goes over the internet.
 * Every request apart from saying hello and pairing needs the token the phone got when pairing.
 */
class TvLinkServer(
    private val name: String,
    private val handler: Handler,
) {
    interface Handler {
        suspend fun hello(): JSONObject
        suspend fun pairRequested(phone: String)
        /** Returns a token if [code] is the one on screen, else null. */
        suspend fun pairConfirmed(code: String, extras: JSONObject): String?
        fun authorised(token: String): Boolean
        suspend fun play(spec: JSONObject): String?
        suspend fun key(key: String)
    }

    @Volatile private var socket: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    /** Serves until [stop]. [onReady] gets "ip:port" once listening. */
    suspend fun run(context: Context, onReady: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val server = listOf(8771, 8772, 8773, 0).firstNotNullOfOrNull { port ->
            try {
                ServerSocket(port)
            } catch (e: Exception) {
                null
            }
        } ?: return@withContext
        socket = server
        announce(context, server.localPort)
        onReady("${PhoneLoginServer.localAddress() ?: "?"}:${server.localPort}")
        try {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                try {
                    client.soTimeout = 10_000
                    handle(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken request; keep listening.
                } finally {
                    runCatching { client.close() }
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private fun announce(context: Context, listenPort: Int) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = TV_SERVICE_TYPE
            port = listenPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        nsd = manager
        registration = listener
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        val r = registration
        if (r != null) runCatching { nsd?.unregisterService(r) }
        registration = null
    }

    private suspend fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val path = parts[1].substringBefore('?')
        var length = 0
        var token = ""
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val k = header.substring(0, colon).trim().lowercase()
            val v = header.substring(colon + 1).trim()
            if (k == "content-length") length = v.toIntOrNull() ?: 0
            if (k == "x-streammania-token") token = v
        }
        val body = ByteArray(length.coerceIn(0, 2_000_000))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val json = try {
            if (read == 0) JSONObject() else JSONTokener(String(body, 0, read, Charsets.UTF_8)).nextValue() as? JSONObject ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        val out = client.getOutputStream()
        fun respond(status: String, o: JSONObject) {
            val bytes = o.toString().toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            out.write(bytes)
            out.flush()
        }
        when (path) {
            "/hello" -> respond("200 OK", handler.hello())
            "/pair" -> {
                handler.pairRequested(json.optString("phone").ifBlank { "A phone" }.take(40))
                respond("200 OK", JSONObject().put("ok", true))
            }
            "/pair/confirm" -> {
                val t = handler.pairConfirmed(json.optString("code"), json)
                if (t == null) respond("403 Forbidden", JSONObject().put("error", "Wrong code")) else respond("200 OK", JSONObject().put("token", t))
            }
            else -> {
                if (!handler.authorised(token)) {
                    respond("401 Unauthorized", JSONObject().put("error", "Pair this phone first"))
                    return
                }
                when (path) {
                    "/play" -> {
                        val error = handler.play(json)
                        if (error == null) respond("200 OK", JSONObject().put("ok", true)) else respond("409 Conflict", JSONObject().put("error", error))
                    }
                    "/key" -> {
                        handler.key(json.optString("key"))
                        respond("200 OK", JSONObject().put("ok", true))
                    }
                    else -> respond("404 Not Found", JSONObject().put("error", "Unknown"))
                }
            }
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
}

/** A Streammania TV found on the home network. */
data class FoundTv(val name: String, val host: String, val port: Int) {
    val address: String get() = "$host:$port"
}

/** Talking to a TV from a phone. Each call returns null on success, or a message saying what went wrong. */
object TvLinkClient {
    private suspend fun call(tv: FoundTv, path: String, body: JSONObject, token: String?): Pair<Int, JSONObject?> =
        withContext(Dispatchers.IO) {
            try {
                val c = URL("http://${tv.address}$path").openConnection() as HttpURLConnection
                c.connectTimeout = 5_000
                c.readTimeout = 10_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                if (token != null) c.setRequestProperty("X-Streammania-Token", token)
                try {
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    code to (runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull())
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                0 to null
            }
        }

    suspend fun hello(tv: FoundTv): JSONObject? = call(tv, "/hello", JSONObject(), null).second

    suspend fun requestPairing(tv: FoundTv, phoneName: String): String? {
        val (code, _) = call(tv, "/pair", JSONObject().put("phone", phoneName), null)
        return if (code == 200) null else "Couldn't reach ${tv.name}. Is Streammania open on the TV?"
    }

    /** Returns the token, or null if the code was wrong or the TV can't be reached. */
    suspend fun confirmPairing(tv: FoundTv, code: String, extras: JSONObject): String? {
        extras.put("code", code)
        val (status, o) = call(tv, "/pair/confirm", extras, null)
        return if (status == 200) o?.optString("token")?.takeIf { it.isNotEmpty() } else null
    }

    suspend fun play(tv: FoundTv, token: String, spec: JSONObject): String? {
        val (status, o) = call(tv, "/play", spec, token)
        return when (status) {
            200 -> null
            401 -> "Pair this phone with ${tv.name} again"
            0 -> "Couldn't reach ${tv.name}. Is Streammania open on the TV?"
            else -> o?.optString("error")?.ifBlank { null } ?: "${tv.name} couldn't play that"
        }
    }

    suspend fun key(tv: FoundTv, token: String, key: String): Boolean = call(tv, "/key", JSONObject().put("key", key), token).first == 200
}

/** Finds Streammania TVs on the home network. Results arrive on the main thread. */
class TvFinder(context: Context, private val onChange: (List<FoundTv>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundTv>()
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        val manager = nsd ?: return
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    waiting.addLast(serviceInfo)
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    found.remove(serviceInfo.serviceName ?: "")
                    onChange(found.values.toList())
                }
            }
        }
        listener = l
        runCatching { manager.discoverServices(TV_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    /** One at a time: older Android can only resolve one service at once. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        if (resolving) return
        val next = waiting.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null) {
                            val name = serviceInfo.serviceName ?: host
                            found[name] = FoundTv(name, host, serviceInfo.port)
                            onChange(found.values.toList())
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { nsd?.stopServiceDiscovery(l) }
        listener = null
    }
}
