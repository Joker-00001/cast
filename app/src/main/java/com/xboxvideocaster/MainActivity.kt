package com.xboxvideocaster

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xboxvideocaster.databinding.ActivityMainBinding
import com.xboxvideocaster.dlna.DlnaController
import com.xboxvideocaster.extractor.VideoStreamSniffer
import com.xboxvideocaster.extractor.YouTubeExtractor
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val dlnaController = DlnaController()
    private val youtubeExtractor = YouTubeExtractor()
    private lateinit var videoStreamSniffer: VideoStreamSniffer

    companion object {
        private const val PREFS_NAME = "xbox_caster_prefs"
        private const val KEY_XBOX_IP = "key_xbox_ip"
        private const val DEFAULT_XBOX_IP = "192.168.1.52"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        videoStreamSniffer = VideoStreamSniffer(this)

        setupIpPersistence()
        setupListeners()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun setupIpPersistence() {
        val savedIp = prefs.getString(KEY_XBOX_IP, DEFAULT_XBOX_IP)
        binding.etXboxIp.setText(savedIp)
    }

    private fun setupListeners() {
        binding.btnCast.setOnClickListener {
            val url = binding.etVideoUrl.text?.toString()?.trim() ?: ""
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""

            if (ip.isBlank()) {
                Toast.makeText(this, "Veuillez renseigner l'adresse IP de la console", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (url.isBlank()) {
                Toast.makeText(this, "Veuillez coller ou partager une URL vidéo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Sauvegarde de l'adresse IP
            prefs.edit().putString(KEY_XBOX_IP, ip).apply()

            startExtractionAndCast(url, ip)
        }

        binding.btnStop.setOnClickListener {
            val ip = binding.etXboxIp.text?.toString()?.trim() ?: ""
            if (ip.isNotBlank()) {
                lifecycleScope.launch {
                    val result = dlnaController.stopMedia(ip)
                    if (result.isSuccess) {
                        updateStatus(getString(R.string.status_ready), isLoading = false)
                        binding.btnStop.visibility = View.GONE
                        binding.btnCast.isEnabled = true
                        Toast.makeText(this@MainActivity, "Lecture interrompue sur la Xbox", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Erreur Stop: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Si le texte partagé contient du texte descriptif avec un lien, isoler le lien http
                val extractedUrl = findUrlInText(sharedText)
                binding.etVideoUrl.setText(extractedUrl ?: sharedText)

                // Lancement automatique si l'IP est configurée
                val ip = binding.etXboxIp.text?.toString()?.trim()
                if (!ip.isNullOrBlank()) {
                    startExtractionAndCast(binding.etVideoUrl.text.toString().trim(), ip)
                }
            }
        }
    }

    private fun findUrlInText(text: String): String? {
        val regex = "(https?://[\\w-]+(\\.[\\w-]+)+(/[#\\w-./?%&=]*)?)".toRegex()
        return regex.find(text)?.value
    }

    private fun startExtractionAndCast(url: String, ip: String) {
        binding.btnCast.isEnabled = false
        updateStatus(getString(R.string.status_extracting), isLoading = true)

        // Cas 1 : URL YouTube directe ou partagée
        if (youtubeExtractor.isYouTubeUrl(url)) {
            val videoId = youtubeExtractor.extractVideoId(url)
            if (videoId != null) {
                lifecycleScope.launch {
                    val streamResult = youtubeExtractor.resolveDirectStreamUrl(videoId)
                    streamResult.onSuccess { streamUrl ->
                        sendToXbox(streamUrl, ip)
                    }.onFailure { error ->
                        // Repli sur le renifleur WebView si Invidious échoue
                        sniffStreamViaWebView(url, ip)
                    }
                }
                return
            }
        }

        // Cas 2 : Streaming classique (Anime-Sama, Sendvid, Sibnet, Vidmoly...)
        sniffStreamViaWebView(url, ip)
    }

    private fun sniffStreamViaWebView(url: String, ip: String) {
        videoStreamSniffer.extractStream(url, object : VideoStreamSniffer.Callback {
            override fun onStreamFound(streamUrl: String) {
                sendToXbox(streamUrl, ip)
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

    private fun sendToXbox(streamUrl: String, ip: String) {
        runOnUiThread {
            updateStatus(getString(R.string.status_sending), isLoading = true)
        }

        lifecycleScope.launch {
            val castResult = dlnaController.playMediaOnXbox(ip = ip, videoUri = streamUrl)
            castResult.onSuccess {
                updateStatus(getString(R.string.status_playing), isLoading = false)
                binding.btnCast.isEnabled = true
                binding.btnStop.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "Lecture lancée sur la console Xbox !", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                updateStatus("Erreur lors de l'envoi : ${error.localizedMessage}", isLoading = false)
                binding.btnCast.isEnabled = true
                Toast.makeText(this@MainActivity, "Erreur DLNA : ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus(text: String, isLoading: Boolean) {
        binding.tvStatus.text = text
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        videoStreamSniffer.cleanup()
        super.onDestroy()
    }
}
