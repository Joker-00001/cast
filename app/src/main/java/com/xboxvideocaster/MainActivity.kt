package com.xboxvideocaster

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xboxvideocaster.databinding.ActivityMainBinding
import com.xboxvideocaster.dlna.CastForegroundService
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

    // État de la lecture en cours sur la Xbox / TV
    private var activePreparedStream: LocalStreamProxy.PreparedStream? = null
    private var currentPlaybackSec: Int = 0
    private var isXboxPaused: Boolean = false
    private var isSeekingNow: Boolean = false
    private var positionTrackerJob: Job? = null

    // Option : Épisode suivant automatique à la fin
    private var isAutoNextEnabled: Boolean = false
    private var autoNextTriggeredForStreamId: String? = null

    // Mode Plein Écran Paysage sur le téléphone
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isPhoneFullscreenMode: Boolean = false

    companion object {
        private const val PREFS_NAME = "xbox_caster_prefs"
        private const val KEY_XBOX_IP = "key_xbox_ip"
        private const val KEY_DEVICE_NAME = "key_device_name"
        private const val KEY_DEVICE_CTRL_URL = "key_device_ctrl_url"
        private const val KEY_AUTO_NEXT = "key_auto_next"
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
        localStreamProxy = LocalStreamProxy.getInstance(this)
        appUpdater = AppUpdater(this)

        requestNotificationPermissionIfNeeded()
        setupIpAndPreferences()
        setupBuiltInBrowser()
        setupListeners()
        setupBackNavigation()
        restoreActiveCastIfAny()
        checkAppUpdateAuto()

        if (!handleIncomingIntent(intent)) {
            showHomeHub()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    private fun restoreActiveCastIfAny() {
        val existingStream = localStreamProxy.activeStream
        if (existingStream != null && CastForegroundService.isCastActive) {
            activePreparedStream = existingStream
            binding.layoutRemoteControls.visibility = View.VISIBLE
            binding.tvTimer.visibility = View.VISIBLE
            updateStatus("Diffusion active en cours sur la TV / Xbox 🎮", isLoading = false)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.xbox_green))
            startPositionTracker()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun showHomeHub() {
        if (isPhoneFullscreenMode) {
            exitPhoneFullscreen()
        }
        binding.layoutHomeHub.visibility = View.VISIBLE
        val savedPage = prefs.getString(KEY_LAST_PAGE, null)
        binding.btnResumeLastPage.visibility = if (savedPage.isNullOrBlank()) View.GONE else View.VISIBLE
        if (activePreparedStream == null) {
            updateStatus("Choisissez une plateforme ou scannez votre TV / Xbox", isLoading = false)
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

    private fun setupIpAndPreferences() {
        val savedIp = prefs.getString(KEY_XBOX_IP, DEFAULT_XBOX_IP)
        binding.etXboxIp.setText(savedIp)
        val savedCtrlUrl = prefs.getString(KEY_DEVICE_CTRL_URL, null)
        if (!savedCtrlUrl.isNullOrBlank()) {
            dlnaController.setPreferredControlUrl(savedCtrlUrl)
        }
        val savedDevName = prefs.getString(KEY_DEVICE_NAME, null)
        if (!savedDevName.isNullOrBlank()) {
            binding.btnCast.text = "📺 LANCER SUR ${savedDevName.substringBefore(" (").uppercase()}"
        }

        isAutoNextEnabled = prefs.getBoolean(KEY_AUTO_NEXT, false)
        updateAutoNextButtonUi()
    }

    private fun updateAutoNextButtonUi() {
        if (isAutoNextEnabled) {
            binding.btnAutoNext.text = "⏭ Ép. Suivant Auto: ON ✅"
            binding.btnAutoNext.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#14532D"))
            binding.btnAutoNext.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            binding.btnAutoNext.text = "⏭ Ép. Suivant Auto: OFF"
            binding.btnAutoNext.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2B303B"))
            binding.btnAutoNext.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
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

                @JavascriptInterface
                fun onAutoNextEpisodeReady(nextEmbedOrPageUrl: String, pageReferer: String) {
                    val ip = binding.etXboxIp.text?.toString()?.trim() ?: DEFAULT_XBOX_IP
                    runOnUiThread {
                        updateStatus("⏭ Passage automatique à l'épisode suivant...", isLoading = true)
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val extracted = videoStreamSniffer.tryExtractDirectStreamFromEmbedHtml(
                            nextEmbedOrPageUrl,
                            pageReferer.ifBlank { "https://anime-sama.to/" }
                        )
                        if (extracted != null && VideoStreamSniffer.isVideoStreamUrl(extracted.first)) {
                            lastDetectedStreamUrl = extracted.first
                            lastDetectedReferer = extracted.second
                            sendStreamToXbox(extracted.first, extracted.second, ip)
                        } else {
                            withContext(Dispatchers.Main) {
                                startExtractionAndCast(nextEmbedOrPageUrl, ip)
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

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customFullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customFullscreenView = view
                    customViewCallback = callback
                    binding.fullscreenContainer.addView(view)
                    binding.fullscreenContainer.visibility = View.VISIBLE
                    enterLandscapeImmersiveUi()
                }

                override fun onHideCustomView() {
                    val view = customFullscreenView ?: return
                    binding.fullscreenContainer.removeView(view)
                    binding.fullscreenContainer.visibility = View.GONE
                    customFullscreenView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    exitLandscapeImmersiveUi()
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

                var lecSelInit = document.getElementById('selectLecteurs');
                if (lecSelInit) {
                    lecSelInit.addEventListener('change', function() {
                        window.__userChangedLecteur = true;
                        window.__xboxCastBlanked = false;
                    });
                }
                var epSelInit = document.getElementById('selectEpisodes');
                if (epSelInit) {
                    epSelInit.addEventListener('change', function() {
                        window.__xboxCastBlanked = false;
                    });
                }

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

                        var foundPreferredAnimeSama = false;
                        // 2. Spécifique Anime-Sama : sélectionner automatiquement le lecteur HLS haute vitesse (ansembed.net / Lecteur 2)
                        // au lieu du Lecteur 1 par défaut (sendvid/sibnet en .mp4 bridé à 250k qui faisait pop/dépop le menu TV !)
                        if (window.location.href.indexOf('anime-sama') !== -1) {
                            var epSel = document.getElementById('selectEpisodes');
                            var lecSel = document.getElementById('selectLecteurs');
                            var pFrame = document.getElementById('playerDF');
                            if (epSel && epSel.selectedIndex >= 0 && !window.__userChangedLecteur) {
                                var epIdx = epSel.selectedIndex;
                                var bestUrl = null;
                                var bestLecIdx = -1;
                                var pools = [window.eps1, window.eps2, window.eps3, window.eps4, window.eps5, window.eps6, window.eps7, window.eps8];
                                for (var p = 0; p < pools.length; p++) {
                                    var arr = pools[p];
                                    if (Array.isArray(arr) && arr[epIdx] && arr[epIdx].indexOf('ansembed.net') !== -1) {
                                        bestUrl = arr[epIdx];
                                        bestLecIdx = p;
                                        break;
                                    }
                                }
                                if (!bestUrl) {
                                    for (var p2 = 0; p2 < pools.length; p2++) {
                                        var arr2 = pools[p2];
                                        if (Array.isArray(arr2) && arr2[epIdx] && arr2[epIdx].indexOf('vidmoly') !== -1) {
                                            bestUrl = arr2[epIdx].replace(/vidmoly\.(to|net)/g, 'vidmoly.biz');
                                            bestLecIdx = p2;
                                            break;
                                        }
                                    }
                                }
                                if (bestUrl) {
                                    foundPreferredAnimeSama = true;
                                    if (lecSel && pFrame && !window.__xboxCastBlanked) {
                                        var curSrc = pFrame.src || '';
                                        if (curSrc !== bestUrl) {
                                            lecSel.selectedIndex = bestLecIdx;
                                            pFrame.src = bestUrl;
                                        }
                                    }
                                    XboxBridge.onIframeDetected(bestUrl, window.location.href);
                                }
                            }
                        }

                        // 3. Surveiller les iframes de lecteurs (French Stream #player-frame ou lecteur choisi manuellement)
                        if (!foundPreferredAnimeSama) {
                            var iframes = document.querySelectorAll('#playerDF, #player-frame, #main-player iframe, #seriePlayer iframe, .iframe-container iframe, iframe[src*="embed"], iframe[src*="ansembed"], iframe[src*="vidmoly"], iframe[src*="sendvid"], iframe[src*="minochinos"], iframe[src*="uqload"], iframe[src*="dood"], iframe[src*="voe"], iframe[src*="luluvdo"], iframe[src*="fsvid"], iframe[src*="vidzy"], iframe[src*="sibnet"]');
                            for (var k = 0; k < iframes.length; k++) {
                                var src = iframes[k].src || iframes[k].getAttribute('data-src');
                                if (src && src.startsWith('http') && src.indexOf('google') === -1) {
                                    XboxBridge.onIframeDetected(src, window.location.href);
                                }
                            }
                        }

                        // 4. Surveiller les balises <video> directes (en ignorant le faux flux /troll/)
                        var vids = document.querySelectorAll('video, source');
                        for (var i = 0; i < vids.length; i++) {
                            var s = vids[i].src || vids[i].currentSrc;
                            if (s && s.startsWith('http') && s.indexOf('/troll/') === -1) {
                                XboxBridge.onStreamDetected(s, window.location.href);
                            }
                        }
                    } catch (e) {}
                }, 800);
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(js, null)
    }

    /**
     * Coupe immédiatement la lecture vidéo (et l'iframe cross-origin) sur le téléphone pendant le Cast Xbox
     * afin que le téléphone ne télécharge pas la vidéo en double en arrière-plan.
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
                    var playerIframes = document.querySelectorAll('#playerDF, #player-frame, iframe[src*="ansembed"], iframe[src*="fsvid"], iframe[src*="vidzy"], iframe[src*="sendvid"], iframe[src*="vidmoly"], iframe[src*="uqload"], iframe[src*="sibnet"]');
                    for (var k = 0; k < playerIframes.length; k++) {
                        var ifr = playerIframes[k];
                        if (ifr.src && ifr.src.startsWith('http')) {
                            ifr.setAttribute('data-cast-src', ifr.src);
                            window.__xboxCastBlanked = true;
                            ifr.src = 'about:blank';
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsPause, null)
    }

    private fun onNewVideoStreamFound(streamUrl: String, referer: String) {
        if (!VideoStreamSniffer.isVideoStreamUrl(streamUrl)) return
        val current = lastDetectedStreamUrl
        // Ne jamais écraser un flux HLS (.m3u8) déjà prêt par un flux .mp4 lent (ex: sendvid rate=250k ou sibnet) !
        if (current != null &&
            current.contains(".m3u8", ignoreCase = true) &&
            streamUrl.contains(".mp4", ignoreCase = true)
        ) {
            return
        }
        val isNewStream = (streamUrl != current)
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

        // --- Scanner les appareils (TV, Xbox...) sur le même Wi-Fi ---
        binding.btnScanDevices.setOnClickListener {
            scanAndSelectDevice()
        }
        binding.btnHomeScanDevices.setOnClickListener {
            scanAndSelectDevice()
        }

        // --- Bouton activable : Ép. Suivant Automatique (ON / OFF) ---
        binding.btnAutoNext.setOnClickListener {
            isAutoNextEnabled = !isAutoNextEnabled
            prefs.edit().putBoolean(KEY_AUTO_NEXT, isAutoNextEnabled).apply()
            updateAutoNextButtonUi()
            val msg = if (isAutoNextEnabled) {
                "Épisode suivant automatique ACTIVÉ ✅"
            } else {
                "Épisode suivant automatique DÉSACTIVÉ"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // --- Bouton : Plein Écran Paysage sur le téléphone ---
        binding.btnPhoneFullscreen.setOnClickListener {
            if (binding.layoutHomeHub.visibility == View.VISIBLE) {
                Toast.makeText(this, "Ouvrez d'abord un animé ou un film pour passer en plein écran", Toast.LENGTH_SHORT).show()
            } else {
                enterPhoneFullscreen()
            }
        }

        binding.btnExitPhoneFullscreen.setOnClickListener {
            exitPhoneFullscreen()
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

        // Bouton principal LANCER SUR LA XBOX / TV
        binding.btnCast.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isBlank()) {
                Toast.makeText(this, "Veuillez entrer l'IP ou scanner votre TV / Xbox", Toast.LENGTH_SHORT).show()
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
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    }
                } else {
                    val res = dlnaController.pauseMedia(ip)
                    if (res.isSuccess) {
                        isXboxPaused = true
                        binding.btnPlayPause.text = "▶ PLAY"
                        updateStatus("En pause sur la TV / Xbox ⏸", isLoading = false)
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
                localStreamProxy.activeStream = null
                CastForegroundService.stopService(this@MainActivity)
                restoreBlankedWebViewIframe()
                lifecycleScope.launch {
                    dlnaController.stopMedia(ip)
                    binding.layoutRemoteControls.visibility = View.GONE
                    binding.tvTimer.visibility = View.GONE
                    updateStatus("Lecture arrêtée sur la TV / Xbox", isLoading = false)
                }
            }
        }
    }

    /**
     * Scanne le réseau Wi-Fi local pour trouver toutes les TV connectées et consoles Xbox
     * et permet de choisir sur quel appareil caster.
     */
    private fun scanAndSelectDevice() {
        updateStatus("📡 Recherche des TV et Xbox sur votre Wi-Fi...", isLoading = true)
        Toast.makeText(this, "Scan du Wi-Fi en cours (2s)...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val wifiIp = localStreamProxy.getLocalIpAddress()
            val devices = dlnaController.scanNetworkDevices(wifiIp)
            updateStatus(
                if (devices.isNotEmpty()) "✅ ${devices.size} appareil(s) détecté(s) sur le Wi-Fi"
                else "Aucun appareil détecté automatiquement (vérifiez que la TV/Xbox est allumée)",
                isLoading = false
            )

            val currentIp = binding.etXboxIp.text?.toString()?.trim() ?: DEFAULT_XBOX_IP
            val listItems = mutableListOf< DlnaController.DiscoveredDevice>()
            listItems.addAll(devices)
            if (listItems.none { it.ip == currentIp }) {
                listItems.add(
                    DlnaController.DiscoveredDevice(
                        name = "Appareil actuel ($currentIp)",
                        ip = currentIp,
                        controlUrl = ""
                    )
                )
            }

            val labels = listItems.map { "📺 ${it.name}" }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("📡 Choisir la TV ou Xbox (Wi-Fi)")
                .setItems(labels) { _, which ->
                    val chosen = listItems[which]
                    binding.etXboxIp.setText(chosen.ip)
                    val ctrlUrl = chosen.controlUrl.ifBlank { null }
                    dlnaController.setPreferredControlUrl(ctrlUrl)
                    val cleanName = chosen.name.substringBefore(" (")
                    prefs.edit()
                        .putString(KEY_XBOX_IP, chosen.ip)
                        .putString(KEY_DEVICE_NAME, cleanName)
                        .putString(KEY_DEVICE_CTRL_URL, ctrlUrl)
                        .apply()
                    binding.btnCast.text = "📺 LANCER SUR ${cleanName.uppercase()}"
                    Toast.makeText(
                        this@MainActivity,
                        "Appareil sélectionné : ${chosen.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNeutralButton("🔄 Relancer le scan") { _, _ ->
                    scanAndSelectDevice()
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    /**
     * Active le mode Plein Écran Paysage sur le téléphone pour regarder la vidéo directement sur le mobile.
     */
    private fun enterPhoneFullscreen() {
        isPhoneFullscreenMode = true
        restoreBlankedWebViewIframe()
        binding.topBar.visibility = View.GONE
        binding.bottomCastBar.visibility = View.GONE
        binding.btnExitPhoneFullscreen.visibility = View.VISIBLE
        enterLandscapeImmersiveUi()

        val jsExpandVideo = """
            (function() {
                try {
                    var old = document.getElementById('__xboxFullscreenStyle');
                    if (old) old.remove();
                    var st = document.createElement('style');
                    st.id = '__xboxFullscreenStyle';
                    st.innerHTML = 'body, html { overflow: hidden !important; background: #000 !important; } #playerDF, #player-frame, #main-player iframe, #seriePlayer iframe, .iframe-container iframe, iframe[src*="ansembed"], iframe[src*="fsvid"], iframe[src*="vidzy"], iframe[src*="sendvid"], iframe[src*="vidmoly"], iframe[src*="uqload"], iframe[src*="sibnet"], video { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-width: 100vw !important; max-height: 100vh !important; z-index: 2147483647 !important; background: #000 !important; border: none !important; margin: 0 !important; padding: 0 !important; }';
                    document.head.appendChild(st);
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsExpandVideo, null)
    }

    /**
     * Quitte le mode Plein Écran Paysage sur le téléphone et restaure l'interface normale.
     */
    private fun exitPhoneFullscreen() {
        if (customFullscreenView != null) {
            binding.webViewBrowser.webChromeClient?.onHideCustomView()
        }
        isPhoneFullscreenMode = false
        binding.btnExitPhoneFullscreen.visibility = View.GONE
        binding.topBar.visibility = View.VISIBLE
        binding.bottomCastBar.visibility = View.VISIBLE
        exitLandscapeImmersiveUi()

        val jsRestoreVideo = """
            (function() {
                try {
                    var old = document.getElementById('__xboxFullscreenStyle');
                    if (old) old.remove();
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsRestoreVideo, null)
    }

    private fun enterLandscapeImmersiveUi() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitLandscapeImmersiveUi() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun restoreBlankedWebViewIframe() {
        val jsRestore = """
            (function() {
                try {
                    window.__xboxCastBlanked = false;
                    var iframes = document.querySelectorAll('iframe[data-cast-src]');
                    for (var i = 0; i < iframes.length; i++) {
                        var saved = iframes[i].getAttribute('data-cast-src');
                        if (saved && (iframes[i].src === 'about:blank' || !iframes[i].src)) {
                            iframes[i].src = saved;
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsRestore, null)
    }

    /**
     * Passe automatiquement à l'épisode suivant dans la page (Anime-Sama ou French Stream)
     * et lance immédiatement sa diffusion sur la TV / Xbox.
     */
    private fun triggerAutoNextEpisode() {
        runOnUiThread {
            updateStatus("⏭ Fin de l'épisode : lancement automatique de l'épisode suivant...", isLoading = true)
            Toast.makeText(this, "⏭ Passage automatique à l'épisode suivant !", Toast.LENGTH_SHORT).show()
        }

        val jsNextEp = """
            (function() {
                try {
                    // 1. Cas Anime-Sama
                    var epSel = document.getElementById('selectEpisodes');
                    if (epSel && epSel.selectedIndex + 1 < epSel.options.length) {
                        window.__xboxCastBlanked = false;
                        epSel.selectedIndex = epSel.selectedIndex + 1;
                        if (typeof selectEpisode === 'function') {
                            selectEpisode();
                        }
                        var nextIdx = epSel.selectedIndex;
                        var pools = [window.eps1, window.eps2, window.eps3, window.eps4, window.eps5, window.eps6, window.eps7, window.eps8];
                        var nextEmbed = null;
                        for (var p = 0; p < pools.length; p++) {
                            var arr = pools[p];
                            if (Array.isArray(arr) && arr[nextIdx] && arr[nextIdx].indexOf('ansembed.net') !== -1) {
                                nextEmbed = arr[nextIdx];
                                break;
                            }
                        }
                        if (!nextEmbed) {
                            for (var p2 = 0; p2 < pools.length; p2++) {
                                var arr2 = pools[p2];
                                if (Array.isArray(arr2) && arr2[nextIdx]) {
                                    nextEmbed = arr2[nextIdx];
                                    break;
                                }
                            }
                        }
                        if (nextEmbed) {
                            XboxBridge.onAutoNextEpisodeReady(nextEmbed, window.location.href);
                            return;
                        }
                    }

                    // 2. Cas French Stream (séries)
                    var activeEp = document.querySelector('.elink.active, .fs-ep-btn.active, .episodes a.active, [data-ep].active');
                    if (activeEp && activeEp.nextElementSibling) {
                        window.__xboxCastBlanked = false;
                        activeEp.nextElementSibling.click();
                        setTimeout(function() {
                            var pFrame = document.getElementById('player-frame') || document.querySelector('iframe[src*="fsvid"], iframe[src*="vidzy"]');
                            if (pFrame && pFrame.src && pFrame.src.startsWith('http')) {
                                XboxBridge.onAutoNextEpisodeReady(pFrame.src, window.location.href);
                            }
                        }, 1500);
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(jsNextEp, null)
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
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    } else {
                        updateStatus("Erreur lors du saut : ${res.exceptionOrNull()?.localizedMessage}", isLoading = false)
                    }
                } else {
                    val seekRes = dlnaController.seekMedia(ip, targetSec)
                    if (seekRes.isSuccess) {
                        currentPlaybackSec = targetSec
                        updateTimerDisplay()
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    }
                }
            } finally {
                isSeekingNow = false
            }
        }
    }

    /**
     * Chronomètre local ultra-fluide (1s) + déclenchement automatique de l'épisode suivant à la fin.
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

                    // Si l'option "Ép. Suivant Auto" est activée et qu'on atteint la fin de l'épisode (à 3s de la fin)
                    if (isAutoNextEnabled &&
                        prepared.totalDurationSec > 60 &&
                        currentPlaybackSec >= prepared.totalDurationSec - 3 &&
                        autoNextTriggeredForStreamId != prepared.id
                    ) {
                        autoNextTriggeredForStreamId = prepared.id
                        triggerAutoNextEpisode()
                    }
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
                if (customFullscreenView != null || isPhoneFullscreenMode) {
                    exitPhoneFullscreen()
                } else if (binding.layoutHomeHub.visibility == View.VISIBLE) {
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
            updateStatus("Préparation du cache anti-freeze & envoi à la TV / Xbox...", isLoading = true)
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
            localStreamProxy.activeStream = prepared
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
                val devName = prefs.getString(KEY_DEVICE_NAME, null) ?: "Xbox / TV ($xboxIp)"
                CastForegroundService.startService(this@MainActivity, xboxIp, devName)
                updateStatus(getString(R.string.status_playing), isLoading = false)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.xbox_green))
                binding.btnCast.isEnabled = true
                binding.btnPlayPause.text = "⏸ PAUSE"
                binding.layoutRemoteControls.visibility = View.VISIBLE
                startPositionTracker()
                Toast.makeText(this@MainActivity, "Lecture lancée sur $devName ! 🎮", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                updateStatus("Erreur connexion (${xboxIp}) : ${error.localizedMessage}", isLoading = false)
                binding.btnCast.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Erreur connexion TV/Xbox : ${error.localizedMessage}",
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
        // Ne JAMAIS arrêter localStreamProxy ni CastForegroundService si une diffusion est en cours :
        // l'application reste active dans la barre de notification même quand on ferme l'application !
        super.onDestroy()
    }
}
