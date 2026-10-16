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

    companion object {
        private const val PREFS_NAME = "xbox_caster_prefs"
        private const val KEY_XBOX_IP = "key_xbox_ip"
        private const val KEY_LAST_PAGE = "key_last_page"
        private const val DEFAULT_XBOX_IP = "192.168.1.52"
        private const val ANIME_SAMA_HOME = "https://anime-sama.to/"
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
            val savedPage = prefs.getString(KEY_LAST_PAGE, ANIME_SAMA_HOME) ?: ANIME_SAMA_HOME
            loadBrowserUrl(savedPage)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun checkAppUpdateAuto() {
        lifecycleScope.launch {
            val currentVersionCode = getCurrentVersionCode()
            val release = appUpdater.checkForUpdate(currentVersionCode)
            if (release != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🚀 Mise à jour disponible (${release.tagName})")
                    .setMessage("Une nouvelle version de Xbox Video Caster est disponible sur GitHub. Voulez-vous la télécharger et l'installer automatiquement ?")
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
            settings.setSupportMultipleWindows(false) // Bloque l'ouverture de popups publicitaires !
            settings.javaScriptCanOpenWindowsAutomatically = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStreamDetected(streamUrl: String, referer: String) {
                    if (streamUrl.startsWith("http")) {
                        onNewVideoStreamFound(streamUrl, referer)
                    }
                }

                @JavascriptInterface
                fun onIframeDetected(iframeSrc: String) {
                    if (iframeSrc.startsWith("http") && iframeSrc != currentIframeEmbedUrl) {
                        currentIframeEmbedUrl = iframeSrc
                        lifecycleScope.launch(Dispatchers.IO) {
                            val extracted = videoStreamSniffer.tryExtractDirectStreamFromEmbedHtml(
                                iframeSrc,
                                "https://anime-sama.to/"
                            )
                            if (extracted != null) {
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
                    if (VideoStreamSniffer.isAdUrl(target)) {
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
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectSmartAnimeSamaWatcher()
                }
            }
        }
    }

    private fun injectSmartAnimeSamaWatcher() {
        val js = """
            (function() {
                if (window.__xboxWatcherInstalled) return;
                window.__xboxWatcherInstalled = true;

                var style = document.createElement('style');
                style.innerHTML = '.ad-container, #bannLeft, #bannRight, #bannBot, #squBot, #bandeau-pubs-nuit { display: none !important; }';
                document.head.appendChild(style);

                setInterval(function() {
                    try {
                        var iframe = document.getElementById('playerDF') || document.querySelector('iframe[src*="embed"], iframe[src*="ansembed"], iframe[src*="vidmoly"], iframe[src*="sendvid"], iframe[src*="minochinos"]');
                        if (iframe && iframe.src && iframe.src.startsWith('http')) {
                            XboxBridge.onIframeDetected(iframe.src);
                        }

                        var vids = document.querySelectorAll('video, source');
                        for (var i = 0; i < vids.length; i++) {
                            var s = vids[i].src || vids[i].currentSrc;
                            if (s && s.startsWith('http')) {
                                XboxBridge.onStreamDetected(s, window.location.href);
                            }
                        }
                    } catch (e) {}
                }, 1200);
            })();
        """.trimIndent()
        binding.webViewBrowser.evaluateJavascript(js, null)
    }

    private fun onNewVideoStreamFound(streamUrl: String, referer: String) {
        lastDetectedStreamUrl = streamUrl
        lastDetectedReferer = referer
        runOnUiThread {
            updateStatus("✅ Flux vidéo prêt ! Appuyez sur le bouton vert pour caster.", isLoading = false)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.xbox_green))
        }
    }

    private fun setupListeners() {
        binding.btnHomeAnimeSama.setOnClickListener {
            loadBrowserUrl(ANIME_SAMA_HOME)
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

        binding.btnCast.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isBlank()) {
                Toast.makeText(this, "Veuillez entrer l'IP de la Xbox", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_XBOX_IP, ip).apply()

            val readyStream = lastDetectedStreamUrl
            if (!readyStream.isNullOrBlank()) {
                sendStreamToXbox(readyStream, lastDetectedReferer, ip)
                return@setOnClickListener
            }

            val targetUrl = currentIframeEmbedUrl ?: binding.webViewBrowser.url ?: binding.etVideoUrl.text.toString().trim()
            if (targetUrl.isBlank()) {
                Toast.makeText(this, "Ouvrez d'abord une page vidéo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startExtractionAndCast(targetUrl, ip)
        }

        binding.btnStop.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isNotBlank()) {
                lifecycleScope.launch {
                    val result = dlnaController.stopMedia(ip)
                    if (result.isSuccess) {
                        updateStatus("Lecture arrêtée sur la Xbox", isLoading = false)
                        binding.btnStop.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Lecture arrêtée", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webViewBrowser.canGoBack()) {
                    binding.webViewBrowser.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
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
        lastDetectedStreamUrl = null
        currentIframeEmbedUrl = null
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        updateStatus("Sélectionnez votre épisode sur la page...", isLoading = false)
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
            if (!iframeUrl.isNullOrBlank()) {
                val extractedFromIframe = withContext(Dispatchers.IO) {
                    videoStreamSniffer.tryExtractDirectStreamFromEmbedHtml(iframeUrl, "https://anime-sama.to/")
                }
                if (extractedFromIframe != null) {
                    sendStreamToXbox(extractedFromIframe.first, extractedFromIframe.second, ip)
                    return@launch
                }
            }

            val smartResult = videoStreamSniffer.extractFromUrlSmart(url)
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
                    updateStatus(message, isLoading = false)
                    binding.btnCast.isEnabled = true
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun sendStreamToXbox(rawStreamUrl: String, referer: String, xboxIp: String) {
        runOnUiThread {
            updateStatus(getString(R.string.status_sending), isLoading = true)
        }

        lifecycleScope.launch {
            val proxiedUrl = localStreamProxy.createProxyUrl(
                realStreamUrl = rawStreamUrl,
                referer = referer,
                userAgent = VideoStreamSniffer.USER_AGENT
            )

            val castResult = dlnaController.playMediaOnXbox(
                ip = xboxIp,
                videoUri = proxiedUrl,
                title = "Anime-Sama / Xbox Stream"
            )

            castResult.onSuccess {
                updateStatus(getString(R.string.status_playing), isLoading = false)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.xbox_green))
                binding.btnCast.isEnabled = true
                binding.btnStop.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "Lecture lancée sur la Xbox ! 🎮", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                val directTry = dlnaController.playMediaOnXbox(ip = xboxIp, videoUri = rawStreamUrl)
                if (directTry.isSuccess) {
                    updateStatus(getString(R.string.status_playing), isLoading = false)
                    binding.btnCast.isEnabled = true
                    binding.btnStop.visibility = View.VISIBLE
                } else {
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
    }

    private fun updateStatus(text: String, isLoading: Boolean) {
        binding.tvStatus.text = text
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        videoStreamSniffer.cleanup()
        localStreamProxy.stop()
        super.onDestroy()
    }
}
