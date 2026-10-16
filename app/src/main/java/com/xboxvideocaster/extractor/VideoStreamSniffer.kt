package com.xboxvideocaster.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

class VideoStreamSniffer(private val context: Context) {

    interface Callback {
        fun onStreamFound(streamUrl: String)
        fun onError(message: String)
    }

    private var hiddenWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasDetectedStream = false

    // Liste des extensions et patterns de flux multimédia
    private val videoPatterns = listOf(
        ".mp4",
        ".m3u8",
        ".m3u",
        ".webm",
        ".mpd",
        "/playlist.m3u8",
        "/master.m3u8",
        "mime=video"
    )

    // Domaines / mots-clés typiques de régies publicitaires et redirections indésirables
    private val adBlockKeywords = listOf(
        "popads", "popcash", "adcash", "doubleclick", "googlesyndication",
        "adnxs", "bidswitch", "trafficjunky", "propellerads", "exoclick",
        "adsterra", "yllix", "hilltopads", "clickadu", "onclickmega",
        "histats", "analytics", "tracking", "tracker", "banner", "cpmstar"
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun extractStream(pageUrl: String, callback: Callback) {
        mainHandler.post {
            hasDetectedStream = false
            cleanup()

            // Si c'est déjà un lien direct vers un fichier vidéo
            val lower = pageUrl.lowercase(Locale.ROOT)
            if (videoPatterns.any { lower.contains(it) }) {
                callback.onStreamFound(pageUrl)
                return@post
            }

            hiddenWebView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        val reqLower = reqUrl.lowercase(Locale.ROOT)

                        // 1. Bloqueur de publicités et de trackers
                        for (adKey in adBlockKeywords) {
                            if (reqLower.contains(adKey)) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                        }

                        // 2. Détection du flux vidéo direct
                        if (!hasDetectedStream) {
                            val isVideoStream = videoPatterns.any { pattern ->
                                reqLower.contains(pattern) && !reqLower.contains("favicon")
                            }

                            if (isVideoStream) {
                                hasDetectedStream = true
                                mainHandler.post {
                                    callback.onStreamFound(reqUrl)
                                    cleanup()
                                }
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Injection JS pour forcer la lecture et extraire les balises <video> ou <iframe>
                        val jsExtract = """
                            (function() {
                                var videos = document.getElementsByTagName('video');
                                for (var i = 0; i < videos.length; i++) {
                                    var src = videos[i].src || videos[i].currentSrc;
                                    if (src && src.startsWith('http')) {
                                        console.log('FOUND_VIDEO:' + src);
                                    }
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsExtract, null)
                    }
                }

                loadUrl(pageUrl)

                // Timeout de recherche au bout de 20 secondes
                mainHandler.postDelayed({
                    if (!hasDetectedStream) {
                        callback.onError("Aucun flux vidéo détecté sur la page après expiration du délai.")
                        cleanup()
                    }
                }, 20000)
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
