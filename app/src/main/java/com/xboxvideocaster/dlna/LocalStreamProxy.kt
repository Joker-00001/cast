package com.xboxvideocaster.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Serveur Proxy & Pre-fetcheur haute performance pour Xbox One / Series :
 * 1. Télécharge en avance (Pre-fetch parallèle) les segments .ts en RAM pour éliminer
 *    les micro-freezes et empêcher l'apparition/disparition du menu OSD de la Xbox !
 * 2. Calcule la durée exacte de l'épisode (via #EXTINF) et permet de sauter à n'importe quelle
 *    seconde (+30s / -30s) instantanément !
 */
class LocalStreamProxy(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    var port: Int = 8989
        private set

    data class SegmentItem(
        val index: Int,
        val url: String,
        val durationSec: Double,
        val startTimeSec: Double
    )

    data class PreparedStream(
        val id: String,
        val originalUrl: String,
        val referer: String,
        val userAgent: String,
        val isHls: Boolean,
        val segments: List<SegmentItem>,
        val totalDurationSec: Int
    )

    private val streamRegistry = ConcurrentHashMap<String, PreparedStream>()
    private val segmentRamCache = ConcurrentHashMap<String, ByteArray>()
    private val prefetchFetchingSet = ConcurrentHashMap.newKeySet<String>()
    private val prefetchPool = Executors.newFixedThreadPool(4)

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
        segmentRamCache.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Analyse la vidéo et prépare les segments et la durée totale avant l'envoi à la Xbox.
     */
    fun prepareStream(realStreamUrl: String, referer: String?, userAgent: String?): PreparedStream {
        start()
        segmentRamCache.clear()
        prefetchFetchingSet.clear()

        val ref = if (!referer.isNullOrBlank()) referer else "https://anime-sama.to/"
        val ua = if (!userAgent.isNullOrBlank()) userAgent else
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val id = "vid_" + System.currentTimeMillis()
        val isHls = realStreamUrl.contains(".m3u8", ignoreCase = true) ||
                realStreamUrl.contains("urlset", ignoreCase = true)

        var segments = emptyList<SegmentItem>()
        var totalDuration = 0

        if (isHls) {
            segments = parseHlsSegments(realStreamUrl, ref, ua)
            totalDuration = segments.sumOf { it.durationSec }.toInt()
        }

        val prepared = PreparedStream(
            id = id,
            originalUrl = realStreamUrl,
            referer = ref,
            userAgent = ua,
            isHls = isHls,
            segments = segments,
            totalDurationSec = if (totalDuration > 0) totalDuration else 1440 // 24 min par défaut
        )
        streamRegistry[id] = prepared

        // Pré-charger immédiatement les 4 premiers segments en RAM pour un démarrage instantané
        if (segments.isNotEmpty()) {
            triggerPrefetch(prepared, 0, 4)
        }

        return prepared
    }

    /**
     * Génère l'URL proxy démarrant à `startSeconds` (pour le lancement ou le saut +30s / -30s)
     */
    fun buildPlaybackUrl(prepared: PreparedStream, startSeconds: Int = 0): String {
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val safeSec = max(0, startSeconds)
        val ext = if (prepared.isHls) ".ts" else ".mp4"
        return "http://$localIp:$port/xbox/${prepared.id}_s${safeSec}$ext"
    }

    private fun parseHlsSegments(masterOrMediaUrl: String, referer: String, userAgent: String): List<SegmentItem> {
        return try {
            val firstM3u8 = fetchText(masterOrMediaUrl, referer, userAgent) ?: return emptyList()
            val baseUri = URL(masterOrMediaUrl)

            var mediaPlaylistUrl = masterOrMediaUrl
            var mediaPlaylistText = firstM3u8

            if (firstM3u8.contains("#EXT-X-STREAM-INF")) {
                val variantUrl = pickBestVariantUrl(firstM3u8, baseUri)
                if (variantUrl != null) {
                    mediaPlaylistUrl = variantUrl
                    mediaPlaylistText = fetchText(variantUrl, referer, userAgent) ?: return emptyList()
                }
            }

            val mediaBaseUri = URL(mediaPlaylistUrl)
            val result = mutableListOf<SegmentItem>()
            var currentDuration = 10.0
            var accumulatedTime = 0.0
            var idx = 0

            for (rawLine in mediaPlaylistText.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#EXTINF:")) {
                    val durStr = trimmed.substringAfter("#EXTINF:").substringBefore(",").trim()
                    currentDuration = durStr.toDoubleOrNull() ?: 10.0
                } else if (!trimmed.startsWith("#")) {
                    val segUrl = resolveUrl(mediaBaseUri, trimmed)
                    result.add(
                        SegmentItem(
                            index = idx++,
                            url = segUrl,
                            durationSec = currentDuration,
                            startTimeSec = accumulatedTime
                        )
                    )
                    accumulatedTime += currentDuration
                }
            }
            result
        } catch (e: Exception) {
            Log.e("LocalStreamProxy", "Failed to parse HLS: ${e.message}")
            emptyList()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30000
                client.sendBufferSize = 262144 // 256 KB socket buffer pour fluidité maximale
                client.tcpNoDelay = true

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

                // Extraire l'ID du flux et l'offset de départ (ex: vid_123456_s90)
                val token = path.substringAfter("/xbox/")
                    .substringBefore(".ts")
                    .substringBefore(".mp4")
                    .substringBefore("?")

                val streamId = token.substringBefore("_s")
                val startSec = token.substringAfter("_s", "0").toIntOrNull() ?: 0

                val prepared = streamRegistry[streamId] ?: streamRegistry.values.lastOrNull() ?: return
                val out = client.getOutputStream()

                if (prepared.isHls) {
                    serveBufferedHlsStream(method, prepared, startSec, out)
                } else {
                    serveDirectMp4(method, prepared, rangeHeader, out)
                }
            } catch (e: Exception) {
                Log.w("LocalStreamProxy", "Client connection closed: ${e.message}")
            }
        }
    }

    /**
     * Envoie les segments MPEG-TS en continu avec pré-chargement parallèle (4 segments d'avance en RAM).
     * Zéro temps mort entre les segments -> plus aucun mini-freeze ni menu Xbox qui pop !
     */
    private fun serveBufferedHlsStream(
        method: String,
        prepared: PreparedStream,
        startSec: Int,
        out: OutputStream
    ) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/vnd.dlna.mpeg-tts\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        out.flush()

        if (method == "HEAD") return

        val segments = prepared.segments
        if (segments.isEmpty()) return

        // Trouver le segment correspondant à startSec (pour +30s / -30s)
        val startIndex = segments.indexOfLast { it.startTimeSec <= startSec }.coerceAtLeast(0)

        for (i in startIndex until segments.size) {
            // Lancer le pré-téléchargement des 5 segments suivants en parallèle
            triggerPrefetch(prepared, i + 1, 5)

            // Nettoyer les anciens segments déjà lus de la RAM pour économiser la mémoire
            if (i - 2 >= 0) {
                segmentRamCache.remove(segments[i - 2].url)
            }

            val segUrl = segments[i].url
            val cachedBytes = segmentRamCache.remove(segUrl)

            if (cachedBytes != null) {
                // Déjà en RAM : envoi instantané (0 ms d'attente !)
                out.write(cachedBytes)
                out.flush()
            } else {
                // Téléchargement direct si pas encore fini en RAM
                val downloaded = downloadSegmentBytes(segUrl, prepared.referer, prepared.userAgent)
                if (downloaded != null) {
                    out.write(downloaded)
                    out.flush()
                }
            }
        }
    }

    private fun triggerPrefetch(prepared: PreparedStream, fromIndex: Int, count: Int) {
        val segments = prepared.segments
        val endIndex = min(segments.size, fromIndex + count)
        for (idx in fromIndex until endIndex) {
            val segUrl = segments[idx].url
            if (!segmentRamCache.containsKey(segUrl) && prefetchFetchingSet.add(segUrl)) {
                prefetchPool.submit {
                    try {
                        val bytes = downloadSegmentBytes(segUrl, prepared.referer, prepared.userAgent)
                        if (bytes != null) {
                            segmentRamCache[segUrl] = bytes
                        }
                    } finally {
                        prefetchFetchingSet.remove(segUrl)
                    }
                }
            }
        }
    }

    private fun downloadSegmentBytes(url: String, referer: String, userAgent: String): ByteArray? {
        val conn = openConnection(url, referer, userAgent, null)
        return try {
            if (conn.responseCode in 200..299) {
                val baos = ByteArrayOutputStream(524288)
                conn.inputStream.use { input ->
                    val buf = ByteArray(32768)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        baos.write(buf, 0, read)
                    }
                }
                baos.toByteArray()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun pickBestVariantUrl(masterText: String, baseUri: URL): String? {
        val lines = masterText.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
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
        prepared: PreparedStream,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val conn = openConnection(prepared.originalUrl, prepared.referer, prepared.userAgent, rangeHeader)
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
            val buffer = ByteArray(65536)
            var read: Int
            while (remoteIn.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            out.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String, referer: String, userAgent: String): String? {
        val conn = openConnection(url, referer, userAgent, null)
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

    private fun openConnection(
        targetUrl: String,
        referer: String,
        userAgent: String,
        rangeHeader: String?
    ): HttpURLConnection {
        return (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", referer)
            try {
                val refUri = URL(referer)
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
