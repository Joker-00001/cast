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
import android.widget.SeekBar
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
import com.xboxvideocaster.extractor.AniSkipClient
import com.xboxvideocaster.extractor.VideoStreamSniffer
import com.xboxvideocaster.extractor.YouTubeExtractor
import com.xboxvideocaster.updater.AppUpdater
import com.xboxvideocaster.updater.RemoteDomainSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity(), CastForegroundService.RemoteActionListener {

    data class FavoriteItem(
        val title: String,
        val url: String,
        val episodeIndex: Int = -1,
        val episodeLabel: String = ""
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val dlnaController = DlnaController()
    private val youtubeExtractor = YouTubeExtractor()
    private val aniSkipClient = AniSkipClient()
    private lateinit var videoStreamSniffer: VideoStreamSniffer
    private lateinit var localStreamProxy: LocalStreamProxy
    private lateinit var appUpdater: AppUpdater
    private lateinit var remoteDomainSync: RemoteDomainSync

    @Volatile
    private var lastDetectedStreamUrl: String? = null

    @Volatile
    private var lastDetectedReferer: String = "https://anime-sama.to/"

    @Volatile
    private var currentIframeEmbedUrl: String? = null

    // Métadonnées de la page en cours (pour Favoris, Reprise & AniSkip)
    @Volatile
    private var currentPageTitle: String = ""

    @Volatile
    private var currentEpisodeNumber: Int = 1

    @Volatile
    private var currentEpisodeIndex: Int = -1

    @Volatile
    private var currentEpisodeLabel: String = ""

    @Volatile
    private var pendingRestoreEpisodeIdx: Int = -1

    @Volatile
    private var currentIntroWindow: AniSkipClient.IntroWindow? = null

    // État d'ouverture/réduction du panneau de commandes du bas
    private var isBottomPanelCollapsed: Boolean = false

    // État de la lecture en cours sur la Xbox / TV
    private var activePreparedStream: LocalStreamProxy.PreparedStream? = null
    private var currentPlaybackSec: Int = 0
    private var isXboxPaused: Boolean = false
    private var isSeekingNow: Boolean = false
    private var isUserDraggingSeekBar: Boolean = false
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
        private const val KEY_LAST_CAST_PAGE_URL = "key_last_cast_page_url"
        private const val KEY_LAST_CAST_TITLE = "key_last_cast_title"
        private const val KEY_LAST_CAST_EP_IDX = "key_last_cast_ep_idx"
        private const val KEY_LAST_CAST_POS_SEC = "key_last_cast_pos_sec"
        private const val KEY_FAVORITES_JSON = "key_favorites_json"
        private const val DEFAULT_XBOX_IP = "192.168.1.52"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        remoteDomainSync = RemoteDomainSync(this, prefs)
        videoStreamSniffer = VideoStreamSniffer(this)
        localStreamProxy = LocalStreamProxy.getInstance(this)
        appUpdater = AppUpdater(this)
        CastForegroundService.remoteActionListener = this

        lifecycleScope.launch {
            remoteDomainSync.syncDomainsFromRemote()
        }

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

    override fun onNotificationPlayPauseToggled(isPaused: Boolean) {
        runOnUiThread {
            isXboxPaused = isPaused
            binding.btnPlayPause.text = if (isPaused) "▶ PLAY" else "⏸ PAUSE"
            updateStatus(
                if (isPaused) "En pause sur la TV / Xbox ⏸" else "Lecture en cours sur la TV / Xbox 🎮",
                isLoading = false
            )
        }
    }

    override fun onNotificationSeekCompleted(newPositionSec: Int) {
        runOnUiThread {
            currentPlaybackSec = newPositionSec
            isXboxPaused = false
            binding.btnPlayPause.text = "⏸ PAUSE"
            updateTimerDisplay()
            updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
        }
    }

    override fun onNotificationStopped() {
        runOnUiThread {
            stopPositionTracker()
            activePreparedStream = null
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            restoreBlankedWebViewIframe()
            binding.layoutRemoteControls.visibility = View.GONE
            binding.tvTimer.visibility = View.GONE
            updateStatus("Lecture arrêtée depuis la notification", isLoading = false)
        }
    }

    override fun onNotificationCloseApp() {
        runOnUiThread {
            stopPositionTracker()
            activePreparedStream = null
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            finishAndRemoveTask()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun restoreActiveCastIfAny() {
        val existingStream = localStreamProxy.activeStream
        if (existingStream != null && CastForegroundService.isCastActive) {
            activePreparedStream = existingStream
            currentPlaybackSec = CastForegroundService.currentPositionSec
            isXboxPaused = CastForegroundService.isPausedState
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.layoutRemoteControls.visibility = View.VISIBLE
            binding.tvTimer.visibility = View.VISIBLE
            binding.btnPlayPause.text = if (isXboxPaused) "▶ PLAY" else "⏸ PAUSE"
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
        val savedCastPage = prefs.getString(KEY_LAST_CAST_PAGE_URL, null)
        val savedCastTitle = prefs.getString(KEY_LAST_CAST_TITLE, null)
        val savedCastSec = prefs.getInt(KEY_LAST_CAST_POS_SEC, 0)
        val savedPage = prefs.getString(KEY_LAST_PAGE, null)

        if (!savedCastPage.isNullOrBlank() && savedCastSec > 15 && !savedCastTitle.isNullOrBlank()) {
            binding.btnResumeLastPage.visibility = View.VISIBLE
            val timeStr = dlnaController.formatSecondsToHms(savedCastSec)
            binding.btnResumeLastPage.text = "⏳ Reprendre : $savedCastTitle ($timeStr)"
        } else if (!savedPage.isNullOrBlank()) {
            binding.btnResumeLastPage.visibility = View.VISIBLE
            binding.btnResumeLastPage.text = "⏳ Reprendre ma dernière page visitée"
        } else {
            binding.btnResumeLastPage.visibility = View.GONE
        }

        if (activePreparedStream == null) {
            updateStatus("Choisissez une plateforme, un favori ou scannez votre TV / Xbox", isLoading = false)
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
            binding.btnAutoNext.text = "⏭ Auto: ON ✅"
            binding.btnAutoNext.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#14532D"))
            binding.btnAutoNext.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            binding.btnAutoNext.text = "⏭ Auto: OFF"
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
                                pageReferer.ifBlank { remoteDomainSync.getAnimeSamaUrl() }
                            )
                            if (extracted != null && VideoStreamSniffer.isVideoStreamUrl(extracted.first)) {
                                onNewVideoStreamFound(extracted.first, extracted.second)
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onPageMetadataDetected(pageTitle: String, epNum: Int, epIdx: Int, epLabel: String, pageUrl: String) {
                    val cleanTitle = pageTitle
                        .replace(Regex("(?i)\\s*[-|•]\\s*(anime-sama|french-stream|fstream|fs\\d+).*"), "")
                        .trim()
                    val changedEp = (epNum != currentEpisodeNumber || epIdx != currentEpisodeIndex || cleanTitle != currentPageTitle)
                    currentPageTitle = cleanTitle
                    if (epNum > 0) currentEpisodeNumber = epNum
                    currentEpisodeIndex = epIdx
                    currentEpisodeLabel = epLabel

                    if (changedEp && epIdx >= 0) {
                        updateFavoriteEpisodeIfPresent(pageUrl, epIdx, epLabel)
                        refreshAniSkipIntroForCurrentEpisode(pageUrl, cleanTitle, currentEpisodeNumber)
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
                            pageReferer.ifBlank { remoteDomainSync.getAnimeSamaUrl() }
                        )
                        if (extracted != null && VideoStreamSniffer.isVideoStreamUrl(extracted.first)) {
                            lastDetectedStreamUrl = extracted.first
                            lastDetectedReferer = extracted.second
                            sendStreamToXbox(extracted.first, extracted.second, ip, forceStartFromZero = true)
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
                        remoteDomainSync.checkAndSaveVisitedUrl(url)
                    }
                    view?.evaluateJavascript("window.open = function(){ return null; };", null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        remoteDomainSync.checkAndSaveVisitedUrl(url)
                    }
                    injectSmartSiteWatcher()
                }
            }
        }
    }

    private fun refreshAniSkipIntroForCurrentEpisode(pageUrl: String, pageTitle: String, episodeNum: Int) {
        currentIntroWindow = null
        runOnUiThread {
            binding.btnSkipIntro.text = "⏭ Skip Intro"
        }
        if (!pageUrl.contains("anime-sama", ignoreCase = true) && episodeNum <= 0) return

        lifecycleScope.launch(Dispatchers.IO) {
            val query = aniSkipClient.extractAnimeQueryAndSeason(pageUrl, pageTitle)
            val totalDur = activePreparedStream?.totalDurationSec ?: 0
            val intro = aniSkipClient.fetchExactIntroTimestamps(query, episodeNum, totalDur)
            currentIntroWindow = intro
            withContext(Dispatchers.Main) {
                if (intro != null) {
                    val endFmt = formatShortMinSec(intro.endSec)
                    binding.btnSkipIntro.text = "⏭ Intro (→ $endFmt)"
                } else {
                    binding.btnSkipIntro.text = "⏭ Intro (+1m25)"
                }
            }
        }
    }

    private fun formatShortMinSec(sec: Int): String {
        val m = (sec.coerceAtLeast(0)) / 60
        val s = (sec.coerceAtLeast(0)) % 60
        return String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun injectSmartSiteWatcher() {
        val restoreEpIdx = pendingRestoreEpisodeIdx
        pendingRestoreEpisodeIdx = -1
        val js = """
            (function() {
                window.open = function() { return null; };
                if ($restoreEpIdx >= 0) {
                    window.__pendingRestoreEpIdx = $restoreEpIdx;
                }
                if (window.__xboxWatcherInstalled) return;
                window.__xboxWatcherInstalled = true;

                var style = document.createElement('style');
                style.innerHTML = '.ad-container, #bannLeft, #bannRight, #bannBot, #squBot, #bandeau-pubs-nuit, #fss-tvload, #fss-ad-top, .adsbygoogle, [id*="pub"], iframe[src*="ads"] { display: none !important; pointer-events: none !important; } .nav-menu > li, .st-tabs span, .short, .sect-t, .container2 { opacity: 1 !important; } @media screen and (max-width: 768px) { .header-outer, #header.header, #header.header .header-in { height: auto !important; min-height: 52px !important; overflow: visible !important; } .header .hd-right, #hd-right { display: block !important; position: relative !important; top: auto !important; right: auto !important; width: 100% !important; padding: 6px 10px 10px 10px !important; box-sizing: border-box !important; clear: both !important; z-index: 1002 !important; } .header .search-wrap, #hd-right .search-wrap { width: 100% !important; display: block !important; } #story { width: 100% !important; height: 38px !important; font-size: 14px !important; padding: 0 40px 0 12px !important; background-color: #2b2b2b !important; color: #fff !important; border: 1px solid #f48625 !important; border-radius: 8px !important; box-sizing: border-box !important; pointer-events: auto !important; } #hd-right details.topnav-account, #hd-right .fss-lang-dropdown { display: none !important; } }';
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

                        // 0. Restaurer automatiquement l'épisode sauvegardé (Favori ou Reprise)
                        var epSelRestore = document.getElementById('selectEpisodes');
                        if (typeof window.__pendingRestoreEpIdx === 'number' && window.__pendingRestoreEpIdx >= 0 && epSelRestore && epSelRestore.options.length > window.__pendingRestoreEpIdx) {
                            var targetIdx = window.__pendingRestoreEpIdx;
                            window.__pendingRestoreEpIdx = -1;
                            if (epSelRestore.selectedIndex !== targetIdx) {
                                epSelRestore.selectedIndex = targetIdx;
                                if (typeof selectEpisode === 'function') {
                                    selectEpisode();
                                } else {
                                    epSelRestore.dispatchEvent(new Event('change', { bubbles: true }));
                                }
                            }
                        }

                        // Extraire le titre de l'anime/série et le numéro d'épisode actuel pour Favoris, Reprise & AniSkip
                        var rawTitle = document.title || '';
                        var hTitle = document.querySelector('#titreOeuvre, h1');
                        if (hTitle && hTitle.textContent && hTitle.textContent.trim().length > 1) {
                            rawTitle = hTitle.textContent.trim();
                        }
                        var curEpNum = 1;
                        var curEpIdx = -1;
                        var curEpLabel = '';
                        if (epSelRestore && epSelRestore.selectedIndex >= 0 && epSelRestore.options[epSelRestore.selectedIndex]) {
                            curEpIdx = epSelRestore.selectedIndex;
                            curEpLabel = epSelRestore.options[curEpIdx].text || ('Épisode ' + (curEpIdx + 1));
                            var mEp = curEpLabel.match(/(\d+)/);
                            curEpNum = mEp ? parseInt(mEp[1], 10) : (curEpIdx + 1);
                        }
                        XboxBridge.onPageMetadataDetected(rawTitle, curEpNum, curEpIdx, curEpLabel, window.location.href);

                        // 1. Détruire uniquement les calques transparents invisibles de pub
                        // sans JAMAIS supprimer le menu mobile, la recherche ou les modales de French Stream !
                        var allDivs = document.querySelectorAll('div, a, aside');
                        for (var d = 0; d < allDivs.length; d++) {
                            var el = allDivs[d];
                            var idAndCls = ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '')).toLowerCase();
                            if (idAndCls.indexOf('fs-') !== -1 ||
                                idAndCls.indexOf('fss-') !== -1 ||
                                idAndCls.indexOf('search') !== -1 ||
                                idAndCls.indexOf('header') !== -1 ||
                                idAndCls.indexOf('menu') !== -1 ||
                                idAndCls.indexOf('modal') !== -1 ||
                                idAndCls.indexOf('login') !== -1 ||
                                idAndCls.indexOf('topnav') !== -1 ||
                                idAndCls.indexOf('custom-mb') !== -1 ||
                                idAndCls.indexOf('suggest') !== -1 ||
                                el.querySelector('input, form, button, select')) {
                                continue;
                            }
                            var st = window.getComputedStyle(el);
                            var z = parseInt(st.zIndex, 10) || 0;
                            if ((st.position === 'fixed' || st.position === 'absolute') && z > 99999) {
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
        // Ne jamais écraser un master.m3u8 déjà détecté par une sous-playlist (index-v1-a1.m3u8) basse qualité (_l) !
        if (current != null &&
            current.contains("master.m3u8", ignoreCase = true) &&
            streamUrl.contains("index-", ignoreCase = true)
        ) {
            return
        }
        // Si une sous-playlist basse qualité (_l/index-) est interceptée, forcer la version HD Stéréo (_n/index-)
        val upgradedStreamUrl = if (streamUrl.contains("_l/index-", ignoreCase = true)) {
            streamUrl.replace("_l/index-", "_n/index-", ignoreCase = true)
        } else {
            streamUrl
        }
        val isNewStream = (upgradedStreamUrl != current)
        lastDetectedStreamUrl = upgradedStreamUrl
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
            loadBrowserUrl(remoteDomainSync.getAnimeSamaUrl())
        }

        binding.cardSelectFrenchStream.setOnClickListener {
            loadBrowserUrl(remoteDomainSync.getFrenchStreamUrl())
        }

        binding.btnResumeLastPage.setOnClickListener {
            val savedCastPage = prefs.getString(KEY_LAST_CAST_PAGE_URL, null)
            val savedCastEpIdx = prefs.getInt(KEY_LAST_CAST_EP_IDX, -1)
            val defaultAs = remoteDomainSync.getAnimeSamaUrl()
            val savedPage = savedCastPage ?: prefs.getString(KEY_LAST_PAGE, defaultAs) ?: defaultAs
            if (savedCastEpIdx >= 0) {
                pendingRestoreEpisodeIdx = savedCastEpIdx
            }
            loadBrowserUrl(savedPage)
        }

        binding.btnHomeAnimeSama.setOnClickListener {
            loadBrowserUrl(remoteDomainSync.getAnimeSamaUrl())
        }

        binding.btnHomeFrenchStream.setOnClickListener {
            loadBrowserUrl(remoteDomainSync.getFrenchStreamUrl())
        }

        // --- Favoris & Séries en cours (1 clic) ---
        binding.btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }
        binding.btnHomeFavorites.setOnClickListener {
            showFavoritesDialog()
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
                handleTopBarSearchOrUrl(rawInput)
            }
        }

        binding.etVideoUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val rawInput = binding.etVideoUrl.text?.toString()?.trim() ?: ""
                if (rawInput.isNotBlank()) {
                    handleTopBarSearchOrUrl(rawInput)
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
                        CastForegroundService.updateNotificationState(this@MainActivity, false, currentPlaybackSec)
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    }
                } else {
                    val res = dlnaController.pauseMedia(ip)
                    if (res.isSuccess) {
                        isXboxPaused = true
                        binding.btnPlayPause.text = "▶ PLAY"
                        CastForegroundService.updateNotificationState(this@MainActivity, true, currentPlaybackSec)
                        updateStatus("En pause sur la TV / Xbox ⏸", isLoading = false)
                    }
                }
            }
        }

        // --- Télécommande : Curseur manuel (SeekBar) pour avancer/reculer librement ---
        binding.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = dlnaController.formatSecondsToHms(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserDraggingSeekBar = false
                val target = seekBar?.progress ?: return
                seekToSeconds(target)
            }
        })

        // --- Télécommande : Reculer de 30s ---
        binding.btnRewind30.setOnClickListener {
            jumpBySeconds(-30)
        }

        // --- Télécommande : Avancer de 30s ---
        binding.btnForward30.setOnClickListener {
            jumpBySeconds(+30)
        }

        // --- Réduire / Ouvrir le panneau de commandes en bas (bouton + swipe vers le bas/haut) ---
        binding.btnToggleBottomPanel.setOnClickListener {
            toggleBottomPanel(!isBottomPanelCollapsed)
        }
        binding.dragHandlePill.setOnClickListener {
            toggleBottomPanel(!isBottomPanelCollapsed)
        }
        setupBottomBarSwipeGesture()

        // --- Télécommande : Skip Intro Précis (AniSkip API ou +1m25) ---
        binding.btnSkipIntro.setOnClickListener {
            if (activePreparedStream == null) {
                Toast.makeText(this, "Lancez d'abord la vidéo sur la TV / Xbox pour sauter l'intro", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intro = currentIntroWindow
            if (intro != null && intro.endSec > currentPlaybackSec + 3) {
                val fmt = formatShortMinSec(intro.endSec)
                Toast.makeText(this, "⏭ Skip Intro AniSkip → $fmt", Toast.LENGTH_SHORT).show()
                seekToSeconds(intro.endSec)
            } else {
                Toast.makeText(this, "⏭ Skip Intro (+1m25)", Toast.LENGTH_SHORT).show()
                jumpBySeconds(+85)
            }
        }

        // --- Télécommande : Arrêter ---
        binding.btnStop.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isNotBlank()) {
                stopPositionTracker()
                activePreparedStream = null
                localStreamProxy.activeStream = null
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
     * Réduit (slide vers le bas) ou ré-ouvre (slide vers le haut) le panneau de commandes en bas de l'écran.
     */
    private fun toggleBottomPanel(collapse: Boolean) {
        isBottomPanelCollapsed = collapse
        val panel = binding.layoutCollapsibleControls
        if (collapse) {
            binding.btnToggleBottomPanel.text = "▲ Ouvrir"
            val heightToSlide = panel.height.takeIf { it > 0 }?.toFloat() ?: 220f
            panel.animate()
                .translationY(heightToSlide)
                .alpha(0f)
                .setDuration(190L)
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.translationY = 0f
                }
                .start()
        } else {
            binding.btnToggleBottomPanel.text = "▼ Réduire"
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationY = 180f
            panel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(190L)
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBottomBarSwipeGesture() {
        var downY = 0f
        val swipeListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - downY
                    if (deltaY > 38f && !isBottomPanelCollapsed) {
                        toggleBottomPanel(true)
                    } else if (deltaY < -38f && isBottomPanelCollapsed) {
                        toggleBottomPanel(false)
                    } else if (kotlin.math.abs(deltaY) < 12f) {
                        toggleBottomPanel(!isBottomPanelCollapsed)
                    }
                    true
                }
                else -> false
            }
        }
        binding.layoutStatusHeader.setOnTouchListener(swipeListener)
        binding.dragHandlePill.setOnTouchListener(swipeListener)
    }

    // =========================================================================
    // Gestion des Favoris / Séries en cours (1 clic + mise à jour auto de l'épisode)
    // =========================================================================

    private fun loadFavorites(): MutableList<FavoriteItem> {
        val list = mutableListOf<FavoriteItem>()
        val raw = prefs.getString(KEY_FAVORITES_JSON, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "")
                val url = obj.optString("url", "")
                val epIdx = obj.optInt("epIdx", -1)
                val epLabel = obj.optString("epLabel", "")
                if (title.isNotBlank() && url.isNotBlank()) {
                    list.add(FavoriteItem(title, url, epIdx, epLabel))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveFavorites(list: List<FavoriteItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("title", item.title)
                put("url", item.url)
                put("epIdx", item.episodeIndex)
                put("epLabel", item.episodeLabel)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITES_JSON, arr.toString()).apply()
    }

    private fun normalizeSeriesBaseUrl(url: String): String {
        return url.substringBefore("?").substringBefore("#").trimEnd('/').lowercase()
    }

    private fun updateFavoriteEpisodeIfPresent(pageUrl: String, epIdx: Int, epLabel: String) {
        if (pageUrl.isBlank() || epIdx < 0) return
        val favs = loadFavorites()
        val normPage = normalizeSeriesBaseUrl(pageUrl)
        var modified = false
        for (i in favs.indices) {
            if (normalizeSeriesBaseUrl(favs[i].url) == normPage) {
                if (favs[i].episodeIndex != epIdx || favs[i].episodeLabel != epLabel) {
                    favs[i] = favs[i].copy(url = pageUrl, episodeIndex = epIdx, episodeLabel = epLabel)
                    modified = true
                }
            }
        }
        if (modified) {
            saveFavorites(favs)
        }
    }

    private fun showFavoritesDialog() {
        val favs = loadFavorites()
        val currentUrl = binding.webViewBrowser.url ?: ""
        val asHome = remoteDomainSync.getAnimeSamaUrl()
        val fsHome = remoteDomainSync.getFrenchStreamUrl()
        val isOnSitePage = binding.layoutHomeHub.visibility != View.VISIBLE &&
            currentUrl.startsWith("http") &&
            currentUrl.trimEnd('/') != asHome.trimEnd('/') &&
            currentUrl.trimEnd('/') != fsHome.trimEnd('/')

        val options = mutableListOf<String>()
        if (isOnSitePage) {
            val displayTitle = currentPageTitle.ifBlank { currentUrl.substringAfter("://").take(35) }
            val epSuffix = if (currentEpisodeLabel.isNotBlank()) " ($currentEpisodeLabel)" else ""
            options.add("➕ Épingler en Favori : $displayTitle$epSuffix")
        }

        for (fav in favs) {
            val epInfo = if (fav.episodeLabel.isNotBlank()) " • ${fav.episodeLabel}" else ""
            options.add("⭐ ${fav.title}$epInfo")
        }

        if (options.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("⭐ Mes Favoris & Séries en cours")
                .setMessage("Vous n'avez pas encore de favoris.\n\nOuvrez la page d'un anime ou d'une série puis appuyez sur le bouton ⭐ en haut pour l'épingler ici en 1 clic !")
                .setPositiveButton("Compris", null)
                .show()
            return
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("⭐ Mes Favoris & Séries en cours")
            .setItems(options.toTypedArray()) { _, which ->
                if (isOnSitePage && which == 0) {
                    addCurrentPageToFavorites(currentUrl)
                } else {
                    val favIdx = if (isOnSitePage) which - 1 else which
                    val chosen = favs.getOrNull(favIdx) ?: return@setItems
                    if (chosen.episodeIndex >= 0) {
                        pendingRestoreEpisodeIdx = chosen.episodeIndex
                    }
                    loadBrowserUrl(chosen.url)
                    Toast.makeText(
                        this,
                        "Ouverture de ${chosen.title}${if (chosen.episodeLabel.isNotBlank()) " (${chosen.episodeLabel})" else ""}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Fermer", null)

        if (favs.isNotEmpty()) {
            builder.setNeutralButton("🗑 Supprimer un favori") { _, _ ->
                showDeleteFavoriteDialog(favs)
            }
        }
        builder.show()
    }

    private fun addCurrentPageToFavorites(currentUrl: String) {
        val favs = loadFavorites()
        val normUrl = normalizeSeriesBaseUrl(currentUrl)
        val cleanTitle = currentPageTitle.ifBlank {
            currentUrl.substringAfter("/catalogue/").substringBefore("/").replace("-", " ")
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "Série / Anime" }
        }
        val existingIdx = favs.indexOfFirst { normalizeSeriesBaseUrl(it.url) == normUrl }
        val newItem = FavoriteItem(
            title = cleanTitle,
            url = currentUrl,
            episodeIndex = currentEpisodeIndex,
            episodeLabel = currentEpisodeLabel
        )
        if (existingIdx >= 0) {
            favs[existingIdx] = newItem
        } else {
            favs.add(0, newItem)
        }
        saveFavorites(favs)
        Toast.makeText(this, "⭐ Ajouté aux favoris : $cleanTitle", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteFavoriteDialog(favs: MutableList<FavoriteItem>) {
        val labels = favs.map { "🗑 ${it.title}${if (it.episodeLabel.isNotBlank()) " (${it.episodeLabel})" else ""}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Supprimer quel favori ?")
            .setItems(labels) { _, which ->
                val removed = favs.removeAt(which)
                saveFavorites(favs)
                Toast.makeText(this, "Favori supprimé : ${removed.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            val listItems = mutableListOf<DlnaController.DiscoveredDevice>()
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
     * Saute exactement de +30s ou -30s dans l'épisode/film en cours sur la Xbox / TV.
     */
    private fun jumpBySeconds(deltaSec: Int) {
        seekToSeconds(currentPlaybackSec + deltaSec)
    }

    /**
     * Saute directement à la seconde choisie (via le curseur SeekBar, Skip Intro ou les boutons +/- 30s).
     */
    private fun seekToSeconds(rawTargetSec: Int) {
        if (isSeekingNow) return
        val ip = binding.etXboxIp.text?.toString()?.trim() ?: return
        val prepared = activePreparedStream ?: return

        val maxSeekSec = (prepared.totalDurationSec - 5).coerceAtLeast(0)
        val targetSec = rawTargetSec.coerceIn(0, maxSeekSec)

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
                        title = getDisplayStreamTitle(),
                        durationSeconds = prepared.totalDurationSec
                    )
                    if (res.isSuccess) {
                        isXboxPaused = false
                        binding.btnPlayPause.text = "⏸ PAUSE"
                        CastForegroundService.updateNotificationState(this@MainActivity, false, currentPlaybackSec)
                        saveCurrentPlaybackProgress()
                        updateTimerDisplay()
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    } else {
                        updateStatus("Erreur lors du saut : ${res.exceptionOrNull()?.localizedMessage}", isLoading = false)
                    }
                } else {
                    val seekRes = dlnaController.seekMedia(ip, targetSec)
                    if (seekRes.isSuccess) {
                        currentPlaybackSec = targetSec
                        CastForegroundService.updateNotificationState(this@MainActivity, isXboxPaused, currentPlaybackSec)
                        saveCurrentPlaybackProgress()
                        updateTimerDisplay()
                        updateStatus("Lecture en cours sur la TV / Xbox 🎮", isLoading = false)
                    }
                }
            } finally {
                isSeekingNow = false
            }
        }
    }

    private fun getDisplayStreamTitle(): String {
        val base = currentPageTitle.ifBlank { "Xbox Video Stream" }
        return if (currentEpisodeLabel.isNotBlank()) "$base • $currentEpisodeLabel" else base
    }

    private fun saveCurrentPlaybackProgress() {
        val pageUrl = binding.webViewBrowser.url ?: prefs.getString(KEY_LAST_PAGE, null) ?: return
        if (!pageUrl.startsWith("http")) return
        val title = getDisplayStreamTitle()
        prefs.edit()
            .putString(KEY_LAST_CAST_PAGE_URL, pageUrl)
            .putString(KEY_LAST_CAST_TITLE, title)
            .putInt(KEY_LAST_CAST_EP_IDX, currentEpisodeIndex)
            .putInt(KEY_LAST_CAST_POS_SEC, currentPlaybackSec)
            .apply()
    }

    /**
     * Chronomètre local ultra-fluide (1s) + sauvegarde auto du timestamp ("Reprendre où j'en étais")
     * + déclenchement automatique de l'épisode suivant à la fin.
     */
    private fun startPositionTracker() {
        stopPositionTracker()
        binding.tvTimer.visibility = View.VISIBLE
        updateTimerDisplay()
        positionTrackerJob = lifecycleScope.launch {
            var tickCount = 0
            while (isActive && activePreparedStream != null) {
                delay(1000)
                val prepared = activePreparedStream ?: break
                if (!isXboxPaused && !isSeekingNow && currentPlaybackSec < prepared.totalDurationSec) {
                    val serviceSec = CastForegroundService.currentPositionSec
                    currentPlaybackSec = maxOf(currentPlaybackSec + 1, serviceSec).coerceAtMost(prepared.totalDurationSec)
                    CastForegroundService.currentPositionSec = currentPlaybackSec
                    prepared.currentConfirmedPlaybackSec = currentPlaybackSec
                    updateTimerDisplay()
                    tickCount++

                    // Sauvegarder automatiquement la progression toutes les 4 secondes ("Reprendre où j'en étais")
                    if (tickCount % 4 == 0 && currentPlaybackSec > 10) {
                        saveCurrentPlaybackProgress()
                    }

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
        binding.tvTotalTime.text = totalStr
        val safeMax = prepared.totalDurationSec.coerceAtLeast(1)
        if (binding.seekBarProgress.max != safeMax) {
            binding.seekBarProgress.max = safeMax
        }
        if (!isUserDraggingSeekBar) {
            binding.tvCurrentTime.text = currentStr
            binding.seekBarProgress.progress = currentPlaybackSec.coerceIn(0, safeMax)
        }
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

    private fun handleTopBarSearchOrUrl(input: String) {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            loadBrowserUrl(input)
            return
        }
        if (input.contains(".") && !input.contains(" ")) {
            loadBrowserUrl("https://$input")
            return
        }
        val currentUrl = binding.webViewBrowser.url ?: ""
        if (currentUrl.contains("fs26", ignoreCase = true) ||
            currentUrl.contains("french-stream", ignoreCase = true) ||
            currentUrl.contains("fs", ignoreCase = true)
        ) {
            binding.layoutHomeHub.visibility = View.GONE
            val safeQuery = input.replace("\\", "\\\\").replace("'", "\\'")
            val jsSearchFs = """
                (function() {
                    var s = document.getElementById('story');
                    var f = document.getElementById('quicksearch');
                    if (s && f) {
                        s.value = '$safeQuery';
                        s.dispatchEvent(new Event('input', { bubbles: true }));
                        f.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                    }
                })();
            """.trimIndent()
            binding.webViewBrowser.evaluateJavascript(jsSearchFs, null)
            return
        }
        loadBrowserUrl(normalizeUrlOrSearch(input))
    }

    private fun normalizeUrlOrSearch(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input
        }
        if (input.contains(".") && !input.contains(" ")) {
            return "https://$input"
        }
        val asBase = remoteDomainSync.getAnimeSamaUrl().trimEnd('/')
        return "$asBase/catalogue/?search=${java.net.URLEncoder.encode(input, "UTF-8")}"
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
            val pageRef = withContext(Dispatchers.Main) { binding.webViewBrowser.url ?: remoteDomainSync.getAnimeSamaUrl() }
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

    private fun sendStreamToXbox(
        rawStreamUrl: String,
        referer: String,
        xboxIp: String,
        forceStartFromZero: Boolean = false
    ) {
        val currentPageUrl = binding.webViewBrowser.url ?: ""
        val savedCastPage = prefs.getString(KEY_LAST_CAST_PAGE_URL, null)
        val savedCastEpIdx = prefs.getInt(KEY_LAST_CAST_EP_IDX, -1)
        val savedCastSec = prefs.getInt(KEY_LAST_CAST_POS_SEC, 0)

        // Si on relance le même épisode / film et qu'une progression > 25s a été mémorisée, proposer de reprendre !
        if (!forceStartFromZero &&
            savedCastSec > 25 &&
            !savedCastPage.isNullOrBlank() &&
            normalizeSeriesBaseUrl(currentPageUrl) == normalizeSeriesBaseUrl(savedCastPage) &&
            (currentEpisodeIndex == savedCastEpIdx || savedCastEpIdx == -1)
        ) {
            runOnUiThread {
                val fmtTime = dlnaController.formatSecondsToHms(savedCastSec)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("⏳ Reprendre où vous en étiez ?")
                    .setMessage("Vous aviez arrêté cet épisode/film à $fmtTime.\nVoulez-vous reprendre directement à $fmtTime ?")
                    .setPositiveButton("▶ Reprendre à $fmtTime") { _, _ ->
                        executeStreamCastToDevice(rawStreamUrl, referer, xboxIp, initialStartSec = savedCastSec)
                    }
                    .setNegativeButton("Recommencer (0:00)") { _, _ ->
                        executeStreamCastToDevice(rawStreamUrl, referer, xboxIp, initialStartSec = 0)
                    }
                    .setCancelable(false)
                    .show()
            }
            return
        }

        executeStreamCastToDevice(rawStreamUrl, referer, xboxIp, initialStartSec = 0)
    }

    private fun executeStreamCastToDevice(
        rawStreamUrl: String,
        referer: String,
        xboxIp: String,
        initialStartSec: Int = 0
    ) {
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

            val safeStartSec = if (initialStartSec > 0 && initialStartSec < prepared.totalDurationSec - 15) {
                if (prepared.isHls && prepared.segments.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        localStreamProxy.warmupForSeek(prepared, initialStartSec)
                    }
                } else {
                    initialStartSec
                }
            } else {
                0
            }

            activePreparedStream = prepared
            localStreamProxy.activeStream = prepared
            currentPlaybackSec = safeStartSec
            isXboxPaused = false

            // Lancer la recherche AniSkip des horaires exacts de l'intro pour cet épisode
            val pageUrl = binding.webViewBrowser.url ?: ""
            refreshAniSkipIntroForCurrentEpisode(pageUrl, currentPageTitle, currentEpisodeNumber)

            val proxiedUrl = localStreamProxy.buildPlaybackUrl(prepared, startSeconds = safeStartSec)
            val streamTitle = getDisplayStreamTitle()

            val castResult = dlnaController.playMediaOnXbox(
                ip = xboxIp,
                videoUri = proxiedUrl,
                title = streamTitle,
                durationSeconds = prepared.totalDurationSec
            )

            castResult.onSuccess {
                val devName = prefs.getString(KEY_DEVICE_NAME, null) ?: "Xbox / TV ($xboxIp)"
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                CastForegroundService.startService(this@MainActivity, xboxIp, devName, streamTitle)
                CastForegroundService.currentPositionSec = safeStartSec
                saveCurrentPlaybackProgress()
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
        if (CastForegroundService.remoteActionListener === this) {
            CastForegroundService.remoteActionListener = null
        }
        stopPositionTracker()
        videoStreamSniffer.cleanup()
        // Ne JAMAIS arrêter localStreamProxy ni CastForegroundService si une diffusion est en cours :
        // l'application reste active dans la barre de notification même quand on ferme l'application !
        super.onDestroy()
    }
}
