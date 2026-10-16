package com.xboxvideocaster.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionCode: Int,
    val tagName: String,
    val apkDownloadUrl: String
)

class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        // Dépôt GitHub de l'utilisateur
        private const val GITHUB_REPO = "Joker-00001/cast"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    /**
     * Vérifie sur GitHub Releases si une version plus récente est disponible
     */
    suspend fun checkForUpdate(currentVersionCode: Int): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "XboxVideoCaster-Updater")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "") // ex: "v4"
                val remoteBuildNumber = tagName.removePrefix("v").toIntOrNull() ?: return@withContext null

                if (remoteBuildNumber > currentVersionCode) {
                    val assets = json.optJSONArray("assets") ?: return@withContext null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            return@withContext ReleaseInfo(
                                versionCode = remoteBuildNumber,
                                tagName = tagName,
                                apkDownloadUrl = downloadUrl
                            )
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Télécharge l'APK directement dans le cache de l'application (contourne le bug de Chrome Mobile !)
     * et lance l'installateur natif Android.
     */
    suspend fun downloadAndInstallApk(
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "XboxVideoCaster-Updater")
                .build()

            val updateFile = File(context.cacheDir, "XboxVideoCaster-update.apk")
            if (updateFile.exists()) updateFile.delete()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Téléchargement échoué (${response.code})"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Fichier APK vide"))
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                onProgress(percent)
                            }
                        }
                        output.flush()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                installApkFile(updateFile)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun installApkFile(apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
