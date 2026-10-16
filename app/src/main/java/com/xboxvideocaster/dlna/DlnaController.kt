package com.xboxvideocaster.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DlnaController(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val AV_TRANSPORT_CONTROL_PATH = "/upnp/control/rendertransport1"
        private const val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\""
    }

    /**
     * Envoie la commande SetAVTransportURI puis Play à la console Xbox via SOAP / UPnP AVTransport
     */
    suspend fun playMediaOnXbox(
        ip: String,
        port: Int = 10243,
        videoUri: String,
        title: String = "Xbox Video Stream"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = "http://$ip:$port$AV_TRANSPORT_CONTROL_PATH"
            val escapedUri = escapeXml(videoUri)
            val escapedTitle = escapeXml(title)

            // 1. SOAP XML SetAVTransportURI
            val didlLiteMetadata = """&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res&gt;$escapedUri&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"""

            val setUriXml = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>$escapedUri</CurrentURI>
            <CurrentURIMetaData>$didlLiteMetadata</CurrentURIMetaData>
        </u:SetAVTransportURI>
    </s:Body>
</s:Envelope>""".trimIndent()

            val setUriRequest = Request.Builder()
                .url(controlUrl)
                .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                .post(setUriXml.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(setUriRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        Exception("Erreur SetAVTransportURI (Code ${response.code}): $body")
                    )
                }
            }

            // 2. SOAP XML Play
            val playXml = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <Speed>1</Speed>
        </u:Play>
    </s:Body>
</s:Envelope>""".trimIndent()

            val playRequest = Request.Builder()
                .url(controlUrl)
                .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                .post(playXml.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(playRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        Exception("Erreur Play (Code ${response.code}): $body")
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Commande Stop pour interrompre la lecture
     */
    suspend fun stopMedia(ip: String, port: Int = 10243): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = "http://$ip:$port$AV_TRANSPORT_CONTROL_PATH"
            val stopXml = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
    <s:Body>
        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
        </u:Stop>
    </s:Body>
</s:Envelope>""".trimIndent()

            val stopRequest = Request.Builder()
                .url(controlUrl)
                .addHeader("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                .post(stopXml.toRequestBody(SOAP_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(stopRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        Exception("Erreur Stop (Code ${response.code}): $body")
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
