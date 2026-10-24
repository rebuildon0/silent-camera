package com.rebuildon0.silentcamera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from the latest GitHub Release (version.json + APK). */
object Updater {
    private const val VERSION_URL =
        "https://github.com/rebuildon0/silent-camera/releases/latest/download/version.json"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val conn = open("$VERSION_URL?t=${System.currentTimeMillis()}")
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(text)
        Release(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            notes = json.optString("notes"),
        )
    }

    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "silent-camera-${release.versionCode}.apk")
            val conn = open(release.apkUrl)
            try {
                val total = conn.contentLengthLong
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            file
        }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        return conn
    }
}
