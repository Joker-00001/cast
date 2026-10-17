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

    companion object {
        private const val DEFAULT_XBOX_PORT = 2869
        private const val DEFAULT_XBOX_CONTROL_PATH =
            "/upnphost/udhisapi.dll?control=uuid:263ceedd-cc6e-416f-b916-1f43eada8031+urn:upnp-org:serviceId:AVTransport"
        private const val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\""
    }

    @Volatile
    private var cachedControlUrl: String? = null

    suspend fun resolveXboxControlUrl(ip: String): String = withContext(Dispatchers.IO) {
        val currentCache = cachedControlUrl
        if (currentCache != null && currentCache.contains(ip)) {
            return@withContext currentCache
        }

        val discoveredLocation = discoverAvTransportLocationViaSsdp(ip)
        if (discoveredLocation != null) {
            val parsedControlUrl = fetchControlUrlFromDescriptionXml(discoveredLocation)
            if (parsedControlUrl != null) {
                cachedControlUrl = parsedControlUrl
                return@withContext parsedControlUrl
            }
        }

        val fallback = "http://$ip:$DEFAULT_XBOX_PORT$DEFAULT_XBOX_CONTROL_PATH"
        cachedControlUrl = fallback
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
        return try {
            val req = Request.Builder().url(locationUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val avIndex = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
                if (avIndex != -1) {
                    val sub = xml.substring(avIndex)
                    val ctrlMatcher = Pattern.compile("<controlURL>([^<]+)</controlURL>").matcher(sub)
                    if (ctrlMatcher.find()) {
                        val ctrlPath = ctrlMatcher.group(1)?.trim() ?: return null
                        val base = URL(locationUrl)
                        return if (ctrlPath.startsWith("http")) {
                            ctrlPath
                        } else {
                            "${base.protocol}://${base.host}:${base.port}$ctrlPath"
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Envoie la commande SetAVTransportURI (avec la durée exacte de la vidéo pour éviter le clignotement de l'OSD Xbox)
     * suivie de Play.
     */
    suspend fun playMediaOnXbox(
        ip: String,
        videoUri: String,
        title: String = "Xbox Video Stream",
        durationSeconds: Int = 1440
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val escapedUri = escapeXml(videoUri)
            val escapedTitle = escapeXml(title)
            val formattedDuration = formatSecondsToHms(durationSeconds)

            val protocolInfo = if (videoUri.endsWith(".ts", ignoreCase = true)) {
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            } else {
                "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }

            try {
                sendSoapAction(controlUrl, "Stop", "<InstanceID>0</InstanceID>")
            } catch (_: Exception) {
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
