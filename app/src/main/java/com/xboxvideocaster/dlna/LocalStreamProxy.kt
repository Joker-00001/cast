package com.xboxvideocaster.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Mini serveur Proxy HTTP local permettant de relayer n'importe quel flux (.m3u8, .ts, .mp4)
 * vers la Xbox en ajoutant automatiquement les headers Referer / Origin / User-Agent requis
 * par les hébergeurs vidéo (Vidmoly, Minochinos, Sendvid, Sibnet, etc.).
 */
class LocalStreamProxy(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    var port: Int = 8989
        private set

    @Volatile
    private var currentTargetUrl: String = ""

    @Volatile
    private var currentReferer: String = "https://anime-sama.to/"

    @Volatile
    private var currentUserAgent: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val urlRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(start = true, isDaemon = true, name = "XboxLocalStreamProxy") {
            try {
                // Essayer le port 8989 ou un port libre
                serverSocket = try {
                    ServerSocket(8989)
                } catch (e: Exception) {
                    ServerSocket(0)
                }
                port = serverSocket?.localPort ?: 8989

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    thread(start = true, isDaemon = true) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e("LocalStreamProxy", "Proxy error: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Prépare une URL locale que la Xbox peut lire directement sans blocage de Referer/CORS
     */
    fun createProxyUrl(realStreamUrl: String, referer: String?, userAgent: String?): String {
        start()
        currentTargetUrl = realStreamUrl
        if (!referer.isNullOrBlank()) currentReferer = referer
        if (!userAgent.isNullOrBlank()) currentUserAgent = userAgent

        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val id = "main_" + System.currentTimeMillis()
        urlRegistry[id] = realStreamUrl

        val ext = if (realStreamUrl.contains(".m3u8", ignoreCase = true)) ".m3u8" else ".mp4"
        return "http://$localIp:$port/stream/$id$ext"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = client.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                // Lire les headers de la Xbox (notamment Range pour le seek dans les MP4)
                var rangeHeader: String? = null
                var line: String?
                while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.lowercase(Locale.ROOT).startsWith("range:")) {
                        rangeHeader = line!!.substringAfter(":").trim()
                    }
                }

                // Identifier quelle URL distante est demandée
                val key = path.substringAfter("/stream/").substringBefore(".m3u8").substringBefore(".mp4").substringBefore("?")
                val targetUrl = urlRegistry[key] ?: currentTargetUrl
                if (targetUrl.isBlank()) return

                val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", currentUserAgent)
                    setRequestProperty("Referer", currentReferer)
                    try {
                        val refUri = URL(currentReferer)
                        setRequestProperty("Origin", "${refUri.protocol}://${refUri.host}")
                    } catch (_: Exception) {
                    }
                    if (rangeHeader != null) {
                        setRequestProperty("Range", rangeHeader)
                    }
                }

                val responseCode = connection.responseCode
                val contentType = connection.contentType ?: if (targetUrl.contains(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4"
                val isM3u8 = targetUrl.contains(".m3u8", ignoreCase = true) ||
                        contentType.contains("mpegurl", ignoreCase = true)

                val out = client.getOutputStream()

                if (isM3u8 && responseCode in 200..299) {
                    // Réécrire le fichier .m3u8 pour que chaque segment passe aussi par notre proxy
                    val m3u8Content = connection.inputStream.bufferedReader().readText()
                    val rewrittenM3u8 = rewriteM3u8(m3u8Content, targetUrl)
                    val bytes = rewrittenM3u8.toByteArray(Charsets.UTF_8)

                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/vnd.apple.mpegurl\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray())
                    out.write(bytes)
                    out.flush()
                } else {
                    // Flux binaire direct (.ts, .mp4, .aac, etc.)
                    val statusLine = if (responseCode == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
                    val contentLength = connection.getHeaderField("Content-Length")
                    val contentRange = connection.getHeaderField("Content-Range")
                    val acceptRanges = connection.getHeaderField("Accept-Ranges") ?: "bytes"

                    val header = buildString {
                        append("$statusLine\r\n")
                        append("Content-Type: $contentType\r\n")
                        if (contentLength != null) append("Content-Length: $contentLength\r\n")
                        if (contentRange != null) append("Content-Range: $contentRange\r\n")
                        append("Accept-Ranges: $acceptRanges\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray())

                    val remoteIn: InputStream = connection.inputStream
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (remoteIn.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w("LocalStreamProxy", "Client disconnected or error: ${e.message}")
            }
        }
    }

    private fun rewriteM3u8(content: String, baseUrl: String): String {
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val baseUri = URL(baseUrl)
        val lines = content.lines()
        val sb = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                sb.append("\n")
                continue
            }
            if (trimmed.startsWith("#")) {
                // Vérifier s'il y a un URI="..." dans un tag #EXT-X-KEY ou #EXT-X-MAP
                if (trimmed.contains("URI=\"")) {
                    val uriValue = trimmed.substringAfter("URI=\"").substringBefore("\"")
                    val resolved = resolveUrl(baseUri, uriValue)
                    val subId = "seg_" + resolved.hashCode().toUInt().toString(16)
                    urlRegistry[subId] = resolved
                    val proxied = "http://$localIp:$port/stream/$subId"
                    sb.append(trimmed.replace("URI=\"$uriValue\"", "URI=\"$proxied\"")).append("\n")
                } else {
                    sb.append(trimmed).append("\n")
                }
            } else {
                val resolved = resolveUrl(baseUri, trimmed)
                val subId = "seg_" + resolved.hashCode().toUInt().toString(16)
                urlRegistry[subId] = resolved
                val isSubM3u8 = resolved.contains(".m3u8", ignoreCase = true)
                val suffix = if (isSubM3u8) ".m3u8" else ".mp4"
                val proxied = "http://$localIp:$port/stream/$subId$suffix"
                sb.append(proxied).append("\n")
            }
        }
        return sb.toString()
    }

    private fun resolveUrl(base: URL, relativeOrAbsolute: String): String {
        return try {
            if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
                relativeOrAbsolute
            } else if (relativeOrAbsolute.startsWith("//")) {
                "${base.protocol}:$relativeOrAbsolute"
            } else {
                URL(base, relativeOrAbsolute).toString()
            }
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
