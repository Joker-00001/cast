package com.xboxvideocaster.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DlnaController(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    data class DiscoveredDevice(
        val name: String,
        val ip: String,
        val controlUrl: String,
        val renderingControlUrl: String = ""
    )

    companion object {
        private const val DEFAULT_XBOX_PORT = 2869
        private const val DEFAULT_XBOX_CONTROL_PATH =
            "/upnphost/udhisapi.dll?control=uuid:263ceedd-cc6e-416f-b916-1f43eada8031+urn:upnp-org:serviceId:AVTransport"
        private const val DEFAULT_XBOX_RENDERING_PATH =
            "/upnphost/udhisapi.dll?control=uuid:263ceedd-cc6e-416f-b916-1f43eada8031+urn:upnp-org:serviceId:RenderingControl"
        private const val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\""
    }

    @Volatile
    private var cachedControlUrl: String? = null

    @Volatile
    private var cachedRenderingControlUrl: String? = null

    fun setPreferredControlUrl(controlUrl: String?) {
        cachedControlUrl = controlUrl
    }

    /**
     * Scanne le réseau Wi-Fi local pour détecter toutes les TV connectées (Samsung, LG, Sony, Philips, TCL, Android TV...)
     * ainsi que les consoles Xbox One / Series X|S via SSDP Multicast + scan rapide du sous-réseau sur le port UPnP 2869.
     */
    suspend fun scanNetworkDevices(localWifiIp: String?): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val foundByIp = LinkedHashMap<String, DiscoveredDevice>()
        val locationsToInspect = LinkedHashSet<Pair<String, String>>() // Pair(ip, locationUrl)

        // 1. Découverte SSDP Multicast (MediaRenderer + AVTransport)
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 450
            val ssdpAddress = InetAddress.getByName("239.255.255.250")
            val targets = listOf(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1"
            )
            for (st in targets) {
                val msg = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 1\r\n")
                    append("ST: $st\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(msg, msg.size, ssdpAddress, 1900))
            }

            val buf = ByteArray(4096)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2000) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val senderIp = pkt.address.hostAddress ?: continue
                    val respText = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val m = Pattern.compile("(?i)Location:\\s*(http://[^\\r\\n]+)").matcher(respText)
                    if (m.find()) {
                        val loc = m.group(1)?.trim() ?: continue
                        locationsToInspect.add(Pair(senderIp, loc))
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }

        // 2. Inspecter chaque XML UPnP découvert par SSDP pour récupérer le vrai nom de la TV / Xbox et son controlURL
        for ((ip, locUrl) in locationsToInspect) {
            val device = parseDeviceFromDescriptionXml(ip, locUrl)
            if (device != null) {
                foundByIp[ip] = device
            }
        }

        // 3. Scan rapide du sous-réseau Wi-Fi sur le port 2869 (au cas où la box Internet bloque le multicast UDP SSDP)
        val prefix = localWifiIp?.substringBeforeLast(".", "") ?: ""
        if (prefix.count { it == '.' } == 2) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
            val futures = (1..254).map { host ->
                val testIp = "$prefix.$host"
                pool.submit(java.util.concurrent.Callable {
                    if (foundByIp.containsKey(testIp) || testIp == localWifiIp) return@Callable null
                    var tcp: java.net.Socket? = null
                    try {
                        tcp = java.net.Socket()
                        tcp.connect(java.net.InetSocketAddress(testIp, DEFAULT_XBOX_PORT), 220)
                        DiscoveredDevice(
                            name = "Xbox / Appareil UPnP ($testIp)",
                            ip = testIp,
                            controlUrl = "http://$testIp:$DEFAULT_XBOX_PORT$DEFAULT_XBOX_CONTROL_PATH"
                        )
                    } catch (_: Exception) {
                        null
                    } finally {
                        try { tcp?.close() } catch (_: Exception) {}
                    }
                })
            }
            for (f in futures) {
                try {
                    val dev = f.get(400, TimeUnit.MILLISECONDS)
                    if (dev != null && !foundByIp.containsKey(dev.ip)) {
                        foundByIp[dev.ip] = dev
                    }
                } catch (_: Exception) {
                }
            }
            pool.shutdownNow()
        }

        foundByIp.values.toList()
    }

    private fun parseDeviceFromDescriptionXml(ip: String, locationUrl: String): DiscoveredDevice? {
        return try {
            val req = Request.Builder().url(locationUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val avIndex = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
                if (avIndex == -1) return null

                val base = URL(locationUrl)
                fun buildFullUrl(path: String): String {
                    return if (path.startsWith("http")) {
                        path
                    } else if (path.startsWith("/")) {
                        "${base.protocol}://${base.host}:${base.port}$path"
                    } else {
                        "${base.protocol}://${base.host}:${base.port}/$path"
                    }
                }

                val sub = xml.substring(avIndex)
                val ctrlMatcher = Pattern.compile("<controlURL>([^<]+)</controlURL>").matcher(sub)
                if (!ctrlMatcher.find()) return null
                val ctrlPath = ctrlMatcher.group(1)?.trim() ?: return null
                val fullCtrlUrl = buildFullUrl(ctrlPath)

                // Extraire aussi l'URL RenderingControl (pour régler le volume et le Mute de la TV / Xbox)
                var fullRenderingUrl = ""
                val rcIndex = xml.indexOf("urn:schemas-upnp-org:service:RenderingControl:1")
                if (rcIndex != -1) {
                    val rcSub = xml.substring(rcIndex)
                    val rcMatcher = Pattern.compile("<controlURL>([^<]+)</controlURL>").matcher(rcSub)
                    if (rcMatcher.find()) {
                        val rcPath = rcMatcher.group(1)?.trim() ?: ""
                        if (rcPath.isNotBlank()) {
                            fullRenderingUrl = buildFullUrl(rcPath)
                        }
                    }
                }

                val nameMatcher = Pattern.compile("<friendlyName>([^<]+)</friendlyName>").matcher(xml)
                val rawName = if (nameMatcher.find()) nameMatcher.group(1)?.trim() else null
                val displayName = if (!rawName.isNullOrBlank()) "$rawName ($ip)" else "TV / Xbox ($ip)"

                DiscoveredDevice(
                    name = displayName,
                    ip = ip,
                    controlUrl = fullCtrlUrl,
                    renderingControlUrl = fullRenderingUrl
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveXboxControlUrl(ip: String): String = withContext(Dispatchers.IO) {
        val currentCache = cachedControlUrl
        if (currentCache != null && currentCache.contains(ip)) {
            return@withContext currentCache
        }

        val discoveredLocation = discoverAvTransportLocationViaSsdp(ip)
        if (discoveredLocation != null) {
            val parsedDevice = parseDeviceFromDescriptionXml(ip, discoveredLocation)
            if (parsedDevice != null) {
                cachedControlUrl = parsedDevice.controlUrl
                if (parsedDevice.renderingControlUrl.isNotBlank()) {
                    cachedRenderingControlUrl = parsedDevice.renderingControlUrl
                }
                return@withContext parsedDevice.controlUrl
            }
        }

        val fallback = "http://$ip:$DEFAULT_XBOX_PORT$DEFAULT_XBOX_CONTROL_PATH"
        cachedControlUrl = fallback
        fallback
    }

    suspend fun resolveRenderingControlUrl(ip: String): String = withContext(Dispatchers.IO) {
        val currentRc = cachedRenderingControlUrl
        if (!currentRc.isNullOrBlank() && currentRc.contains(ip)) {
            return@withContext currentRc
        }

        val discoveredLocation = discoverAvTransportLocationViaSsdp(ip)
        if (discoveredLocation != null) {
            val parsedDevice = parseDeviceFromDescriptionXml(ip, discoveredLocation)
            if (parsedDevice != null && parsedDevice.renderingControlUrl.isNotBlank()) {
                cachedRenderingControlUrl = parsedDevice.renderingControlUrl
                return@withContext parsedDevice.renderingControlUrl
            }
        }

        // Si l'URL AVTransport est déjà connue (ex: Samsung / LG / Sony), déduire l'URL RenderingControl
        val currentAv = cachedControlUrl
        if (!currentAv.isNullOrBlank() && currentAv.contains(ip)) {
            val guessed = currentAv
                .replace("AVTransport", "RenderingControl")
                .replace("avtransport", "renderingcontrol")
            if (guessed != currentAv) {
                cachedRenderingControlUrl = guessed
                return@withContext guessed
            }
        }

        val fallback = "http://$ip:$DEFAULT_XBOX_PORT$DEFAULT_XBOX_RENDERING_PATH"
        cachedRenderingControlUrl = fallback
        fallback
    }

    private fun discoverAvTransportLocationViaSsdp(targetIp: String): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 1500
            val ssdPAddress = InetAddress.getByName("239.255.255.250")
            val searchMessage = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n")
            }
            val sendData = searchMessage.toByteArray(Charsets.US_ASCII)
            val sendPacket = DatagramPacket(sendData, sendData.size, ssdPAddress, 1900)
            socket.send(sendPacket)

            val buf = ByteArray(4096)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1500) {
                val receivePacket = DatagramPacket(buf, buf.size)
                socket.receive(receivePacket)
                val senderIp = receivePacket.address.hostAddress ?: ""
                if (senderIp == targetIp) {
                    val responseText = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    val matcher = Pattern.compile("(?i)Location:\\s*(http://[^\\r\\n]+)").matcher(responseText)
                    if (matcher.find()) {
                        return matcher.group(1)?.trim()
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun fetchControlUrlFromDescriptionXml(locationUrl: String): String? {
        return parseDeviceFromDescriptionXml("", locationUrl)?.controlUrl
    }

    /**
     * Envoie la commande SetAVTransportURI (avec la durée exacte de la vidéo pour éviter le clignotement de l'OSD Xbox)
     * suivie de Play.
     */
    suspend fun playMediaOnXbox(
        ip: String,
        videoUri: String,
        title: String = "Xbox Video Stream",
        durationSeconds: Int = 1440,
        stopFirst: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val escapedUri = escapeXml(videoUri)
            val escapedTitle = escapeXml(title)
            val formattedDuration = formatSecondsToHms(durationSeconds)

            val protocolInfo = if (videoUri.endsWith(".ts", ignoreCase = true)) {
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            } else {
                "http-get:*:video/mp4:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }

            if (stopFirst) {
                try {
                    sendSoapAction(controlUrl, "Stop", "<InstanceID>0</InstanceID>")
                } catch (_: Exception) {
                }
            }

            val didlLiteMetadata = escapeXml(
                """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res duration="$formattedDuration" protocolInfo="$protocolInfo">$escapedUri</res></item></DIDL-Lite>"""
            )

            val setUriArgs = """
                <InstanceID>0</InstanceID>
                <CurrentURI>$escapedUri</CurrentURI>
                <CurrentURIMetaData>$didlLiteMetadata</CurrentURIMetaData>
            """.trimIndent()

            val setResult = sendSoapAction(controlUrl, "SetAVTransportURI", setUriArgs)
            if (setResult.isFailure) {
                val simpleArgs = """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>$escapedUri</CurrentURI>
                    <CurrentURIMetaData></CurrentURIMetaData>
                """.trimIndent()
                val retryResult = sendSoapAction(controlUrl, "SetAVTransportURI", simpleArgs)
                if (retryResult.isFailure) {
                    return@withContext retryResult
                }
            }

            val playArgs = """
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            """.trimIndent()

            sendSoapAction(controlUrl, "Play", playArgs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Met en pause la lecture sur la Xbox
     */
    suspend fun pauseMedia(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            sendSoapAction(controlUrl, "Pause", "<InstanceID>0</InstanceID>")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reprend la lecture sur la Xbox
     */
    suspend fun resumeMedia(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val playArgs = """
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            """.trimIndent()
            sendSoapAction(controlUrl, "Play", playArgs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saute à un moment précis (HH:MM:SS) via UPnP Seek REL_TIME
     */
    suspend fun seekMedia(ip: String, targetSeconds: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val targetHms = formatSecondsToHms(targetSeconds)
            val seekArgs = """
                <InstanceID>0</InstanceID>
                <Unit>REL_TIME</Unit>
                <Target>$targetHms</Target>
            """.trimIndent()
            sendSoapAction(controlUrl, "Seek", seekArgs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Récupère le temps de lecture écoulé depuis le début du flux actuel (en secondes)
     */
    suspend fun getElapsedSeconds(ip: String): Int = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:GetPositionInfo>
    </s:Body>
</s:Envelope>""".trimIndent()

            val request = Request.Builder()
                .url(controlUrl)
                .addHeader("Content-Type", SOAP_MEDIA_TYPE)
                .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"")
                .post(envelope.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0
                val xml = resp.body?.string() ?: return@withContext 0
                val m = Pattern.compile("<RelTime[^>]*>([^<]+)</RelTime>").matcher(xml)
                if (m.find()) {
                    return@withContext parseHmsToSeconds(m.group(1)?.trim() ?: "0:00:00")
                }
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Commande Stop pour interrompre la lecture
     */
    suspend fun stopMedia(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            sendSoapAction(controlUrl, "Stop", "<InstanceID>0</InstanceID>")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Récupère le volume actuel de la TV / Xbox (0 à 100) via UPnP RenderingControl:1
     */
    suspend fun getVolume(ip: String): Int? = withContext(Dispatchers.IO) {
        try {
            val rcUrl = resolveRenderingControlUrl(ip)
            val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
            <InstanceID>0</InstanceID>
            <Channel>Master</Channel>
        </u:GetVolume>
    </s:Body>
</s:Envelope>""".trimIndent()

            val request = Request.Builder()
                .url(rcUrl)
                .addHeader("Content-Type", SOAP_MEDIA_TYPE)
                .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"")
                .post(envelope.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val xml = resp.body?.string() ?: return@withContext null
                val m = Pattern.compile("<CurrentVolume[^>]*>\\s*(\\d+)\\s*</CurrentVolume>").matcher(xml)
                if (m.find()) {
                    return@withContext m.group(1)?.toIntOrNull()?.coerceIn(0, 100)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Définit le volume de la TV / Xbox (0 à 100) via UPnP RenderingControl:1
     */
    suspend fun setVolume(ip: String, volume: Int): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val safeVol = volume.coerceIn(0, 100)
            val rcUrl = resolveRenderingControlUrl(ip)
            val body = """
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredVolume>$safeVol</DesiredVolume>
            """.trimIndent()
            val res = sendRenderingSoapAction(rcUrl, "SetVolume", body)
            if (res.isSuccess) Result.success(safeVol) else Result.failure(res.exceptionOrNull() ?: Exception("Erreur SetVolume"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Monte ou baisse le volume de la TV / Xbox (ex: +5 ou -5)
     */
    suspend fun adjustVolume(ip: String, delta: Int, fallbackCurrent: Int = 25): Result<Int> = withContext(Dispatchers.IO) {
        val current = getVolume(ip) ?: fallbackCurrent
        val next = (current + delta).coerceIn(0, 100)
        setVolume(ip, next)
    }

    /**
     * Active ou désactive le mode Muet (Mute) sur la TV / Xbox
     */
    suspend fun setMute(ip: String, mute: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val rcUrl = resolveRenderingControlUrl(ip)
            val muteVal = if (mute) 1 else 0
            val body = """
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredMute>$muteVal</DesiredMute>
            """.trimIndent()
            val res = sendRenderingSoapAction(rcUrl, "SetMute", body)
            if (res.isSuccess) Result.success(mute) else Result.failure(res.exceptionOrNull() ?: Exception("Erreur SetMute"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendRenderingSoapAction(rcUrl: String, actionName: String, bodyInnerXml: String): Result<Unit> {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:$actionName xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
            $bodyInnerXml
        </u:$actionName>
    </s:Body>
</s:Envelope>""".trimIndent()

        val request = Request.Builder()
            .url(rcUrl)
            .addHeader("Content-Type", SOAP_MEDIA_TYPE)
            .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#$actionName\"")
            .post(envelope.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                return Result.failure(Exception("HTTP ${response.code} sur $actionName: $errBody"))
            }
        }
        return Result.success(Unit)
    }

    private fun sendSoapAction(controlUrl: String, actionName: String, bodyInnerXml: String): Result<Unit> {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:$actionName xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            $bodyInnerXml
        </u:$actionName>
    </s:Body>
</s:Envelope>""".trimIndent()

        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("Content-Type", SOAP_MEDIA_TYPE)
            .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$actionName\"")
            .post(envelope.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                return Result.failure(Exception("HTTP ${response.code} sur $actionName: $errBody"))
            }
        }
        return Result.success(Unit)
    }

    fun formatSecondsToHms(totalSec: Int): String {
        val safe = totalSec.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    private fun parseHmsToSeconds(hms: String): Int {
        val parts = hms.split(":")
        if (parts.size != 3) return 0
        val h = parts[0].toIntOrNull() ?: 0
        val m = parts[1].toIntOrNull() ?: 0
        val s = parts[2].substringBefore(".").toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
