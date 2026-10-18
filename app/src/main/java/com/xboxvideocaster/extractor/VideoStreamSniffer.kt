package com.xboxvideocaster.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
            "adrecover", "adsco.re", "realsrv", "twinrdsyn", "onmarshtompor",
            "alwingulla", "augresearch", "popunder", "poperblocker",
            "/assets/jquery/ev-cus", "deskademeta.php", "cdn.vidmoly.me/vj"
        )

        val ALLOWED_NAVIGATION_DOMAINS = listOf(
            "anime-sama.", "fs26.", "french-stream.", "fstream.", "french-manga.",
            "fsvid.", "vidzy.", "trakx.", "vidara.", "multiup.", "darki",
            "ansembed.", "vidmoly.", "sendvid.", "sibnet.", "minochinos.",
            "morencius.", "embed4me.", "uqload.", "dood", "voe.", "luluvdo.",
            "vidoza.", "upstream.", "streamtape.", "filemoon.", "wish",
            "smoothpre.", "movearnpre.", "vk.com", "vkvideo.ru", "myvi.", "yourupload.",
            "youtube.com", "youtu.be", "google.com"
        )

        fun isAdUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return AD_KEYWORDS.any { lower.contains(it) }
        }

        fun isNavigationAllowed(targetUrl: String): Boolean {
            val lower = targetUrl.lowercase(Locale.ROOT)
            if (isAdUrl(lower)) return false
            if (lower.contains("anime-sama.") || lower.contains("french-stream.") || lower.contains("fstream.") || lower.contains("french-manga.")) {
                return true
            }
            if (Regex("(?i)https?://[^/]*fs\\d+\\.").containsMatchIn(targetUrl)) {
                return true
            }
            return ALLOWED_NAVIGATION_DOMAINS.any { lower.contains(it) }
        }

        fun isVideoStreamUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            // Exclure impérativement la fausse vidéo "troll" de 18s de FrenchStream (/troll/master.m3u8) !
            if (lower.contains("/troll/") ||
                lower.contains(".jpg") || lower.contains(".png") || lower.contains(".gif") ||
                lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") ||
                lower.contains("slides?") || lower.contains(" iframes-") || lower.contains("/iframes-")
            ) {
                return false
            }
            return VIDEO_PATTERNS.any { lower.contains(it) }
        }

        /**
         * Décode les flux vidéo obfusqués par XOR + Base64 inversé (utilisés par fsvid.lol et vidzy.cc sur French Stream).
         * Comme l'URL décodée commence toujours par "https://", l'octet clé initial k0 vaut (reversed[0] xor 'h').
         */
        fun decodeXorBase64Stream(b64: String): String? {
            return try {
                val raw = Base64.decode(b64.trim(), Base64.DEFAULT)
                if (raw.size < 20) return null
                val reversed = raw.reversedArray()
                val k0 = (reversed[0].toInt() and 0xFF) xor 'h'.code
                val sb = StringBuilder(reversed.size)
                for (i in reversed.indices) {
                    val kk = (k0 + i * 89) and 0xFF
                    val ch = ((reversed[i].toInt() and 0xFF) xor kk).toChar()
                    sb.append(ch)
                }
                val decoded = sb.toString()
                if ((decoded.startsWith("https://") || decoded.startsWith("http://")) &&
                    isVideoStreamUrl(decoded)
                ) {
                    decoded
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Décompresse les scripts obfusqués avec eval(function(p,a,c,k,e,d){...}) (fsvid.lol, vidmoly, uqload...)
         */
        fun unpackDeanEdwardsIfNeeded(html: String): String {
            val sb = StringBuilder(html)
            try {
                val regex = Pattern.compile(
                    """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)""",
                    Pattern.DOTALL
                )
                val matcher = regex.matcher(html)
                while (matcher.find()) {
                    var payload = matcher.group(1) ?: continue
                    val radix = matcher.group(2)?.toIntOrNull() ?: 36
                    val count = matcher.group(3)?.toIntOrNull() ?: 0
                    val symtab = (matcher.group(4) ?: "").split("|")
                    if (radix in 2..36 && count > 0 && symtab.isNotEmpty()) {
                        val wordRegex = Regex("""\b[0-9a-zA-Z]+\b""")
                        payload = wordRegex.replace(payload) { matchResult ->
                            val token = matchResult.value
                            val idx = token.toIntOrNull(radix)
                            if (idx != null && idx in symtab.indices && symtab[idx].isNotEmpty()) {
                                symtab[idx]
                            } else {
                                token
                            }
                        }
                        sb.append("\n").append(payload)
                    }
                }
            } catch (_: Exception) {
            }
            return sb.toString()
        }
    }

    /**
     * Analyse intelligente directe via HTTP
     */
    suspend fun extractFromUrlSmart(url: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            // 1. Déjà un flux direct ?
            if (isVideoStreamUrl(url)) {
                return@withContext Result.success(Pair(url, url))
            }

            // 2. Est-ce une page d'épisode Anime-Sama (anime-sama.to) ?
            if (url.contains("anime-sama.")) {
                val asExtracted = tryExtractFromAnimeSamaPage(url)
                if (asExtracted != null) {
                    return@withContext Result.success(asExtracted)
                }
            }

            // 3. Est-ce une page de film/série French Stream ou French Manga ?
            if (url.contains("french-stream.") || url.contains("fstream.") || Regex("(?i)fs\\d+\\.").containsMatchIn(url) ||
                url.contains("french-manga.") || Regex("(?i)w\\d+\\.french-manga\\.").containsMatchIn(url)) {
                val fsExtracted = tryExtractFromFrenchStreamPage(url)
                if (fsExtracted != null) {
                    return@withContext Result.success(fsExtracted)
                }
            }

            // 4. Est-ce directement une page de lecteur (fsvid, vidzy, ansembed, vidmoly, sendvid, sibnet...) ?
            val directFromEmbed = tryExtractDirectStreamFromEmbedHtml(url, url)
            if (directFromEmbed != null) {
                return@withContext Result.success(directFromEmbed)
            }

            Result.failure(Exception("Passage à l'extraction dynamique WebView"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sur Anime-Sama, récupère episodes.js et extrait en priorité absolue le flux HLS (ansembed.net / vidmoly)
     * au lieu du Lecteur 1 par défaut (sendvid/sibnet en .mp4 bridé à 250k qui faisait clignoter le menu TV).
     */
    private fun tryExtractFromAnimeSamaPage(pageUrl: String): Pair<String, String>? {
        return try {
            val cleanBase = pageUrl.substringBefore("?").substringBefore("#").trimEnd('/')
            val episodesJsUrl = "$cleanBase/episodes.js"
            val req = Request.Builder()
                .url(episodesJsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageUrl)
                .build()

            val jsContent = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val ansembedMatcher = Pattern.compile("""https?://ansembed\.net/embed-[^'"\s]+""").matcher(jsContent)
            if (ansembedMatcher.find()) {
                val embedUrl = ansembedMatcher.group(0)
                if (!embedUrl.isNullOrBlank()) {
                    val extracted = tryExtractDirectStreamFromEmbedHtml(embedUrl, pageUrl)
                    if (extracted != null) return extracted
                }
            }

            val vidmolyMatcher = Pattern.compile("""https?://vidmoly\.[a-z]+/embed-[^'"\s]+""").matcher(jsContent)
            if (vidmolyMatcher.find()) {
                val embedUrl = vidmolyMatcher.group(0)?.replace("vidmoly.to", "vidmoly.biz")?.replace("vidmoly.net", "vidmoly.biz")
                if (!embedUrl.isNullOrBlank()) {
                    val extracted = tryExtractDirectStreamFromEmbedHtml(embedUrl, pageUrl)
                    if (extracted != null) return extracted
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Résout l'épisode suivant sur Anime-Sama de façon 100% autonome en tâche de fond (hors WebView),
     * permettant de pré-charger l'épisode même si le téléphone a son écran éteint en veille !
     */
    fun resolveAnimeSamaNextEpisodeStream(pageUrl: String, nextEpisodeIndex: Int): Pair<String, String>? {
        return try {
            val cleanBase = pageUrl.substringBefore("?").substringBefore("#").trimEnd('/')
            val episodesJsUrl = "$cleanBase/episodes.js"
            val req = Request.Builder()
                .url(episodesJsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageUrl)
                .build()

            val jsContent = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val pattern = Pattern.compile("""var\s+(eps\d+)\s*=\s*\[(.*?)\];""", Pattern.DOTALL)
            val matcher = pattern.matcher(jsContent)
            val allReaderLists = mutableListOf<List<String>>()

            while (matcher.find()) {
                val arrayContent = matcher.group(2) ?: continue
                val strMatcher = Pattern.compile("""['"](https?://[^'"]+)['"]""").matcher(arrayContent)
                val list = mutableListOf<String>()
                while (strMatcher.find()) {
                    strMatcher.group(1)?.let { list.add(it) }
                }
                if (list.isNotEmpty()) {
                    allReaderLists.add(list)
                }
            }

            if (allReaderLists.isEmpty()) return null

            var chosenEmbed: String? = null

            // Priorité 1 : ansembed.net (HLS direct rapide)
            for (readerList in allReaderLists) {
                if (nextEpisodeIndex in readerList.indices) {
                    val candidate = readerList[nextEpisodeIndex]
                    if (candidate.contains("ansembed.net")) {
                        chosenEmbed = candidate
                        break
                    }
                }
            }

            // Priorité 2 : vidmoly
            if (chosenEmbed == null) {
                for (readerList in allReaderLists) {
                    if (nextEpisodeIndex in readerList.indices) {
                        val candidate = readerList[nextEpisodeIndex]
                        if (candidate.contains("vidmoly")) {
                            chosenEmbed = candidate.replace("vidmoly.to", "vidmoly.biz").replace("vidmoly.net", "vidmoly.biz")
                            break
                        }
                    }
                }
            }

            // Priorité 3 : tout lecteur disponible pour cet index
            if (chosenEmbed == null) {
                for (readerList in allReaderLists) {
                    if (nextEpisodeIndex in readerList.indices) {
                        chosenEmbed = readerList[nextEpisodeIndex]
                        break
                    }
                }
            }

            if (!chosenEmbed.isNullOrBlank()) {
                tryExtractDirectStreamFromEmbedHtml(chosenEmbed, pageUrl)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryExtractFromFrenchStreamPage(pageUrl: String): Pair<String, String>? {
        return try {
            val pageOrigin = try {
                val uri = java.net.URI(pageUrl)
                "${uri.scheme}://${uri.host}/"
            } catch (_: Exception) {
                if (pageUrl.contains("french-manga")) "https://w16.french-manga.net/" else "https://fs26.lol/"
            }

            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageOrigin)
                .build()

            val html = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            // Chercher l'URL d'embed du lecteur actif dans la page French Stream / French Manga (data-url-default ou player-frame / main-player)
            val candidates = mutableListOf<String>()
            val iframeMatcher = Pattern.compile("""id=["'](?:player-frame|main-player)["'][^>]*src=["'](https?://[^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(html)
            if (iframeMatcher.find()) {
                iframeMatcher.group(1)?.let { candidates.add(it) }
            }
            val dataUrlMatcher = Pattern.compile("""data-url-default=["'](https?://[^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(html)
            while (dataUrlMatcher.find()) {
                dataUrlMatcher.group(1)?.let { candidates.add(it) }
            }

            // Vérifier aussi film_api.php si newsid est présent
            val idMatcher = Pattern.compile("""newsid=(\d+)|/(\d+)-""").matcher(pageUrl)
            if (idMatcher.find()) {
                val newsId = idMatcher.group(1) ?: idMatcher.group(2)
                if (!newsId.isNullOrBlank()) {
                    val apiUrl = "${pageOrigin.trimEnd('/')}/engine/ajax/film_api.php?id=$newsId"
                    val apiReq = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", pageUrl)
                        .build()
                    httpClient.newCall(apiReq).execute().use { apiResp ->
                        if (apiResp.isSuccessful) {
                            val json = apiResp.body?.string() ?: ""
                            val embedMatcher = Pattern.compile("""https?:\\/\\/[^"']+""").matcher(json)
                            while (embedMatcher.find()) {
                                val u = embedMatcher.group(0)?.replace("\\/", "/") ?: continue
                                candidates.add(u)
                            }
                        }
                    }
                }
            }

            for (embedUrl in candidates.distinct()) {
                val stream = tryExtractDirectStreamFromEmbedHtml(embedUrl, pageUrl)
                if (stream != null) return stream
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Télécharge le HTML d'un lecteur embed et extrait le vrai lien .m3u8 ou .mp4
     * (décode aussi le chiffrement XOR+Base64 de fsvid.lol / vidzy.cc et ignore le faux flux /troll/).
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
                val rawHtml = resp.body?.string() ?: return null
                val fullText = unpackDeanEdwardsIfNeeded(rawHtml)

                // 1. Décodage prioritaire du flux chiffré XOR + Base64 (fsvid.lol / vidzy.cc sur French Stream)
                val xorB64Pattern = Pattern.compile("""\}\)\(["']([A-Za-z0-9+/=]{40,})["']\)""")
                val xorMatcher = xorB64Pattern.matcher(fullText)
                while (xorMatcher.find()) {
                    val b64 = xorMatcher.group(1) ?: continue
                    val decoded = decodeXorBase64Stream(b64)
                    if (!decoded.isNullOrBlank() && isVideoStreamUrl(decoded)) {
                        return Pair(decoded, embedUrl)
                    }
                }

                // 2. Chercher toutes les URLs .m3u8 explicites (en filtrant /troll/ et iframes-)
                val m3u8Pattern = Pattern.compile("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)
                val m3u8Matcher = m3u8Pattern.matcher(fullText)
                while (m3u8Matcher.find()) {
                    val found = m3u8Matcher.group(1)?.replace("\\/", "/")
                    if (!found.isNullOrBlank() && isVideoStreamUrl(found)) {
                        return Pair(found, embedUrl)
                    }
                }

                // 3. Chercher toutes les URLs .mp4 explicites
                val mp4Pattern = Pattern.compile("""['"](https?://[^'"]+\.mp4[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)
                val mp4Matcher = mp4Pattern.matcher(fullText)
                while (mp4Matcher.find()) {
                    val found = mp4Matcher.group(1)?.replace("\\/", "/")
                    if (!found.isNullOrBlank() && isVideoStreamUrl(found)) {
                        return Pair(found, embedUrl)
                    }
                }

                // 4. Cas spécifique Sibnet (chemin relatif /v/xxxxx.mp4)
                val sibnetPattern = Pattern.compile("""src\s*:\s*['"](/v/[^'"]+\.mp4[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)
                val sibnetMatcher = sibnetPattern.matcher(fullText)
                if (sibnetMatcher.find()) {
                    val rel = sibnetMatcher.group(1)
                    if (!rel.isNullOrBlank()) {
                        return Pair("https://video.sibnet.ru$rel", "https://video.sibnet.ru/")
                    }
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
                        if (!hasDetectedStream && streamUrl.startsWith("http") && isVideoStreamUrl(streamUrl)) {
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
                        val jsInspect = """
                            (function() {
                                try {
                                    var iframe = document.getElementById('playerDF') || document.getElementById('player-frame') || document.querySelector('iframe[src*="embed"], iframe[src*="vidmoly"], iframe[src*="ansembed"], iframe[src*="sendvid"], iframe[src*="fsvid"], iframe[src*="vidzy"]');
                                    if (iframe && iframe.src && iframe.src.startsWith('http') && (/(anime-sama|french-stream|fs\d+|fstream)/i.test(window.location.href))) {
                                        window.location.href = iframe.src;
                                        return;
                                    }
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
                                    if (typeof videojs === 'function') {
                                        var vp = videojs.getPlayers && Object.values(videojs.getPlayers())[0];
                                        if (vp && typeof vp.currentSrc === 'function') {
                                            var vsrc = vp.currentSrc();
                                            if (vsrc && vsrc.startsWith('http') && vsrc.indexOf('/troll/') === -1) {
                                                AndroidSniffer.onUrlFound(vsrc, window.location.href);
                                                return;
                                            }
                                        }
                                    }
                                    var vids = document.querySelectorAll('video, source');
                                    for (var i = 0; i < vids.length; i++) {
                                        var s = vids[i].src || vids[i].currentSrc;
                                        if (s && s.startsWith('http') && s.indexOf('/troll/') === -1) {
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
                        callback.onError("Aucun flux vidéo détecté. Appuyez une fois sur Play ▶ dans la page puis recliquez sur LANCER !")
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
