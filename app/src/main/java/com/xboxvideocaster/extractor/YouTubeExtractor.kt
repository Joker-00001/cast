package com.xboxvideocaster.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    // Liste d'instances Invidious / Piped publiques fiables pour la résolution rapide du stream
    private val invidiousInstances = listOf(
        "https://inv.tux.pizza",
        "https://invidious.nerdvpn.de",
        "https://invidious.projectsegfau.lt",
        "https://yewtu.be"
    )

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    fun extractVideoId(url: String): String? {
        val pattern = "(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/|.*[?&]v=)|youtu\\.be\\/)([^\"&?\\/\\s]{11})"
        val compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        val matcher = compiledPattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else null
    }

    suspend fun resolveDirectStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        for (instance in invidiousInstances) {
            try {
                val apiUrl = "$instance/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "XboxVideoCaster/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)

                        // 1. Privilégier le flux HLS s'il existe (.m3u8)
                        if (json.has("hlsUrl") && !json.isNull("hlsUrl")) {
                            val hls = json.getString("hlsUrl")
                            if (hls.isNotBlank()) return@withContext Result.success(hls)
                        }

                        // 2. Sinon formatStreams (stream combiné audio+vidéo mp4)
                        if (json.has("formatStreams")) {
                            val formats = json.getJSONArray("formatStreams")
                            for (i in 0 until formats.length()) {
                                val item = formats.getJSONObject(i)
                                val directUrl = item.optString("url")
                                val container = item.optString("container")
                                if (directUrl.isNotBlank() && (container.equals("mp4", ignoreCase = true) || directUrl.contains(".mp4"))) {
                                    return@withContext Result.success(directUrl)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Essayer l'instance suivante
            }
        }
        Result.failure(Exception("Impossible d'extraire le flux direct YouTube."))
    }
}
