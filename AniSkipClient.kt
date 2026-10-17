package com.xboxvideocaster.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client officiel AniSkip + Jikan (MyAnimeList) permettant de récupérer au dixième de seconde près
 * le début et la fin du générique (Opening / Intro) de n'importe quel épisode d'anime !
 */
class AniSkipClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    data class IntroWindow(
        val startSec: Int,
        val endSec: Int,
        val animeTitle: String,
        val episodeNumber: Int
    )

    private val malIdCache = ConcurrentHashMap<String, Int>()

    /**
     * Recherche les timestamps exacts de l'intro (Opening) pour un anime et un numéro d'épisode.
     * Retourne null si l'anime ou l'épisode n'est pas répertorié sur AniSkip.
     */
    suspend fun fetchExactIntroTimestamps(
        rawAnimeTitle: String,
        episodeNumber: Int,
        episodeDurationSec: Int = 0
    ): IntroWindow? = withContext(Dispatchers.IO) {
        try {
            val cleanQuery = cleanAnimeTitle(rawAnimeTitle)
            if (cleanQuery.length < 2 || episodeNumber <= 0) return@withContext null

            val malId = resolveMalId(cleanQuery) ?: return@withContext null

            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                "?types[]=op&types[]=mixed-op&episodeLength=0"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", VideoStreamSniffer.USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (!json.optBoolean("found", false)) return@withContext null

                val results = json.optJSONArray("results") ?: return@withContext null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val skipType = item.optString("skipType", "")
                    if (skipType == "op" || skipType == "mixed-op") {
                        val interval = item.optJSONObject("interval") ?: continue
                        val startSec = interval.optDouble("startTime", -1.0).toInt()
                        val endSec = interval.optDouble("endTime", -1.0).toInt()
                        if (startSec >= 0 && endSec > startSec + 15) {
                            return@withContext IntroWindow(
                                startSec = startSec,
                                endSec = endSec,
                                animeTitle = cleanQuery,
                                episodeNumber = episodeNumber
                            )
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMalId(cleanTitle: String): Int? {
        val cacheKey = cleanTitle.lowercase()
        malIdCache[cacheKey]?.let { return it }

        return try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val jikanUrl = "https://api.jikan.moe/v4/anime?q=$encoded&limit=1"
            val req = Request.Builder()
                .url(jikanUrl)
                .header("User-Agent", VideoStreamSniffer.USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return null
                if (data.length() == 0) return null
                val first = data.optJSONObject(0) ?: return null
                val malId = first.optInt("mal_id", -1)
                if (malId > 0) {
                    malIdCache[cacheKey] = malId
                    malId
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extrait un nom d'anime propre à partir d'une URL Anime-Sama ou du titre de la page.
     * Exemple : "https://anime-sama.to/catalogue/solo-leveling/saison2/vostfr/" -> "solo leveling season 2"
     */
    fun extractAnimeQueryAndSeason(pageUrl: String, pageTitle: String): String {
        try {
            if (pageUrl.contains("/catalogue/", ignoreCase = true)) {
                val afterCat = pageUrl.substringAfter("/catalogue/").trim('/')
                val parts = afterCat.split("/")
                val slug = parts.getOrNull(0)?.replace("-", " ")?.trim() ?: ""
                val seasonPart = parts.getOrNull(1)?.lowercase() ?: ""
                val seasonNum = Regex("saison\\s*(\\d+)").find(seasonPart)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (slug.isNotBlank()) {
                    return if (seasonNum != null && seasonNum > 1) {
                        "$slug season $seasonNum"
                    } else {
                        slug
                    }
                }
            }
        } catch (_: Exception) {
        }
        return cleanAnimeTitle(pageTitle)
    }

    private fun cleanAnimeTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)(anime-sama|vostfr|vf|streaming|épisode\\s*\\d+|episode\\s*\\d+|saison\\s*\\d+)"), " ")
            .replace(Regex("[|\\-–—•:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
