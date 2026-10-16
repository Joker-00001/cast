package com.xboxvideocaster.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class VideoStreamSniffer(private val context: Context) {

    interface Callback {
        fun onStreamFound(streamUrl: String, referer: String)
        fun onError(message: String)
    }

    private var hiddenWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasDetectedStream = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val VIDEO_PATTERNS = listOf(
            ".m3u8",
            ".mp4",
            "/master.m3u8",
            "/playlist.m3u8",
            "/index.m3u8",
            "urlset/master",
            "mime=video"
        )

        val AD_KEYWORDS = listOf(
            "popads", "popcash", "adcash", "acscdn", "doubleclick", "googlesyndication",
            "adnxs", "bidswitch", "trafficjunky", "propellerads", "exoclick",
            "adsterra", "yllix", "hilltopads", "clickadu", "onclickmega",
            "histats", "llvpn", "kingdomparlor", "korbo.fr", "eneba.com",
            "yandex.ru/metrika", "eblock.php", "a-zzz.com", "btag.min.js",
            "tsyndicate", "outbrain", "taboola", "mgid", "revcontent",
            "admaven", "galaksion", "mondiad", "pushground", "richads",
            "evadav", "rollerads", "bet365", "1xbet", "stake.com",
            "casino", "chaturbate", "stripchat", "livejasmin", "jerkmate",
            "bongacams", "myfreecams", "cam4", "brazzers", "pornhub",
            "xvideos", "xnxx", "onlyfans", "aliexpr", "temu.com",
            "shein.com", "opera.com/download", "vpn", "clearclean",
            "adrecover", "adsco.re", "realsrv", " twinrdsyn", "onmarshtompor",
            "alwingulla", "augresearch", "popunder", "poperblocker",
            "/assets/jquery/ev-cus", "deskademeta.php", "cdn.vidmoly.me/vj"
        )

        val ALLOWED_NAVIGATION_DOMAINS = listOf(
            "anime-sama.", "fs26.lol", "french-stream.", "french-manga.",
            "ansembed.", "vidmoly.", "sendvid.", "sibnet.", "minochinos.",
            "morencius.", "embed4me.", "uqload.", "dood", "voe.", "luluvdo.",
            "vidoza.", "upstream.", "streamtape.", "filemoon.", "wish",
            "youtube.com", "youtu.be", "google.com"
        )

        fun isAdUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return AD_KEYWORDS.any { lower.contains(it) }
        }

        fun isNavigationAllowed(targetUrl: String): Boolean {
            val lower = targetUrl.lowercase(Locale.ROOT)
            if (isAdUrl(lower)) return false
            return ALLOWED_NAVIGATION_DOMAINS.any { lower.contains(it) }
        }

        fun isVideoStreamUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".gif") ||
                lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") ||
                lower.contains("slides?")
            ) {
                return false
            }
            return VIDEO_PATTERNS.any { lower.contains(it) }
        }
    }

    /**
     * Analyse intelligente directe via HTTP (Anime-Sama episodes.js -> Embed -> .m3u8/.mp4)
     * Puis fallback WebView avec auto-trigger JWPlayer si nécessaire
     */
    suspend fun extractFromUrlSmart(url: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            // 1. Déjà un flux direct ?
            if (isVideoStreamUrl(url)) {
                return@withContext Result.success(Pair(url, url))
            }

            // 2. Est-ce une page de catalogue / saison Anime-Sama ?
            if (url.contains("anime-sama", ignoreCase = true)) {
                val baseDir = if (url.endsWith("/")) url else "${url.substringBeforeLast("/")}/"
                val episodesJsUrl = "${baseDir}episodes.js"
                val req = Request.Builder()
                    .url(episodesJsUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", url)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val jsContent = resp.body?.string() ?: ""
                        val embedUrls = parseEmbedUrlsFromEpisodesJs(jsContent)
                        for (embedUrl in embedUrls) {
                            val extracted = tryExtractDirectStreamFromEmbedHtml(embedUrl, url)
                            if (extracted != null) {
                                return@withContext Result.success(extracted)
                            }
                        }
                    }
                }
            }

            // 3. Est-ce directement une page de lecteur (ansembed, vidmoly, sendvid, minochinos, sibnet...) ?
            val directFromEmbed = tryExtractDirectStreamFromEmbedHtml(url, "https://anime-sama.to/")
            if (directFromEmbed != null) {
                return@withContext Result.success(directFromEmbed)
            }

            Result.failure(Exception("Passage à l'extraction dynamique WebView"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseEmbedUrlsFromEpisodesJs(js: String): List<String> {
        val result = mutableListOf<String>()
        // Privilégier ansembed / vidmoly / sendvid / sibnet qui ont des liens directs clairs
        val matcher = Pattern.compile("['\"](https?://[^'\"]+)['\"]").matcher(js)
        val allUrls = mutableListOf<String>()
        while (matcher.find()) {
            val u = matcher.group(1) ?: continue
            allUrls.add(u)
        }
        // Trier pour tester ansembed / vidmoly / sendvid en priorité (Episode 1 de chaque lecteur)
        val preferred = allUrls.filter {
            it.contains("ansembed") || it.contains("vidmoly") || it.contains("sendvid") || it.contains("sibnet")
        }
        result.addAll(preferred.take(4))
        result.addAll(allUrls.take(4))
        return result.distinct()
    }

    /**
     * Télécharge le HTML d'un lecteur embed et extrait le lien .m3u8 ou .mp4 (ex: sources: [{ file: '...' }])
     */
    fun tryExtractDirectStreamFromEmbedHtml(embedUrl: String, referer: String): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null

                // Chercher une URL .m3u8 ou .mp4 explicite dans le code source
                val m3u8Pattern = Pattern.compile("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)
                val m3u8Matcher = m3u8Pattern.matcher(html)
                if (m3u8Matcher.find()) {
                    val found = m3u8Matcher.group(1)
                    if (!found.isNullOrBlank()) return Pair(found, embedUrl)
                }

                val mp4Pattern = Pattern.compile("""['"](https?://[^'"]+\.mp4[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)
                val mp4Matcher = mp4Pattern.matcher(html)
                if (mp4Matcher.find()) {
                    val found = mp4Matcher.group(1)
                    if (!found.isNullOrBlank()) return Pair(found, embedUrl)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun extractStreamViaWebView(pageUrl: String, callback: Callback) {
        mainHandler.post {
            hasDetectedStream = false
            cleanup()

            hiddenWebView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = USER_AGENT

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onUrlFound(streamUrl: String, ref: String) {
                        if (!hasDetectedStream && streamUrl.startsWith("http")) {
                            hasDetectedStream = true
                            mainHandler.post {
                                callback.onStreamFound(streamUrl, ref.ifBlank { pageUrl })
                                cleanup()
                            }
                        }
                    }
                }, "AndroidSniffer")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null

                        if (isAdUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                        }

                        if (!hasDetectedStream && isVideoStreamUrl(reqUrl)) {
                            hasDetectedStream = true
                            val ref = request.requestHeaders["Referer"] ?: pageUrl
                            mainHandler.post {
                                callback.onStreamFound(reqUrl, ref)
                                cleanup()
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Script qui inspecte JWPlayer, les balises <video> et les iframes d'Anime-Sama
                        val jsInspect = """
                            (function() {
                                try {
                                    // 1. Si on est sur Anime-Sama et qu'il y a un iframe #playerDF, aller dedans
                                    var iframe = document.getElementById('playerDF') || document.querySelector('iframe[src*="embed"], iframe[src*="vidmoly"], iframe[src*="ansembed"], iframe[src*="sendvid"]');
                                    if (iframe && iframe.src && iframe.src.startsWith('http') && window.location.href.indexOf('anime-sama') !== -1) {
                                        window.location.href = iframe.src;
                                        return;
                                    }
                                    // 2. Si jwplayer est présent sur la page
                                    if (typeof jwplayer === 'function') {
                                        var p = jwplayer();
                                        if (p && typeof p.getPlaylistItem === 'function') {
                                            var item = p.getPlaylistItem();
                                            if (item && item.file) {
                                                AndroidSniffer.onUrlFound(item.file, window.location.href);
                                                return;
                                            }
                                        }
                                        if (p && typeof p.play === 'function') {
                                            p.play();
                                        }
                                    }
                                    // 3. Balises <video>
                                    var vids = document.querySelectorAll('video, source');
                                    for (var i = 0; i < vids.length; i++) {
                                        var s = vids[i].src || vids[i].currentSrc;
                                        if (s && s.startsWith('http')) {
                                            AndroidSniffer.onUrlFound(s, window.location.href);
                                            return;
                                        }
                                    }
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsInspect, null)
                    }
                }

                loadUrl(pageUrl)

                mainHandler.postDelayed({
                    if (!hasDetectedStream) {
                        callback.onError("Aucun flux vidéo détecté. Utilisez l'onglet Navigateur intégré pour choisir votre épisode !")
                        cleanup()
                    }
                }, 15000)
            }
        }
    }

    fun cleanup() {
        mainHandler.post {
            hiddenWebView?.stopLoading()
            hiddenWebView?.destroy()
            hiddenWebView = null
        }
    }
}
