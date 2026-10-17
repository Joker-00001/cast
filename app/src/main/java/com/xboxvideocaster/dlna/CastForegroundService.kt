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

/**
 * Service Android de premier plan (Foreground Service) avec :
 * 1. WakeLock CPU + WifiLock (WIFI_MODE_FULL_HIGH_PERF).
 * 2. Piste AudioTrack 100% silencieuse (PCM 0) en boucle : empêche Android (Samsung OneUI, Xiaomi MIUI, Doze Mode)
 *    de couper le Wi-Fi ou de geler l'application au bout de 5 minutes quand l'écran du téléphone est en veille !
 * 3. Ping Wi-Fi actif toutes les 10s vers la TV / Xbox pour maintenir l'antenne Wi-Fi éveillée en continu.
 */
class CastForegroundService : Service() {

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
        private const val EXTRA_DEVICE_IP = "extra_device_ip"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        @Volatile
        var isCastActive: Boolean = false

        fun startService(context: Context, deviceIp: String = "192.168.1.52", deviceName: String = "Xbox / TV") {
            isCastActive = true
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

        fun stopService(context: Context) {
            isCastActive = false
            val intent = Intent(context, CastForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        startSilentAudioAndWifiKeepAlive()
        // S'assurer que le serveur proxy reste démarré au niveau du processus
        LocalStreamProxy.getInstance(applicationContext).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAST) {
            isCastActive = false
            stopSilentAudioAndWifiKeepAlive()
            val ipToStop = currentDeviceIp
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DlnaController().stopMedia(ipToStop)
                } catch (_: Exception) {
                }
            }
            LocalStreamProxy.getInstance(applicationContext).activeStream = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_DEVICE_IP)?.let { currentDeviceIp = it }
        intent?.getStringExtra(EXTRA_DEVICE_NAME)?.let { currentDeviceName = it }

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

    /**
     * Joue un flux PCM 100% silencieux (zéros, volume 0) et envoie un ping Wi-Fi vers la TV/Xbox
     * pour qu'Android considère l'application comme un lecteur audio actif (comme Spotify)
     * et ne mette JAMAIS la puce Wi-Fi en veille au bout de 5 minutes d'écran éteint !
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

                val silenceBytes = ByteArray(sampleRate * 2) // 1 seconde de silence PCM
                var tick = 0
                while (keepAliveThreadRunning && isCastActive) {
                    try {
                        track.write(silenceBytes, 0, silenceBytes.size)
                    } catch (_: Exception) {
                    }
                    tick++
                    // Toutes les 8 secondes, réveiller la table ARP / Wi-Fi vers l'IP de la TV / Xbox
                    if (tick % 8 == 0) {
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

        val stopIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_STOP_CAST
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📺 Diffusion active sur $currentDeviceName")
            .setContentText("Flux vidéo actif en arrière-plan • Touchez pour ouvrir la télécommande")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_media_pause, "⏹ Arrêter le Cast", pendingStop)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ne pas arrêter le service quand l'utilisateur ferme l'application depuis le multitâche :
        // la notification reste dans la barre de notif et la vidéo continue sur la TV / Xbox !
        if (isCastActive) {
            acquireLocks()
            startSilentAudioAndWifiKeepAlive()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification())
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
                // IMPORTANT : WIFI_MODE_FULL_HIGH_PERF reste actif même quand l'écran s'éteint
                // (contrairement à WIFI_MODE_FULL_LOW_LATENCY qu'Android désactive dès que l'écran s'éteint !)
                @Suppress("DEPRECATION")
                val lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF
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
                description = "Affiche la notification permanente pendant la diffusion vidéo sur la TV ou la Xbox"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopSilentAudioAndWifiKeepAlive()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
