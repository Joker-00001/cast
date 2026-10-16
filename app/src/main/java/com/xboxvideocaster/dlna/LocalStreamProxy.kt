package com.xboxvideocaster.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Serveur Proxy & Convertisseur temps-réel pour Xbox One / Series :
 * - Pour les flux HLS (.m3u8) : télécharge et fusionne les segments .ts en continu (remuxing live)
 *   en un seul flux MPEG-TS continu (video/vnd.dlna.mpeg-tts) que la Xbox décode nativement sans écran noir !
 * - Pour les flux .mp4 : relaie le flux avec support du Range HTTP et injection des headers Referer / Origin.
 */
class LocalStreamProxy(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    var port: Int = 8989
        private set

    private data class StreamConfig(
        val url: String,
        val referer: String,
        val userAgent: String
    )

    private val streamRegistry = java.util.concurrent.ConcurrentHashMap<String, StreamConfig>()

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(start = true, isDaemon = true, name = "XboxLocalStreamProxy") {
            try {
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
     * Crée une URL compatible DLNA Xbox :
     * - Si la source est un .m3u8 (HLS), l'URL se termine par .ts et streame un flux MPEG-TS continu.
     * - Si la source est un .mp4, l'URL se termine par .mp4.
     */
    fun createProxyUrl(realStreamUrl: String, referer: String?, userAgent: String?): String {
        start()
        val ref = if (!referer.isNullOrBlank()) referer else "https://anime-sama.to/"
        val ua = if (!userAgent.isNullOrBlank()) userAgent else
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val id = "vid_" + System.currentTimeMillis()
        streamRegistry[id] = StreamConfig(realStreamUrl, ref, ua)

        val isHls = realStreamUrl.contains(".m3u8", ignoreCase = true) ||
                realStreamUrl.contains("urlset", ignoreCase = true)
        val ext = if (isHls) ".ts" else ".mp4"
        return "http://$localIp:$port/xbox/$id$ext"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30000
                val input = client.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.ROOT)
                val path = parts[1]

                var rangeHeader: String? = null
                var line: String?
                while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.lowercase(Locale.ROOT).startsWith("range:")) {
                        rangeHeader = line!!.substringAfter(":").trim()
                    }
                }

                val key = path.substringAfter("/xbox/")
                    .substringBefore(".ts")
                    .substringBefore(".mp4")
                    .substringBefore("?")

                val config = streamRegistry[key] ?: streamRegistry.values.lastOrNull() ?: return
                val out = client.getOutputStream()

                val isHls = config.url.contains(".m3u8", ignoreCase = true) ||
                        config.url.contains("urlset", ignoreCase = true)

                if (isHls) {
                    serveHlsAsContinuousMpegTs(method, config, out)
                } else {
                    serveDirectMp4(method, config, rangeHeader, out)
                }
            } catch (e: Exception) {
                Log.w("LocalStreamProxy", "Client connection closed: ${e.message}")
            }
        }
    }

    /**
     * Télécharge la playlist HLS (.m3u8), sélectionne la meilleure qualité,
     * puis envoie tous les segments .ts bout-à-bout en un seul flux continu compatible Xbox DLNA !
     */
    private fun serveHlsAsContinuousMpegTs(method: String, config: StreamConfig, out: OutputStream) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/vnd.dlna.mpeg-tts\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        out.flush()

        if (method == "HEAD") return

        // 1. Récupérer le contenu du fichier .m3u8 initial
        val firstM3u8 = fetchTextWithHeaders(config.url, config) ?: return
        val baseUri = URL(config.url)

        // 2. Est-ce un Master Playlist (contenant #EXT-X-STREAM-INF) ?
        var mediaPlaylistUrl = config.url
        var mediaPlaylistText = firstM3u8

        if (firstM3u8.contains("#EXT-X-STREAM-INF")) {
            val variantUrl = pickBestVariantUrl(firstM3u8, baseUri)
            if (variantUrl != null) {
                mediaPlaylistUrl = variantUrl
                mediaPlaylistText = fetchTextWithHeaders(variantUrl, config) ?: return
            }
        }

        // 3. Extraire la liste de tous les segments .ts de la Media Playlist
        val mediaBaseUri = URL(mediaPlaylistUrl)
        val segmentUrls = mutableListOf<String>()
        for (rawLine in mediaPlaylistText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segmentUrls.add(resolveUrl(mediaBaseUri, trimmed))
            }
        }

        // 4. Streamer chaque segment .ts séquentiellement dans le même flux HTTP vers la Xbox
        val buffer = ByteArray(32768)
        for (segUrl in segmentUrls) {
            val conn = openConnectionWithHeaders(segUrl, config, null)
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { segIn ->
                        var read: Int
                        while (segIn.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                    out.flush()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun pickBestVariantUrl(masterText: String, baseUri: URL): String? {
        val lines = masterText.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // La ligne suivante non-vide et non-commentaire est l'URL de la variante (souvent la meilleure résolution en 1er)
                for (j in (i + 1) until lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        return resolveUrl(baseUri, next)
                    }
                }
            }
        }
        return null
    }

    private fun serveDirectMp4(
        method: String,
        config: StreamConfig,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val conn = openConnectionWithHeaders(config.url, config, rangeHeader)
        try {
            val code = conn.responseCode
            val statusLine = if (code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val contentType = conn.contentType ?: "video/mp4"
            val contentLength = conn.getHeaderField("Content-Length")
            val contentRange = conn.getHeaderField("Content-Range")
            val acceptRanges = conn.getHeaderField("Accept-Ranges") ?: "bytes"

            val headers = buildString {
                append("$statusLine\r\n")
                append("Content-Type: $contentType\r\n")
                if (contentLength != null) append("Content-Length: $contentLength\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                append("Accept-Ranges: $acceptRanges\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray())
            out.flush()

            if (method == "HEAD") return

            val remoteIn: InputStream = conn.inputStream
            val buffer = ByteArray(32768)
            var read: Int
            while (remoteIn.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            out.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchTextWithHeaders(url: String, config: StreamConfig): String? {
        val conn = openConnectionWithHeaders(url, config, null)
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionWithHeaders(
        targetUrl: String,
        config: StreamConfig,
        rangeHeader: String?
    ): HttpURLConnection {
        return (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", config.userAgent)
            setRequestProperty("Referer", config.referer)
            try {
                val refUri = URL(config.referer)
                setRequestProperty("Origin", "${refUri.protocol}://${refUri.host}")
            } catch (_: Exception) {
            }
            if (rangeHeader != null) {
                setRequestProperty("Range", rangeHeader)
            }
        }
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
        } catch (_: Exception) {
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
