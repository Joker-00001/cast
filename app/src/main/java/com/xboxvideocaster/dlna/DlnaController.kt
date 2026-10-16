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
        // Endpoint réel découvert sur votre Xbox (192.168.1.52:2869)
        private const val DEFAULT_XBOX_PORT = 2869
        private const val DEFAULT_XBOX_CONTROL_PATH =
            "/upnphost/udhisapi.dll?control=uuid:263ceedd-cc6e-416f-b916-1f43eada8031+urn:upnp-org:serviceId:AVTransport"
        private const val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\""
    }

    @Volatile
    private var cachedControlUrl: String? = null

    /**
     * Découvre dynamiquement l'URL de contrôle AVTransport de la Xbox sur le réseau
     * ou utilise directement l'endpoint Xbox One / Series port 2869.
     */
    suspend fun resolveXboxControlUrl(ip: String): String = withContext(Dispatchers.IO) {
        val currentCache = cachedControlUrl
        if (currentCache != null && currentCache.contains(ip)) {
            return@withContext currentCache
        }

        // 1. Tenter la découverte SSDP rapide (1.5s max) ciblée sur AVTransport:1
        val discoveredLocation = discoverAvTransportLocationViaSsdp(ip)
        if (discoveredLocation != null) {
            val parsedControlUrl = fetchControlUrlFromDescriptionXml(discoveredLocation)
            if (parsedControlUrl != null) {
                cachedControlUrl = parsedControlUrl
                return@withContext parsedControlUrl
            }
        }

        // 2. Fallback direct sur l'endpoint exact de votre Xbox (Port 2869)
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
                // Extraire le bloc service AVTransport:1 et son <controlURL>
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
     * Envoie la commande SetAVTransportURI puis Play à la console Xbox via SOAP / UPnP AVTransport
     */
    suspend fun playMediaOnXbox(
        ip: String,
        videoUri: String,
        title: String = "Xbox Video Stream"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = resolveXboxControlUrl(ip)
            val escapedUri = escapeXml(videoUri)
            val escapedTitle = escapeXml(title)

            val protocolInfo = if (videoUri.endsWith(".ts", ignoreCase = true)) {
                "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            } else {
                "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }

            // 1. Arrêter tout flux précédent proprement (optionnel, ignore l'erreur)
            try {
                sendSoapAction(controlUrl, "Stop", """<InstanceID>0</InstanceID>""")
            } catch (_: Exception) {
            }

            // 2. SOAP XML SetAVTransportURI
            val didlLiteMetadata = escapeXml(
                """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocolInfo">$escapedUri</res></item></DIDL-Lite>"""
            )

            val setUriArgs = """
                <InstanceID>0</InstanceID>
                <CurrentURI>$escapedUri</CurrentURI>
                <CurrentURIMetaData>$didlLiteMetadata</CurrentURIMetaData>
            """.trimIndent()

            val setResult = sendSoapAction(controlUrl, "SetAVTransportURI", setUriArgs)
            if (setResult.isFailure) {
                // Réessayer sans métadonnées DIDL-Lite si la Xbox préfère CurrentURIMetaData vide
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

            // 3. SOAP XML Play
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

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
