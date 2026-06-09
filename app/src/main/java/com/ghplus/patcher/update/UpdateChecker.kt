package com.ghplus.patcher.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ghplus.patcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Resolved information about the latest GitHub release of this patcher. */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * Checks the public GitHub repo for a newer patcher build and installs it.
 *
 * Releases attach the patcher APK named like
 * `GameHubPlusPatcher-<versionName>-<versionCode>.apk`; the trailing integer is
 * the authoritative version compared against [BuildConfig.VERSION_CODE].
 */
object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/moukrea/gamehubplus/releases/latest"

    private val APK_NAME = Regex("""GameHubPlusPatcher-.*-(\d+)\.apk""")

    /** Fetch the latest release; returns null on any error or if no APK asset is found. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GameHubPlusPatcher")
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

                val tag = json.optString("tag_name")
                val name = json.optString("name").ifBlank { tag }
                val notes = json.optString("body")

                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                var versionCode = -1
                var versionName = name.ifBlank { tag }

                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name")
                    val match = APK_NAME.matchEntire(assetName) ?: continue
                    apkUrl = asset.optString("browser_download_url")
                    versionCode = match.groupValues[1].toInt()
                    // Recover the versionName portion between the prefix and the
                    // trailing "-<versionCode>.apk".
                    versionName = assetName
                        .removePrefix("GameHubPlusPatcher-")
                        .removeSuffix("-${match.groupValues[1]}.apk")
                        .ifBlank { tag }
                    break
                }

                val url = apkUrl ?: return@withContext null

                // Fallback: derive versionCode from the tag (e.g. "v1.0-2" or "2").
                if (versionCode < 0) {
                    versionCode = Regex("""(\d+)\D*$""").find(tag)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@withContext null
                }

                Release(versionCode, versionName, url, notes)
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** True when the release is newer than the installed build. */
    fun isNewer(r: Release): Boolean = r.versionCode > BuildConfig.VERSION_CODE

    // --- install-permission handling (Android 8+ per-app "install unknown apps") ---

    /** True if the app may currently install packages ("install unknown apps" granted). */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Open the system screen where the user allows this app to install packages. */
    fun openInstallSettings(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    // --- "skip this version" so a declined update stops nagging until a newer one ships ---

    private const val PREFS = "update_prefs"
    private const val KEY_SKIPPED = "skipped_version_code"

    fun skippedVersionCode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SKIPPED, -1)

    fun setSkippedVersionCode(ctx: Context, code: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, code).apply()
    }

    /**
     * Download the release APK into cacheDir/updates, reporting [onProgress] in 0f..1f
     * (or -1f while the total size is unknown). Returns the local file.
     */
    suspend fun download(
        ctx: Context,
        r: Release,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val name = r.apkUrl.substringAfterLast('/').ifBlank { "update.apk" }
        val out = File(dir, name)

        val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "GameHubPlusPatcher")
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        done += read
                        onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /** Launch the system package installer for [file] via the shared FileProvider. */
    fun installApk(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
