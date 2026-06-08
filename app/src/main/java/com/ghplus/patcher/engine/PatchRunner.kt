package com.ghplus.patcher.engine

import android.content.Context
import app.revanced.library.ApkUtils.applyTo
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatches
import app.revanced.patcher.patcher
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device ReVanced patch runner.
 *
 * Ports the in-process patch flow from ReVanced Manager v2.6.0:
 *   - patcher/runtime/CoroutineRuntime.kt  (the in-process runner — load bundle,
 *     select patches, set options, run)
 *   - patcher/Session.kt                   (patcher() -> apply -> ApkUtils.applyTo)
 *   - patcher/patch/PatchBundle.kt         (PatchBundle.Loader.patches via loadPatches)
 *
 * Reproduces the desktop CLI:
 *   revanced-cli patch -p patches.rvp -b --exclusive -e "<patch>"...
 *     -O packageName=com.xiaoji.egggameplus -O appName=GameHub+ -o out.apk in.apk
 *
 * The `-b` (bypass bundle verification) is implicit: the Android loadPatches
 * loads the dex bundle directly with no signature check. `--exclusive` is
 * reproduced by selecting only the patches named in [SELECTED] (others off).
 */
class PatchRunner(
    private val ctx: Context,
    private val log: (String) -> Unit,
) {
    private val logger = PatchLogger(log)

    companion object {
        const val OPT_PACKAGE_NAME = "com.xiaoji.egggameplus"
        const val OPT_APP_NAME = "GameHub+"

        /** The exact, exclusive patch set (matches the desktop `-e` list). */
        val SELECTED: List<String> = listOf(
            "Bypass login",
            "Disable Firebase Crashlytics",
            "Mute UI sounds",
            "PC-accurate vibration",
            "Vibration settings activity",
            "PC Vibration Settings label resource",
            "PC Vibration Settings menu row",
            "Show PC Game Settings row",
            "Show Game ID label resource",
            "Show Game ID menu row",
            "GOG library card (permanent)",
            "GOG activities (Phase 1)",
            "GOG menu row",
            "File manager access",
            "Banner Tools drawables",
            "Banner Tools menu row",
            "Per-game menu id capture (shared)",
            "Export/Import PC config label resources",
            "Export/Import PC config rows",
            "EmuReady Compatibility label resources",
            "EmuReady compatibility activity",
            "EmuReady Compatibility row",
            "EmuReady tile grade badge",
            "Change package name",
            "Change app name",
        )

        // Compose sound assets to silence (see CLAUDE.md). Entry names inside the
        // output APK zip whose path matches this prefix + .m4a are replaced with
        // the bundled silent clip.
        private const val SOUND_PREFIX =
            "assets/composeResources/com.xiaoji.egggame.core/files/sound/"
    }

    /**
     * Patches [inputApk] and returns the signed output APK in [ctx].cacheDir.
     */
    fun run(inputApk: File): File {
        val cache = ctx.cacheDir
        val tmp = File(cache, "tmp").also { it.deleteRecursively(); it.mkdirs() }
        val framework = File(cache, "framework").also { it.mkdirs() }

        // 1. Copy the bundled .rvp to cache (loadPatches reads a real File).
        val bundleFile = File(cache, "patches.rvp")
        ctx.assets.open("patches.rvp").use { input ->
            bundleFile.outputStream().use { input.copyTo(it) }
        }
        log("Loaded patch bundle")

        // 2. Resolve aapt2 (extracted native lib).
        val aapt = Aapt.binary(ctx)
            ?: throw IllegalStateException("Could not resolve bundled aapt2 (libaapt2.so)")
        log("aapt2: ${aapt.absolutePath}")

        // 3. Load all patches from the bundle (bypass verification = plain load).
        //    Mirrors PatchBundle.Loader.patches(...).
        val allPatches: Set<Patch> = loadPatches(
            bundleFile,
            onFailedToLoad = { f, t ->
                throw IllegalStateException("Failed to load bundle ${f.name}: ${t.message}", t)
            },
        )
        log("Bundle exposes ${allPatches.size} patches")

        // 4. Select EXACTLY the named patches (exclusive) and set their options.
        val byName = allPatches.filter { it.name != null }.associateBy { it.name!! }
        val selected = SELECTED.map { name ->
            byName[name] ?: throw IllegalArgumentException("Patch not found in bundle: \"$name\"")
        }
        byName["Change package name"]?.options?.set("packageName", OPT_PACKAGE_NAME)
        byName["Change app name"]?.options?.set("appName", OPT_APP_NAME)
        log("Selected ${selected.size} patches (exclusive)")

        // 5. Build the patcher (decodes manifest/resources). Mirrors Session.run's
        //    patcher(apkFile, temporaryFilesPath, frameworkFileDirectory, aaptBinaryPath).
        val output = File(cache, "GameHubPlus_patched.apk")
        val unsigned = File(cache, "GameHubPlus_unsigned.apk")
        val selectedSet = selected.toSet()

        logger.withJavaLogging {
            log("Reading APK and decoding resources...")
            val patcherFn = patcher(
                apkFile = inputApk,
                temporaryFilesPath = tmp,
                aaptBinaryPath = aapt,
                frameworkFileDirectory = framework.absolutePath,
            ) { _, _ -> selectedSet }

            // 6. Apply patches; emit per-patch results (mirrors applyPatchesVerbose).
            log("Applying patches...")
            val result = patcherFn { patchResult ->
                val ex = patchResult.exception
                if (ex != null) {
                    log("FAILED: ${patchResult.patch.name}: ${ex.message}")
                    throw ex
                } else {
                    log("OK: ${patchResult.patch.name}")
                }
            }

            // 7. Write patched dex + resources onto a copy of the source APK.
            //    Mirrors Session.run: copy input -> result.applyTo(patched).
            log("Writing patched APK...")
            inputApk.copyTo(unsigned, overwrite = true)
            result.applyTo(unsigned)
        }

        // 8. Mute UI sounds: swap every sound/*.m4a entry for the silent clip.
        val muted = File(cache, "GameHubPlus_muted.apk")
        muteSounds(unsigned, muted)

        // 9. Sign with the bundled debug keystore (apksig; aligns too).
        log("Signing...")
        ApkSign(ctx).sign(muted, output, minSdk = 29)
        log("Done: ${output.name}")

        tmp.deleteRecursively()
        unsigned.delete()
        muted.delete()
        return output
    }

    /**
     * Copies [input] to [output], replacing every compose sound .m4a entry with
     * the bundled silent clip. Reproduces the desktop post-step that silences UI
     * sounds at the asset level.
     */
    private fun muteSounds(input: File, output: File) {
        val silent = ctx.assets.open("silent.m4a").use { it.readBytes() }
        var replaced = 0
        ZipFile(input).use { zin ->
            ZipOutputStream(output.outputStream().buffered()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val isSound = e.name.startsWith(SOUND_PREFIX) && e.name.endsWith(".m4a")
                    val outEntry = ZipEntry(e.name).apply {
                        method = ZipEntry.STORED.let { if (isSound) it else e.method }
                    }
                    if (isSound) {
                        // STORED requires size + crc up front.
                        val crc = java.util.zip.CRC32().apply { update(silent) }
                        outEntry.method = ZipEntry.STORED
                        outEntry.size = silent.size.toLong()
                        outEntry.compressedSize = silent.size.toLong()
                        outEntry.crc = crc.value
                        zout.putNextEntry(outEntry)
                        zout.write(silent)
                        zout.closeEntry()
                        replaced++
                    } else {
                        // Preserve original entry verbatim (re-deflate is fine; the
                        // final apksig step re-aligns the zip anyway).
                        val plain = ZipEntry(e.name)
                        zout.putNextEntry(plain)
                        zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
            }
        }
        log("Muted $replaced UI sound asset(s)")
    }
}
