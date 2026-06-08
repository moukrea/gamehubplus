package com.ghplus.patcher.update

import android.content.Context
import android.content.Intent
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

    /** Download the release APK into cacheDir/updates and return the local file. */
    suspend fun download(ctx: Context, r: Release): File = withContext(Dispatchers.IO) {
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
            conn.inputStream.use { input ->
                out.outputStream().use { input.copyTo(it) }
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
