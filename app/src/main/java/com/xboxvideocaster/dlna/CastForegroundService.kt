package com.xboxvideocaster.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xboxvideocaster.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Service Android de premier plan (Foreground Service) avec :
 * 1. WakeLock CPU + WifiLock (WIFI_MODE_FULL_HIGH_PERF).
 * 2. Piste AudioTrack 100% silencieuse (PCM 0) en boucle : empêche Android (Samsung OneUI, Xiaomi MIUI, Doze Mode)
 *    de couper le Wi-Fi ou de geler l'application quand l'écran du téléphone est en veille !
 * 3. Horloge matérielle 1s intégrée : met à jour `currentConfirmedPlaybackSec` même écran éteint.
 * 4. 3 Boutons toujours visibles dans la barre de notification :
 *    [⏸ Pause / ▶ Play]  |  [+30s ⏩]  |  [❌ Fermer l'app]
 */
class CastForegroundService : Service() {

    interface RemoteActionListener {
        fun onNotificationPlayPauseToggled(isPaused: Boolean)
        fun onNotificationSeekCompleted(newPositionSec: Int)
        fun onNotificationStopped()
        fun onNotificationCloseApp()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var silentAudioTrack: AudioTrack? = null
    @Volatile
    private var keepAliveThreadRunning = false
    private var currentDeviceIp: String = "192.168.1.52"
    private var currentDeviceName: String = "Xbox / TV"

    companion object {
        private const val CHANNEL_ID = "xbox_cast_active_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP_CAST = "com.xboxvideocaster.ACTION_STOP_CAST"
        const val ACTION_CLOSE_APP = "com.xboxvideocaster.ACTION_CLOSE_APP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.xboxvideocaster.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_FORWARD_30 = "com.xboxvideocaster.ACTION_FORWARD_30"

        private const val EXTRA_DEVICE_IP = "extra_device_ip"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        @Volatile
        var isCastActive: Boolean = false

        @Volatile
        var isPausedState: Boolean = false

        @Volatile
        var currentPositionSec: Int = 0

        @Volatile
        var currentTitleText: String = "Flux vidéo actif"

        @Volatile
        var remoteActionListener: RemoteActionListener? = null

        @Volatile
        var serviceInstance: CastForegroundService? = null

        fun startService(
            context: Context,
            deviceIp: String = "192.168.1.52",
            deviceName: String = "Xbox / TV",
            title: String = "Flux vidéo actif"
        ) {
            isCastActive = true
            isPausedState = false
            currentTitleText = title
            val intent = Intent(context, CastForegroundService::class.java).apply {
                putExtra(EXTRA_DEVICE_IP, deviceIp)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateNotificationState(context: Context, isPaused: Boolean, positionSec: Int, title: String? = null) {
            isPausedState = isPaused
            currentPositionSec = positionSec
            if (!title.isNullOrBlank()) {
                currentTitleText = title
            }
            val inst = serviceInstance
            if (inst != null && isCastActive) {
                inst.refreshNotification()
            } else if (isCastActive) {
                val intent = Intent(context, CastForegroundService::class.java)
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                }
            }
        }

        fun stopService(context: Context) {
            isCastActive = false
            isPausedState = false
            val intent = Intent(context, CastForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        acquireLocks()
        startSilentAudioAndWifiKeepAlive()
        LocalStreamProxy.getInstance(applicationContext).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_DEVICE_IP)?.let { currentDeviceIp = it }
        intent?.getStringExtra(EXTRA_DEVICE_NAME)?.let { currentDeviceName = it }

        when (intent?.action) {
            ACTION_CLOSE_APP, ACTION_STOP_CAST -> {
                val shouldCloseApp = (intent.action == ACTION_CLOSE_APP)
                isCastActive = false
                isPausedState = false
                stopSilentAudioAndWifiKeepAlive()
                val ipToStop = currentDeviceIp
                Thread {
                    try {
                        kotlinx.coroutines.runBlocking {
                            DlnaController().stopMedia(ipToStop)
                        }
                    } catch (_: Exception) {
                    }
                }.start()
                LocalStreamProxy.getInstance(applicationContext).activeStream = null
                if (shouldCloseApp) {
                    remoteActionListener?.onNotificationCloseApp()
                } else {
                    remoteActionListener?.onNotificationStopped()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                if (shouldCloseApp) {
                    Thread {
                        try {
                            Thread.sleep(350)
                        } catch (_: Exception) {
                        }
                        exitProcess(0)
                    }.start()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PLAY_PAUSE -> {
                val ip = currentDeviceIp
                val nextPaused = !isPausedState
                isPausedState = nextPaused
                refreshNotification()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dlna = DlnaController()
                        if (nextPaused) {
                            dlna.pauseMedia(ip)
                        } else {
                            dlna.resumeMedia(ip)
                        }
                        remoteActionListener?.onNotificationPlayPauseToggled(nextPaused)
                    } catch (_: Exception) {
                    }
                }
                return START_STICKY
            }

            ACTION_FORWARD_30 -> {
                performSeekFromNotification(+30)
                return START_STICKY
            }
        }

        acquireLocks()
        startSilentAudioAndWifiKeepAlive()
        LocalStreamProxy.getInstance(applicationContext).start()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun performSeekFromNotification(deltaSec: Int) {
        val proxy = LocalStreamProxy.getInstance(applicationContext)
        val prepared = proxy.activeStream ?: return
        val ip = currentDeviceIp
        val maxSeek = (prepared.totalDurationSec - 5).coerceAtLeast(0)
        val estimatedCurrent = maxOf(currentPositionSec, prepared.currentConfirmedPlaybackSec)
        val targetSec = (estimatedCurrent + deltaSec).coerceIn(0, maxSeek)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dlna = DlnaController()
                if (prepared.isHls && prepared.segments.isNotEmpty()) {
                    val actualSec = proxy.warmupForSeek(prepared, targetSec)
                    currentPositionSec = actualSec
                    isPausedState = false
                    val newUrl = proxy.buildPlaybackUrl(prepared, actualSec)
                    dlna.playMediaOnXbox(
                        ip = ip,
                        videoUri = newUrl,
                        title = currentTitleText,
                        durationSeconds = prepared.totalDurationSec
                    )
                    refreshNotification()
                    remoteActionListener?.onNotificationSeekCompleted(actualSec)
                } else {
                    dlna.seekMedia(ip, targetSec)
                    currentPositionSec = targetSec
                    prepared.currentConfirmedPlaybackSec = targetSec
                    refreshNotification()
                    remoteActionListener?.onNotificationSeekCompleted(targetSec)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshNotification() {
        if (!isCastActive) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Joue un flux PCM 100% silencieux (zéros, volume 0) d'exactement 1 seconde par tour de boucle :
     * 1. Empêche Android de mettre la puce Wi-Fi en veille quand l'écran est éteint.
     * 2. Fait avancer `currentConfirmedPlaybackSec` chaque seconde même si l'application est réduite ou l'écran éteint !
     * 3. Envoie un ping réseau vers la TV toutes les 6 secondes.
     */
    private fun startSilentAudioAndWifiKeepAlive() {
        if (keepAliveThreadRunning) return
        keepAliveThreadRunning = true

        thread(start = true, isDaemon = true, name = "CastStandbyKeepAlive") {
            try {
                val sampleRate = 8000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sampleRate * 2)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.setVolume(0.0f)
                track.play()
                silentAudioTrack = track

                val silenceBytes = ByteArray(sampleRate * 2) // Exactement 1 seconde de silence PCM 16-bit 8000Hz
                var tick = 0
                while (keepAliveThreadRunning && isCastActive) {
                    try {
                        track.write(silenceBytes, 0, silenceBytes.size)
                    } catch (_: Exception) {
                        Thread.sleep(1000)
                    }
                    tick++

                    // Mettre à jour l'horloge de lecture confirmée même quand le téléphone est en veille !
                    if (!isPausedState) {
                        currentPositionSec += 1
                        val active = LocalStreamProxy.getInstance(applicationContext).activeStream
                        if (active != null && currentPositionSec <= active.totalDurationSec) {
                            active.currentConfirmedPlaybackSec = currentPositionSec
                        }
                    }

                    // Toutes les 6 secondes, réveiller la table ARP / Wi-Fi vers l'IP de la TV / Xbox
                    if (tick % 6 == 0) {
                        try {
                            InetAddress.getByName(currentDeviceIp).isReachable(1500)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    silentAudioTrack?.stop()
                    silentAudioTrack?.release()
                } catch (_: Exception) {
                }
                silentAudioTrack = null
                keepAliveThreadRunning = false
            }
        }
    }

    private fun stopSilentAudioAndWifiKeepAlive() {
        keepAliveThreadRunning = false
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (_: Exception) {
        }
        silentAudioTrack = null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val pendingToggle = PendingIntent.getService(
            this,
            3,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeAppIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_CLOSE_APP
        }
        val pendingCloseApp = PendingIntent.getService(
            this,
            1,
            closeAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseLabel = if (isPausedState) "▶ Play" else "⏸ Pause"
        val playPauseIcon = if (isPausedState) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val statusPrefix = if (isPausedState) "⏸ En pause" else "▶ En lecture"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📺 $currentDeviceName • $currentTitleText")
            .setContentText("$statusPrefix • Touchez pour ouvrir l'application")
            .setSmallIcon(playPauseIcon)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(playPauseIcon, playPauseLabel, pendingToggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "❌ Fermer l'app", pendingCloseApp)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ne pas arrêter le service quand l'utilisateur balaie l'application depuis le multitâche :
        // elle reste active dans la barre de notif jusqu'à ce qu'on clique sur "❌ Fermer l'app" !
        if (isCastActive) {
            acquireLocks()
            startSilentAudioAndWifiKeepAlive()
            refreshNotification()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XboxVideoCaster::ProxyWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L) // Max 6h
                }
            }

            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "XboxVideoCaster::ProxyWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Diffusion TV / Xbox en cours",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Affiche la télécommande permanente et le bouton Fermer l'app pendant la diffusion"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (serviceInstance === this) {
            serviceInstance = null
        }
        stopSilentAudioAndWifiKeepAlive()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
