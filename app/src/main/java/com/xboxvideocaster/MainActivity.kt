package com.xboxvideocaster

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xboxvideocaster.databinding.ActivityMainBinding
import com.xboxvideocaster.dlna.DlnaController
import com.xboxvideocaster.dlna.LocalStreamProxy
import com.xboxvideocaster.extractor.VideoStreamSniffer
import com.xboxvideocaster.extractor.YouTubeExtractor
import com.xboxvideocaster.updater.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val dlnaController = DlnaController()
    private val youtubeExtractor = YouTubeExtractor()
    private lateinit var videoStreamSniffer: VideoStreamSniffer
    private lateinit var localStreamProxy: LocalStreamProxy
    private lateinit var appUpdater: AppUpdater

    @Volatile
    private var lastDetectedStreamUrl: String? = null

    @Volatile
    private var lastDetectedReferer: String = "https://anime-sama.to/"

    @Volatile
    private var currentIframeEmbedUrl: String? = null

    // État de la lecture en cours sur la Xbox pour la télécommande (-30s / Pause / +30s)
    private var activePreparedStream: LocalStreamProxy.PreparedStream? = null
    private var currentPlaybackSec: Int = 0
    private var isXboxPaused: Boolean = false
    private var isSeekingNow: Boolean = false
    private var positionTrackerJob: Job? = null

    companion object {
        private const val PREFS_NAME = "xbox_caster_prefs"
        private const val KEY_XBOX_IP = "key_xbox_ip"
        private const val KEY_LAST_PAGE = "key_last_page"
        private const val DEFAULT_XBOX_IP = "192.168.1.52"
        private const val ANIME_SAMA_HOME = "https://anime-sama.to/"
        private const val FRENCH_STREAM_HOME = "https://fs26.lol/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        videoStreamSniffer = VideoStreamSniffer(this)
        localStreamProxy = LocalStreamProxy(this)
        localStreamProxy.start()
        appUpdater = AppUpdater(this)

        setupIpPersistence()
        setupBuiltInBrowser()
        setupListeners()
        setupBackNavigation()
        checkAppUpdateAuto()

        if (!handleIncomingIntent(intent)) {
            showHomeHub()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun showHomeHub() {
        binding.layoutHomeHub.visibility = View.VISIBLE
        val savedPage = prefs.getString(KEY_LAST_PAGE, null)
        binding.btnResumeLastPage.visibility = if (savedPage.isNullOrBlank()) View.GONE else View.VISIBLE
        if (activePreparedStream == null) {
            updateStatus("Choisissez une plateforme ci-dessus", isLoading = false)
        }
    }

    private fun checkAppUpdateAuto() {
        lifecycleScope.launch {
            val currentVersionCode = getCurrentVersionCode()
            val release = appUpdater.checkForUpdate(currentVersionCode)
            if (release != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🚀 Mise à jour disponible (${release.tagName})")
                    .setMessage("Une nouvelle version de Xbox Video Caster est disponible. Voulez-vous la télécharger et l'installer automatiquement ?")
                    .setPositiveButton("Mettre à jour maintenant") { _, _ ->
                        startAutoUpdate(release.apkDownloadUrl)
                    }
                    .setNegativeButton("Plus tard", null)
                    .show()
            }
        }
    }

    private fun startAutoUpdate(apkUrl: String) {
        updateStatus("Téléchargement de la mise à jour (0%)...", isLoading = true)
        lifecycleScope.launch {
            val res = appUpdater.downloadAndInstallApk(apkUrl) { progress ->
                runOnUiThread {
                    updateStatus("Téléchargement de la mise à jour ($progress%)...", isLoading = true)
                }
            }
            res.onSuccess {
                updateStatus("Installation de la mise à jour lancée !", isLoading = false)
            }.onFailure { err ->
                updateStatus("Erreur mise à jour : ${err.localizedMessage}", isLoading = false)
                Toast.makeText(this@MainActivity, "Erreur MAJ : ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) {
            1
        }
    }

    private fun setupIpPersistence() {
        val savedIp = prefs.getString(KEY_XBOX_IP, DEFAULT_XBOX_IP)
        binding.etXboxIp.setText(savedIp)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBuiltInBrowser() {
        binding.webViewBrowser.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = VideoStreamSniffer.USER_AGENT
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStreamDetected(streamUrl: String, referer: String) {
                    if (streamUrl.startsWith("http") && VideoStreamSniffer.isVideoStreamUrl(streamUrl)) {
                        onNewVideoStreamFound(streamUrl, referer)
                    }
                }

                @JavascriptInterface
                fun onIframeDetected(iframeSrc: String, pageReferer: String) {
                    if (iframeSrc.startsWith("http") && iframeSrc != currentIframeEmbedUrl) {
                        currentIframeEmbedUrl = iframeSrc
                        lifecycleScope.launch(Dispatchers.IO) {
                            val extracted = videoStreamSniffer.tryExtractDirectStreamFromEmbedHtml(
                                iframeSrc,
                                pageReferer.ifBlank { "https://anime-sama.to/" }
                            )
                            if (extracted != null && VideoStreamSniffer.isVideoStreamUrl(extracted.first)) {
                                onNewVideoStreamFound(extracted.first, extracted.second)
                            }
                        }
                    }
                }
            }, "XboxBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    return false
                }
            }

            webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    if (!VideoStreamSniffer.isNavigationAllowed(target)) {
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null

                    if (VideoStreamSniffer.isAdUrl(reqUrl)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream("".toByteArray())
                        )
                    }

                    if (VideoStreamSniffer.isVideoStreamUrl(reqUrl)) {
                        val ref = request.requestHeaders["Referer"] ?: lastDetectedReferer
                        onNewVideoStreamFound(reqUrl, ref)
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) {
                        binding.etVideoUrl.setText(url)
                        prefs.edit().putString(KEY_LAST_PAGE, url).apply()
                    }
                    view?.evaluateJavascript("window.open = function(){ return null; };", null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectSmartSiteWatcher()
                }
            }
        }
    }

    private fun injectSmartSiteWatcher() {
        val js = """
            (function() {
                window.open = function() { return null; };
                if (window.__xboxWatcherInstalled) return;
                window.__xboxWatcherInstalled = true;

                var style = document.createElement('style');
                style.innerHTML = '.ad-container, #bannLeft, #bannRight, #bannBot, #squBot, #bandeau-pubs-nuit, #fss-tvload, .adsbygoogle, [id*="ad-"], [class*="ad-"], [id*="pub"], iframe[src*="ads"] { display: none !important; pointer-events: none !important; } .nav-menu > li, .st-tabs span, .short, .sect-t, .container2 { opacity: 1 !important; }';
                document.head.appendChild(style);

                setInterval(function() {
                    try {
                        window.open = function() { return null; };

                        // 1. Détruire les calques transparents invisibles de pub
                        var allDivs = document.querySelectorAll('div, a, aside');
                        for (var d = 0; d < allDivs.length; d++) {
                            var el = allDivs[d];
                            var st = window.getComputedStyle(el);
                            var z = parseInt(st.zIndex, 10) || 0;
                            if ((st.position === 'fixed' || st.position === 'absolute') && z > 99999 && el.id !== 'loginModal') {
                                el.remove();
                            }
                        }

                        // 2. Surveiller les iframes de lecteurs (Anime-Sama #playerDF et French Stream #player-frame)
                        var iframes = document.querySelectorAll('#playerDF, #player-frame, #main-player iframe, #seriePlayer iframe, .iframe-container iframe, iframe[src*="embed"], iframe[src*="ansembed"], iframe[src*="vidmoly"], iframe[src*="sendvid"], iframe[src*="minochinos"], iframe[src*="uqload"], iframe[src*="dood"], iframe[src*="voe"], iframe[src*="luluvdo"], iframe[src*="fsvid"], iframe[src*="vidzy"], iframe[src*="sibnet"]');
                        for (var k = 0; k < iframes.length; k++) {
                            var src = iframes[k].src || iframes[k].getAttribute('data-src');
                            if (src && src.startsWith('http') && src.indexOf('google') === -1) {
                                XboxBridge.onIframeDetected(src, window.location.href);
                            }
                        }

                        // 3. Surveiller les balises <video> directes (en ignorant le faux flux /troll/)
                        var vids = document.querySelectorAll('video, source');
                        for (var i = 0; i < vids.length; i++) {
                            var s = vids[i].src || vids[i].currentSrc;
                            if (s && s.startsWith('http') && s.indexOf('/troll/') === -1) {
                                XboxBridge.onStreamDetected(s, window.location.href);
                            }
                        }
                    } catch (e) {}
                }, 1000);
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(js, null)
    }

    /**
     * Met en pause la vidéo dans la WebView du téléphone pendant le Cast sur la Xbox
     * pour libérer 100% de la bande passante Wi-Fi et éviter les micro-freezes.
     */
    private fun pauseWebViewPlayback() {
        val jsPause = """
            (function() {
                try {
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        vids[i].pause();
                    }
                    if (typeof jwplayer === 'function') {
                        jwplayer().pause(true);
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsPause, null)
    }

    private fun onNewVideoStreamFound(streamUrl: String, referer: String) {
        if (!VideoStreamSniffer.isVideoStreamUrl(streamUrl)) return
        val isNewStream = (streamUrl != lastDetectedStreamUrl)
        lastDetectedStreamUrl = streamUrl
        lastDetectedReferer = referer
        runOnUiThread {
            if (activePreparedStream == null || isNewStream) {
                updateStatus("✅ Flux vidéo prêt ! Appuyez sur LANCER SUR LA XBOX.", isLoading = false)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.xbox_green))
            }
        }
    }

    private fun setupListeners() {
        binding.btnHomeHub.setOnClickListener {
            showHomeHub()
        }

        binding.cardSelectAnimeSama.setOnClickListener {
            loadBrowserUrl(ANIME_SAMA_HOME)
        }

        binding.cardSelectFrenchStream.setOnClickListener {
            loadBrowserUrl(FRENCH_STREAM_HOME)
        }

        binding.btnResumeLastPage.setOnClickListener {
            val savedPage = prefs.getString(KEY_LAST_PAGE, ANIME_SAMA_HOME) ?: ANIME_SAMA_HOME
            loadBrowserUrl(savedPage)
        }

        binding.btnHomeAnimeSama.setOnClickListener {
            loadBrowserUrl(ANIME_SAMA_HOME)
        }

        binding.btnHomeFrenchStream.setOnClickListener {
            loadBrowserUrl(FRENCH_STREAM_HOME)
        }

        binding.btnGoUrl.setOnClickListener {
            val rawInput = binding.etVideoUrl.text?.toString()?.trim() ?: ""
            if (rawInput.isNotBlank()) {
                loadBrowserUrl(normalizeUrlOrSearch(rawInput))
            }
        }

        binding.etVideoUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val rawInput = binding.etVideoUrl.text?.toString()?.trim() ?: ""
                if (rawInput.isNotBlank()) {
                    loadBrowserUrl(normalizeUrlOrSearch(rawInput))
                }
                true
            } else {
                false
            }
        }

        // Bouton principal LANCER SUR LA XBOX
        binding.btnCast.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isBlank()) {
                Toast.makeText(this, "Veuillez entrer l'IP de la Xbox", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_XBOX_IP, ip).apply()
            pauseWebViewPlayback()

            val readyStream = lastDetectedStreamUrl
            if (!readyStream.isNullOrBlank() && VideoStreamSniffer.isVideoStreamUrl(readyStream)) {
                sendStreamToXbox(readyStream, lastDetectedReferer, ip)
                return@setOnClickListener
            }

            val targetUrl = currentIframeEmbedUrl ?: binding.webViewBrowser.url ?: binding.etVideoUrl.text.toString().trim()
            if (targetUrl.isBlank()) {
                Toast.makeText(this, "Choisissez d'abord un film ou un épisode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startExtractionAndCast(targetUrl, ip)
        }

        // --- Télécommande : Pause / Play ---
        binding.btnPlayPause.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: return@setOnClickListener
            lifecycleScope.launch {
                if (isXboxPaused) {
                    val res = dlnaController.resumeMedia(ip)
                    if (res.isSuccess) {
                        isXboxPaused = false
                        binding.btnPlayPause.text = "⏸ PAUSE"
                        updateStatus("Lecture en cours sur la Xbox 🎮", isLoading = false)
                    }
                } else {
                    val res = dlnaController.pauseMedia(ip)
                    if (res.isSuccess) {
                        isXboxPaused = true
                        binding.btnPlayPause.text = "▶ PLAY"
                        updateStatus("En pause sur la Xbox ⏸", isLoading = false)
                    }
                }
            }
        }

        // --- Télécommande : Reculer de 30s ---
        binding.btnRewind30.setOnClickListener {
            jumpBySeconds(-30)
        }

        // --- Télécommande : Avancer de 30s ---
        binding.btnForward30.setOnClickListener {
            jumpBySeconds(+30)
        }

        // --- Télécommande : Arrêter ---
        binding.btnStop.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isNotBlank()) {
                stopPositionTracker()
                activePreparedStream = null
                com.xboxvideocaster.dlna.CastForegroundService.stopService(this@MainActivity)
                lifecycleScope.launch {
                    dlnaController.stopMedia(ip)
                    binding.layoutRemoteControls.visibility = View.GONE
                    binding.tvTimer.visibility = View.GONE
                    updateStatus("Lecture arrêtée sur la Xbox", isLoading = false)
                }
            }
        }
    }

    /**
     * Saute exactement de +30s ou -30s dans l'épisode/film en cours sur la Xbox
     * (sans jamais doubler le compteur de temps ni changer d'épisode).
     */
    private fun jumpBySeconds(deltaSec: Int) {
        if (isSeekingNow) return
        val ip = binding.etXboxIp.text?.toString()?.trim() ?: return
        val prepared = activePreparedStream ?: return

        val maxSeekSec = (prepared.totalDurationSec - 5).coerceAtLeast(0)
        val targetSec = (currentPlaybackSec + deltaSec).coerceIn(0, maxSeekSec)

        isSeekingNow = true
        updateStatus("Saut à ${dlnaController.formatSecondsToHms(targetSec)}...", isLoading = true)

        lifecycleScope.launch {
            try {
                if (prepared.isHls && prepared.segments.isNotEmpty()) {
                    val actualStartSec = withContext(Dispatchers.IO) {
                        localStreamProxy.warmupForSeek(prepared, targetSec)
                    }
                    currentPlaybackSec = actualStartSec
                    updateTimerDisplay()

                    val newStreamUrl = localStreamProxy.buildPlaybackUrl(prepared, actualStartSec)

                    val res = dlnaController.playMediaOnXbox(
                        ip = ip,
                        videoUri = newStreamUrl,
                        title = "Xbox Video Stream",
                        durationSeconds = prepared.totalDurationSec
                    )
                    if (res.isSuccess) {
                        isXboxPaused = false
                        binding.btnPlayPause.text = "⏸ PAUSE"
                        updateTimerDisplay()
                        updateStatus("Lecture en cours sur la Xbox 🎮", isLoading = false)
                    } else {
                        updateStatus("Erreur lors du saut : ${res.exceptionOrNull()?.localizedMessage}", isLoading = false)
                    }
                } else {
                    val seekRes = dlnaController.seekMedia(ip, targetSec)
                    if (seekRes.isSuccess) {
                        currentPlaybackSec = targetSec
                        updateTimerDisplay()
                        updateStatus("Lecture en cours sur la Xbox 🎮", isLoading = false)
                    }
                }
            } finally {
                isSeekingNow = false
            }
        }
    }

    /**
     * Chronomètre local ultra-fluide (1s) : n'envoie aucune requête SOAP GetPositionInfo en boucle
     * à la Xbox (ce qui évitait le réveil permanent de l'interface TV et le doublage de l'offset).
     */
    private fun startPositionTracker() {
        stopPositionTracker()
        binding.tvTimer.visibility = View.VISIBLE
        updateTimerDisplay()
        positionTrackerJob = lifecycleScope.launch {
            while (isActive && activePreparedStream != null) {
                delay(1000)
                val prepared = activePreparedStream ?: break
                if (!isXboxPaused && !isSeekingNow && currentPlaybackSec < prepared.totalDurationSec) {
                    currentPlaybackSec += 1
                    updateTimerDisplay()
                }
            }
        }
    }

    private fun stopPositionTracker() {
        positionTrackerJob?.cancel()
        positionTrackerJob = null
    }

    private fun updateTimerDisplay() {
        val prepared = activePreparedStream ?: return
        val currentStr = dlnaController.formatSecondsToHms(currentPlaybackSec)
        val totalStr = dlnaController.formatSecondsToHms(prepared.totalDurationSec)
        binding.tvTimer.text = "$currentStr / $totalStr"
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutHomeHub.visibility == View.VISIBLE) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else if (binding.webViewBrowser.canGoBack()) {
                    binding.webViewBrowser.goBack()
                } else {
                    showHomeHub()
                }
            }
        })
    }

    private fun normalizeUrlOrSearch(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input
        }
        if (input.contains(".") && !input.contains(" ")) {
            return "https://$input"
        }
        return "https://anime-sama.to/catalogue/?search=${java.net.URLEncoder.encode(input, "UTF-8")}"
    }

    private fun loadBrowserUrl(url: String) {
        binding.layoutHomeHub.visibility = View.GONE
        lastDetectedStreamUrl = null
        currentIframeEmbedUrl = null
        if (activePreparedStream == null) {
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            updateStatus("Lancez la vidéo sur la page ou choisissez un épisode...", isLoading = false)
        }
        binding.etVideoUrl.setText(url)
        binding.webViewBrowser.loadUrl(url)
    }

    private fun handleIncomingIntent(intent: Intent): Boolean {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val url = findUrlInText(sharedText) ?: sharedText
                loadBrowserUrl(url)
                val ip = binding.etXboxIp.text?.toString()?.trim()
                if (!ip.isNullOrBlank()) {
                    startExtractionAndCast(url, ip)
                }
                return true
            }
        }
        return false
    }

    private fun findUrlInText(text: String): String? {
        val regex = "(https?://[\\w-]+(\\.[\\w-]+)+(/[#\\w-./?%&=]*)?)".toRegex()
        return regex.find(text)?.value
    }

    private fun startExtractionAndCast(url: String, ip: String) {
        binding.btnCast.isEnabled = false
        updateStatus(getString(R.string.status_extracting), isLoading = true)

        if (youtubeExtractor.isYouTubeUrl(url)) {
            val videoId = youtubeExtractor.extractVideoId(url)
            if (videoId != null) {
                lifecycleScope.launch {
                    val streamResult = youtubeExtractor.resolveDirectStreamUrl(videoId)
                    streamResult.onSuccess { streamUrl ->
                        sendStreamToXbox(streamUrl, "https://www.youtube.com/", ip)
                    }.onFailure {
                        fallbackWebViewExtraction(url, ip)
                    }
                }
                return
            }
        }

        lifecycleScope.launch {
            val iframeUrl = withContext(Dispatchers.Main) { currentIframeEmbedUrl }
            val pageRef = withContext(Dispatchers.Main) { binding.webViewBrowser.url ?: "https://anime-sama.to/" }
            if (!iframeUrl.isNullOrBlank()) {
                val extractedFromIframe = withContext(Dispatchers.IO) {
                    videoStreamSniffer.tryExtractDirectStreamFromEmbedHtml(iframeUrl, pageRef)
                }
                if (extractedFromIframe != null) {
                    sendStreamToXbox(extractedFromIframe.first, extractedFromIframe.second, ip)
                    return@launch
                }
            }

            val smartResult = videoStreamSniffer.extractFromUrlSmart(pageRef)
            smartResult.onSuccess { (streamUrl, referer) ->
                sendStreamToXbox(streamUrl, referer, ip)
            }.onFailure {
                fallbackWebViewExtraction(iframeUrl ?: url, ip)
            }
        }
    }

    private fun fallbackWebViewExtraction(url: String, ip: String) {
        videoStreamSniffer.extractStreamViaWebView(url, object : VideoStreamSniffer.Callback {
            override fun onStreamFound(streamUrl: String, referer: String) {
                sendStreamToXbox(streamUrl, referer, ip)
            }

            override fun onError(message: String) {
                runOnUiThread {
                    updateStatus("Appuyez une fois sur le bouton Play ▶ de la vidéo puis recliquez sur LANCER !", isLoading = false)
                    binding.btnCast.isEnabled = true
                }
            }
        })
    }

    private fun sendStreamToXbox(rawStreamUrl: String, referer: String, xboxIp: String) {
        runOnUiThread {
            binding.btnCast.isEnabled = false
            updateStatus("Préparation du cache anti-freeze & envoi à la Xbox...", isLoading = true)
        }

        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                localStreamProxy.prepareStream(
                    realStreamUrl = rawStreamUrl,
                    referer = referer,
                    userAgent = VideoStreamSniffer.USER_AGENT
                )
            }

            activePreparedStream = prepared
            currentPlaybackSec = 0
            isXboxPaused = false

            val proxiedUrl = localStreamProxy.buildPlaybackUrl(prepared, startSeconds = 0)

            val castResult = dlnaController.playMediaOnXbox(
                ip = xboxIp,
                videoUri = proxiedUrl,
                title = "Xbox Video Stream",
                durationSeconds = prepared.totalDurationSec
            )

            castResult.onSuccess {
                com.xboxvideocaster.dlna.CastForegroundService.startService(this@MainActivity)
                updateStatus(getString(R.string.status_playing), isLoading = false)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.xbox_green))
                binding.btnCast.isEnabled = true
                binding.btnPlayPause.text = "⏸ PAUSE"
                binding.layoutRemoteControls.visibility = View.VISIBLE
                startPositionTracker()
                Toast.makeText(this@MainActivity, "Lecture lancée sur la Xbox ! 🎮", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                updateStatus("Erreur Xbox (${xboxIp}) : ${error.localizedMessage}", isLoading = false)
                binding.btnCast.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Erreur connexion Xbox : ${error.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateStatus(text: String, isLoading: Boolean) {
        binding.tvStatus.text = text
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        stopPositionTracker()
        videoStreamSniffer.cleanup()
        if (isFinishing && activePreparedStream == null) {
            localStreamProxy.stop()
            com.xboxvideocaster.dlna.CastForegroundService.stopService(this)
        }
        super.onDestroy()
    }
}
