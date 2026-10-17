package com.xboxvideocaster.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Serveur Proxy & Remuxeur MPEG-TS haute performance pour Xbox One / Series :
 * 1. Pool HTTP/2 persistant (OkHttp) + déduplication des téléchargements en cours (Future)
 *    pour ne jamais télécharger deux fois le même segment.
 * 2. Pré-chargement de 2 segments complets (20s) avant le démarrage et 6 segments (60s) d'avance en RAM.
 * 3. Nettoyage MPEG-TS (ISO 13818-1) : supprime les paquets de bourrage illégaux (AFC=3, alen=183)
 *    générés par nginx-vod-module à la fin de chaque segment .ts et ré-indexe les Continuity Counters (CC)
 *    en continu -> élimine les micro-freezes de 0.5s et empêche le menu OSD de la TV de pop/dépop en boucle !
 */
class LocalStreamProxy(private val context: Context) {

    companion object {
        @Volatile
        private var instance: LocalStreamProxy? = null

        fun getInstance(context: Context): LocalStreamProxy {
            return instance ?: synchronized(this) {
                instance ?: LocalStreamProxy(context.applicationContext).also {
                    it.start()
                    instance = it
                }
            }
        }
    }

    @Volatile
    var activeStream: PreparedStream? = null

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
        val totalDurationSec: Int,
        @Volatile var lastServedSegmentIdx: Int = 0,
        @Volatile var lastSeekOrStartWallMs: Long = System.currentTimeMillis()
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()

    private val streamRegistry = ConcurrentHashMap<String, PreparedStream>()
    private val segmentFutures = ConcurrentHashMap<String, Future<ByteArray?>>()
    // 3 threads max pour ne pas saturer l'antenne Wi-Fi du téléphone pendant qu'il envoie le flux à la Smart TV
    private val prefetchPool = Executors.newFixedThreadPool(3)

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
        clearSegmentCache()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private fun clearSegmentCache() {
        for (f in segmentFutures.values) {
            try {
                f.cancel(true)
            } catch (_: Exception) {
            }
        }
        segmentFutures.clear()
    }

    /**
     * Analyse la vidéo, prépare les segments, la durée totale et pré-charge les 2 premiers segments
     * en RAM avant d'envoyer l'ordre de lecture à la TV / Xbox.
     */
    fun prepareStream(realStreamUrl: String, referer: String?, userAgent: String?): PreparedStream {
        start()
        clearSegmentCache()

        val ref = resolveBestReferer(realStreamUrl, referer)
        val ua = if (!userAgent.isNullOrBlank()) userAgent else
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val id = "vid" + System.currentTimeMillis()
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
            totalDurationSec = if (totalDuration > 0) totalDuration else 1440,
            lastServedSegmentIdx = 0,
            lastSeekOrStartWallMs = System.currentTimeMillis()
        )
        streamRegistry[id] = prepared

        // Pré-charger les 3 premiers segments sans saturer le Wi-Fi et attendre les 2 premiers en RAM
        if (segments.isNotEmpty()) {
            triggerPrefetch(prepared, 0, 3)
            val prebufferCount = min(2, segments.size)
            for (i in 0 until prebufferCount) {
                try {
                    ensureSegmentDownload(segments[i].url, prepared.referer, prepared.userAgent)
                        .get(10, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
        }

        return prepared
    }

    /**
     * Pré-charge la zone cible avant un saut (+30s / -30s / curseur)
     */
    fun warmupForSeek(prepared: PreparedStream, targetSec: Int): Int {
        if (!prepared.isHls || prepared.segments.isEmpty()) return targetSec
        val startIndex = prepared.segments.indexOfLast { it.startTimeSec <= targetSec }.coerceAtLeast(0)
        prepared.lastServedSegmentIdx = startIndex
        prepared.lastSeekOrStartWallMs = System.currentTimeMillis()
        triggerPrefetch(prepared, startIndex, 3)
        try {
            ensureSegmentDownload(
                prepared.segments[startIndex].url,
                prepared.referer,
                prepared.userAgent
            ).get(6, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return prepared.segments[startIndex].startTimeSec.toInt()
    }

    private fun resolveBestReferer(streamUrl: String, pageReferer: String?): String {
        val lower = streamUrl.lowercase(Locale.ROOT)
        return when {
            lower.contains("fsvid") -> "https://fsvid.lol/"
            lower.contains("vidzy") -> "https://vidzy.cc/"
            lower.contains("vmnow") || lower.contains("vmpx") || lower.contains("ansembed") -> "https://ansembed.net/"
            lower.contains("vidmoly") -> "https://vidmoly.to/"
            lower.contains("sibnet") -> "https://video.sibnet.ru/"
            lower.contains("sendvid") -> "https://sendvid.com/"
            !pageReferer.isNullOrBlank() -> pageReferer
            else -> "https://anime-sama.to/"
        }
    }

    /**
     * Génère l'URL proxy démarrant à `startSeconds` (ex: /xbox/vid123456_s90.ts)
     */
    fun buildPlaybackUrl(prepared: PreparedStream, startSeconds: Int = 0): String {
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val safeSec = max(0, startSeconds)
        val ext = if (prepared.isHls) ".ts" else ".mp4"
        return "http://$localIp:$port/xbox/${prepared.id}_s${safeSec}$ext"
    }

    private fun parseHlsSegments(masterOrMediaUrl: String, referer: String, userAgent: String): List<SegmentItem> {
        return try {
            val normalizedUrl = if (masterOrMediaUrl.contains("_l/index-", ignoreCase = true)) {
                masterOrMediaUrl.replace("_l/index-", "_n/index-", ignoreCase = true)
            } else {
                masterOrMediaUrl
            }
            val firstM3u8 = fetchText(normalizedUrl, referer, userAgent)
                ?: fetchText(masterOrMediaUrl, referer, userAgent)
                ?: return emptyList()
            val baseUri = URL(normalizedUrl)

            var mediaPlaylistUrl = normalizedUrl
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
                // Timeout court uniquement pour lire les headers HTTP, puis 0 (infini) pendant le streaming
                // afin que la connexion ne coupe JAMAIS quand le buffer de la Smart TV est plein !
                client.soTimeout = 10000
                client.keepAlive = true
                client.sendBufferSize = 262144 // 256 KB socket buffer
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

                // Désactiver le timeout une fois la requête lue pour supporter les pauses / buffers TV
                client.soTimeout = 0

                val token = path.substringAfter("/xbox/")
                    .substringBefore(".ts")
                    .substringBefore(".mp4")
                    .substringBefore("?")

                val streamId = token.substringBefore("_s")
                val startSec = token.substringAfter("_s", "0").toIntOrNull() ?: 0

                val prepared = streamRegistry[streamId] ?: streamRegistry.values.lastOrNull() ?: return
                val out = client.getOutputStream()

                if (prepared.isHls) {
                    serveBufferedHlsStream(method, prepared, startSec, rangeHeader, out)
                } else {
                    serveDirectMp4(method, prepared, rangeHeader, out)
                }
            } catch (e: Exception) {
                Log.w("LocalStreamProxy", "Client connection closed: ${e.message}")
            }
        }
    }

    /**
     * Envoie les segments MPEG-TS en continu avec :
     * - Anti-rollback : si la Smart TV se reconnecte en cours de film (même sans header Range à cause de DLNA.ORG_OP=00),
     *   reprend automatiquement au segment où la TV s'était arrêtée au lieu de repartir au début !
     * - DLNA.ORG_OP=00 sans DLNA.ORG_PN strict (accepte l'audio AAC 32 kHz d'Anime-Sama sans reset sur Xbox)
     * - Filtrage des paquets illégaux (AFC=3, alen=183) et continuité stricte des compteurs CC MPEG-TS !
     */
    private fun serveBufferedHlsStream(
        method: String,
        prepared: PreparedStream,
        startSec: Int,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/vnd.dlna.mpeg-tts\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        out.flush()

        if (method == "HEAD") return

        val segments = prepared.segments
        if (segments.isEmpty()) return

        var startIndex = segments.indexOfLast { it.startTimeSec <= startSec }.coerceAtLeast(0)
        val elapsedSinceSeekOrStartMs = System.currentTimeMillis() - prepared.lastSeekOrStartWallMs
        // Si la Smart TV se reconnecte plus de 12s après le démarrage ou le dernier saut (suite à une micro-coupure Wi-Fi),
        // reprendre directement au segment en cours (même si la TV n'envoie pas de header Range à cause de DLNA.ORG_OP=00)
        // -> élimine définitivement le rollback de 5 minutes en arrière !
        if ((elapsedSinceSeekOrStartMs > 12_000L || (rangeHeader != null && !rangeHeader.startsWith("bytes=0-"))) &&
            prepared.lastServedSegmentIdx > startIndex + 1
        ) {
            startIndex = (prepared.lastServedSegmentIdx - 1).coerceAtLeast(startIndex)
        }

        // Table des Continuity Counters (CC 0..15) par PID pour un flux MPEG-TS 100% continu sans coupure
        val pidContinuityMap = HashMap<Int, Int>()

        for (i in startIndex until segments.size) {
            prepared.lastServedSegmentIdx = i

            // Pré-charger 4 segments d'avance (sur 3 threads) pour garder le Wi-Fi fluide vers la TV
            // tout en ayant ~40-60s d'avance en RAM
            triggerPrefetch(prepared, i + 1, 4)

            // Libérer la RAM au fur et à mesure (garder les 2 derniers segments)
            if (i - 2 >= 0) {
                segmentFutures.remove(segments[i - 2].url)
            }

            val segUrl = segments[i].url
            var rawBytes = try {
                ensureSegmentDownload(segUrl, prepared.referer, prepared.userAgent)
                    .get(25, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            if (rawBytes == null) {
                segmentFutures.remove(segUrl)
                rawBytes = downloadSegmentBytes(segUrl, prepared.referer, prepared.userAgent) ?: continue
            }

            val cleanBytes = sanitizeTsSegment(rawBytes, pidContinuityMap, isFirstSegment = (i == startIndex))
            var offset = 0
            val chunkSize = 32768 // 32 KB chunks pour un flux Wi-Fi régulier sans pic de latence
            while (offset < cleanBytes.size) {
                val len = min(chunkSize, cleanBytes.size - offset)
                out.write(cleanBytes, offset, len)
                offset += len
            }
            out.flush()
        }
    }

    /**
     * Paquet PMT (PID 0x0FFF) propre ne déclarant que la vidéo H.264 (PID 0x0100) et l'audio AAC (PID 0x0101),
     * sans la piste fantôme Apple ID3 Timed Metadata (PID 0x0102, stream_type 0x15) présente sur ansembed.net.
     */
    private val cleanPmtPacket: ByteArray = ByteArray(188) { 0xFF.toByte() }.apply {
        val headerAndSection = intArrayOf(
            0x47, 0x4F, 0xFF, 0x10, 0x00,
            0x02, 0xB0, 0x17, 0x00, 0x01, 0xC1, 0x00, 0x00, 0xE1, 0x00, 0xF0, 0x00,
            0x1B, 0xE1, 0x00, 0xF0, 0x00,
            0x0F, 0xE1, 0x01, 0xF0, 0x00,
            0x2F, 0x44, 0xB9, 0x9B
        )
        for (idx in headerAndSection.indices) {
            this[idx] = headerAndSection[idx].toByte()
        }
    }

    /**
     * Nettoie un segment MPEG-TS (paquets de 188 octets) :
     * 1. Supprime les paquets de bourrage illégaux (AFC=3 avec adaptation_field_length >= 183).
     * 2. Supprime la piste fantôme ID3 (PID 0x0102).
     * 3. Conserve les tables PAT (PID 0x0000) et remplace le PMT (PID 0x0FFF) par un PMT H.264+AAC propre
     *    avec Continuity Counter (CC) continu dans chaque segment (indispensable pour que les Smart TV
     *    ne perdent pas le signal PSI au bout de quelques minutes !).
     */
    private fun sanitizeTsSegment(
        raw: ByteArray,
        ccMap: HashMap<Int, Int>,
        isFirstSegment: Boolean
    ): ByteArray {
        if (raw.size < 188 || raw.size % 188 != 0) return raw

        val outBuf = ByteArray(raw.size)
        var outPos = 0
        val totalPackets = raw.size / 188

        for (p in 0 until totalPackets) {
            val off = p * 188
            if (raw[off] != 0x47.toByte()) {
                System.arraycopy(raw, off, outBuf, outPos, 188)
                outPos += 188
                continue
            }

            val pid = ((raw[off + 1].toInt() and 0x1F) shl 8) or (raw[off + 2].toInt() and 0xFF)
            val afc = (raw[off + 3].toInt() ushr 4) and 0x03

            // Ignorer les paquets de bourrage invalides (AFC=3 mais adaptation_field_length >= 183 -> 0 octet de payload)
            if (afc == 3) {
                val alen = raw[off + 4].toInt() and 0xFF
                if (alen >= 183) {
                    continue
                }
            }

            // Ignorer les paquets de bourrage purs (PID 0x1FFF) et la piste fantôme ID3 metadata (PID 0x0102)
            if (pid == 0x1FFF || pid == 0x0102) {
                continue
            }

            // Remplacer le PMT (PID 0x0FFF) dans chaque segment par notre PMT propre (sans piste ID3 0x0102)
            // tout en conservant PAT (PID 0) et PMT (PID 0x0FFF) pour que la Smart TV garde le verrouillage du signal !
            if (pid == 0x0FFF) {
                System.arraycopy(cleanPmtPacket, 0, outBuf, outPos, 188)
            } else {
                System.arraycopy(raw, off, outBuf, outPos, 188)
            }

            // Désactiver le flag de discontinuité éventuel pour éviter que l'OSD Xbox ne s'affiche
            if (!isFirstSegment && (afc == 2 || afc == 3)) {
                val alen = outBuf[outPos + 4].toInt() and 0xFF
                if (alen > 0) {
                    outBuf[outPos + 5] = (outBuf[outPos + 5].toInt() and 0x7F).toByte()
                }
            }

            // Mettre à jour le Continuity Counter (CC) uniquement si le paquet contient un payload (AFC == 1 ou 3)
            if (afc == 1 || afc == 3) {
                val prevCc = ccMap[pid]
                val nextCc = if (prevCc == null) {
                    outBuf[outPos + 3].toInt() and 0x0F
                } else {
                    (prevCc + 1) and 0x0F
                }
                ccMap[pid] = nextCc
                outBuf[outPos + 3] = ((outBuf[outPos + 3].toInt() and 0xF0) or nextCc).toByte()
            }

            outPos += 188
        }

        return if (outPos == outBuf.size) outBuf else outBuf.copyOf(outPos)
    }

    private fun triggerPrefetch(prepared: PreparedStream, fromIndex: Int, count: Int) {
        val segments = prepared.segments
        val endIndex = min(segments.size, fromIndex + count)
        for (idx in fromIndex until endIndex) {
            ensureSegmentDownload(segments[idx].url, prepared.referer, prepared.userAgent)
        }
    }

    private fun ensureSegmentDownload(
        segUrl: String,
        referer: String,
        userAgent: String
    ): Future<ByteArray?> {
        return segmentFutures.computeIfAbsent(segUrl) {
            prefetchPool.submit(Callable {
                downloadSegmentBytes(segUrl, referer, userAgent)
            })
        }
    }

    private fun downloadSegmentBytes(url: String, referer: String, userAgent: String): ByteArray? {
        for (attempt in 1..5) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Referer", referer)
                try {
                    val refUri = URL(referer)
                    reqBuilder.header("Origin", "${refUri.protocol}://${refUri.host}")
                } catch (_: Exception) {
                }

                val bytes = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    return bytes
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(350L * attempt)
            } catch (_: InterruptedException) {
                break
            }
        }
        return null
    }

    private fun pickBestVariantUrl(masterText: String, baseUri: URL): String? {
        val lines = masterText.lines()
        var bestUrl: String? = null
        var bestBandwidth = -1L

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = line.substringAfter("BANDWIDTH=", "")
                    .substringBefore(",")
                    .trim()
                    .toLongOrNull() ?: 0L
                for (j in (i + 1) until lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        val candidate = resolveUrl(baseUri, next)
                        if (bw > bestBandwidth || bestUrl == null) {
                            bestBandwidth = bw
                            bestUrl = candidate
                        }
                        break
                    }
                }
            }
        }
        return bestUrl
    }

    private fun serveDirectMp4(
        method: String,
        prepared: PreparedStream,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val reqBuilder = Request.Builder()
            .url(prepared.originalUrl)
            .header("User-Agent", prepared.userAgent)
            .header("Referer", prepared.referer)
        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val code = resp.code
            val statusLine = if (code == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val contentType = resp.header("Content-Type") ?: "video/mp4"
            val contentLength = resp.header("Content-Length")
            val contentRange = resp.header("Content-Range")

            val headers = buildString {
                append("$statusLine\r\n")
                append("Content-Type: $contentType\r\n")
                if (contentLength != null) append("Content-Length: $contentLength\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray())
            out.flush()

            if (method == "HEAD") return

            val remoteIn = resp.body?.byteStream() ?: return
            val buffer = ByteArray(65536)
            var read: Int
            while (remoteIn.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    private fun fetchText(url: String, referer: String, userAgent: String): String? {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
            try {
                val refUri = URL(referer)
                reqBuilder.header("Origin", "${refUri.protocol}://${refUri.host}")
            } catch (_: Exception) {
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
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
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
