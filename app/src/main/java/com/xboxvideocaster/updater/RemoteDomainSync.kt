package com.xboxvideocaster.updater

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class RemoteDomainSync(private val context: Context, private val prefs: SharedPreferences) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val KEY_ANIME_SAMA_URL = "pref_dynamic_anime_sama_url"
        const val KEY_FRENCH_STREAM_URL = "pref_dynamic_french_stream_url"
        const val KEY_FRENCH_MANGA_URL = "pref_dynamic_french_manga_url"

        const val DEFAULT_ANIME_SAMA_HOME = "https://anime-sama.to/"
        const val DEFAULT_FRENCH_STREAM_HOME = "https://fs26.lol/"
        const val DEFAULT_FRENCH_MANGA_HOME = "https://w16.french-manga.net/"

        // Fichier distant sur le dépôt GitHub pour bascule d'urgence sans mise à jour APK
        private const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/Joker-00001/cast/main/domains.json"
    }

    /**
     * Retourne l'URL active d'Anime-Sama (issue du suivi dynamique, de la config GitHub ou par défaut)
     */
    fun getAnimeSamaUrl(): String {
        val saved = prefs.getString(KEY_ANIME_SAMA_URL, null)
        return if (!saved.isNullOrBlank() && saved.startsWith("http")) {
            ensureTrailingSlash(saved)
        } else {
            DEFAULT_ANIME_SAMA_HOME
        }
    }

    /**
     * Retourne l'URL active de French Stream (issue du suivi dynamique, de la config GitHub ou par défaut)
     */
    fun getFrenchStreamUrl(): String {
        val saved = prefs.getString(KEY_FRENCH_STREAM_URL, null)
        return if (!saved.isNullOrBlank() && saved.startsWith("http")) {
            ensureTrailingSlash(saved)
        } else {
            DEFAULT_FRENCH_STREAM_HOME
        }
    }

    /**
     * Retourne l'URL active de French-Manga (issue du suivi dynamique, de la config GitHub ou par défaut)
     */
    fun getFrenchMangaUrl(): String {
        val saved = prefs.getString(KEY_FRENCH_MANGA_URL, null)
        return if (!saved.isNullOrBlank() && saved.startsWith("http")) {
            ensureTrailingSlash(saved)
        } else {
            DEFAULT_FRENCH_MANGA_HOME
        }
    }

    /**
     * Récupère en arrière-plan la liste des domaines officiels à jour depuis GitHub.
     * Si l'ancien domaine est mort ou bloqué par les FAI (DNS), l'application bascule automatiquement.
     */
    suspend fun syncDomainsFromRemote() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .header("User-Agent", "XboxVideoCaster-DomainSync")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val body = resp.body?.string() ?: return@withContext
                val json = JSONObject(body)

                val asUrl = json.optString("anime_sama", "").trim()
                val fsUrl = json.optString("french_stream", "").trim()
                val fmUrl = json.optString("french_manga", "").trim()

                val editor = prefs.edit()
                var updated = false
                if (asUrl.startsWith("http")) {
                    editor.putString(KEY_ANIME_SAMA_URL, ensureTrailingSlash(asUrl))
                    updated = true
                }
                if (fsUrl.startsWith("http")) {
                    editor.putString(KEY_FRENCH_STREAM_URL, ensureTrailingSlash(fsUrl))
                    updated = true
                }
                if (fmUrl.startsWith("http")) {
                    editor.putString(KEY_FRENCH_MANGA_URL, ensureTrailingSlash(fmUrl))
                    updated = true
                }
                if (updated) {
                    editor.apply()
                }
            }
        } catch (_: Exception) {
            // Pas de réseau ou GitHub temporairement inaccessible : on garde les domaines locaux enregistrés
        }
    }

    /**
     * Analyse toute URL visitée dans le navigateur intégré.
     * Dès qu'un site effectue une redirection HTTP 301/302 ou JS (ex: fs26.lol -> fs27.lol, w16.french-manga.net -> w17.french-manga.net, etc.),
     * l'application mémorise immédiatement le nouveau domaine valide.
     */
    fun checkAndSaveVisitedUrl(url: String?) {
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return
        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return
            val scheme = uri.scheme ?: "https"
            val rootUrl = "$scheme://$host/"

            if (host.contains("anime-sama.")) {
                val current = getAnimeSamaUrl()
                if (current != rootUrl) {
                    prefs.edit().putString(KEY_ANIME_SAMA_URL, rootUrl).apply()
                }
            } else if (Regex("(?i)fs\\d+\\.").containsMatchIn(host) || host.contains("french-stream.") || host.contains("fstream.")) {
                val current = getFrenchStreamUrl()
                if (current != rootUrl) {
                    prefs.edit().putString(KEY_FRENCH_STREAM_URL, rootUrl).apply()
                }
            } else if (host.contains("french-manga.") || Regex("(?i)w\\d+\\.french-manga\\.").containsMatchIn(host)) {
                val current = getFrenchMangaUrl()
                if (current != rootUrl) {
                    prefs.edit().putString(KEY_FRENCH_MANGA_URL, rootUrl).apply()
                }
            }
        } catch (_: Exception) {}
    }

    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
}
