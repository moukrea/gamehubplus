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

        /** Always applied (rebrand so it installs alongside stock + the per-game
         *  id capture that the menu-row features depend on). Not user-toggleable. */
        val ALWAYS: List<String> = listOf(
            "Change package name",
            "Change app name",
            "Per-game menu id capture (shared)",
        )

        /** User-toggleable features -> the patches each enables. Order = UI order;
         *  the key is the label shown in the app. "Mute UI sounds" has no patch
         *  (done as a post-step) — handled via the `mute` flag. */
        val FEATURES: LinkedHashMap<String, List<String>> = linkedMapOf(
            "Bypass login" to listOf("Bypass login"),
            "PC-accurate vibration" to listOf(
                "PC-accurate vibration",
                "Vibration settings activity",
                "PC Vibration Settings label resource",
                "PC Vibration Settings menu row",
            ),
            // NOTE: "GOG library card (permanent)" is omitted — it's retired on
            // 6.0.8 (its fingerprint matches nothing → "Collection is empty").
            // GOG is reachable via its activities + menu row.
            "GOG integration" to listOf(
                "GOG activities (Phase 1)",
                "GOG menu row",
            ),
            "File manager access" to listOf("File manager access"),
            "Disable Firebase Crashlytics" to listOf("Disable Firebase Crashlytics"),
            "Show Game ID" to listOf(
                "Show Game ID label resource",
                "Show Game ID menu row",
            ),
            "Show PC Game Settings row" to listOf("Show PC Game Settings row"),
            "Export/Import PC config" to listOf(
                "Export/Import PC config label resources",
                "Export/Import PC config rows",
            ),
            "EmuReady compatibility" to listOf(
                "EmuReady Compatibility label resources",
                "EmuReady compatibility activity",
                "EmuReady Compatibility row",
                "EmuReady tile grade badge",
            ),
            "Banner Tools menu/grid" to listOf(
                "Banner Tools drawables",
                "Banner Tools menu row",
            ),
            "Custom components (driver/Proton/DXVK)" to listOf(
                "Custom components label resources",
                "Custom components activity",
                "Custom components row",
                "Custom components picker merge",
            ),
            "Mute UI sounds" to emptyList(),
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
    fun run(
        inputApk: File,
        enabledFeatures: Set<String> = FEATURES.keys.toSet(),
    ): File {
        val cache = ctx.cacheDir
        val mute = "Mute UI sounds" in enabledFeatures
        val tmp = File(cache, "tmp").also { it.deleteRecursively(); it.mkdirs() }
        val framework = File(cache, "framework").also { it.mkdirs() }

        // 1. Stage the bundled .rvp in filesDir (stable; cacheDir can be evicted).
        //    CRITICAL: Android 14+ refuses to load dex from an app-WRITABLE file
        //    ("Writable dex file ... is not allowed"), so mark it read-only before
        //    loadPatches — exactly as ReVanced Manager's Source does.
        val bundleFile = File(ctx.filesDir, "patches.rvp")
        // DELETE any prior copy first: a previous run left it read-only, and
        // re-opening a read-only file for writing throws EACCES. Deleting works
        // (the file's read-only attr doesn't block deletion in a writable dir).
        bundleFile.delete()
        ctx.assets.open("patches.rvp").use { input ->
            bundleFile.outputStream().use { input.copyTo(it) }
        }
        val ro = bundleFile.setReadOnly()
        log("Patch bundle staged (read-only=$ro, writable=${bundleFile.canWrite()})")

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

        // 4. Resolve the selected patch names from ALWAYS + the enabled features,
        //    then map to Patch objects (exclusive: only these run). Names missing
        //    from the bundle are skipped with a warning rather than failing.
        val byName = allPatches.filter { it.name != null }.associateBy { it.name!! }
        val names = LinkedHashSet(ALWAYS)
        enabledFeatures.forEach { f -> FEATURES[f]?.let { names.addAll(it) } }
        val selected = names.mapNotNull { name ->
            byName[name] ?: run { log("(skip: \"$name\" not in bundle)"); null }
        }
        byName["Change package name"]?.options?.set("packageName", OPT_PACKAGE_NAME)
        byName["Change app name"]?.options?.set("appName", OPT_APP_NAME)
        log("Selected ${selected.size} patches (${enabledFeatures.size} features)")

        // 5. Build the patcher (decodes manifest/resources). Mirrors Session.run's
        //    patcher(apkFile, temporaryFilesPath, frameworkFileDirectory, aaptBinaryPath).
        val output = File(cache, "GameHubPlus_patched.apk")
        val unsigned = File(cache, "GameHubPlus_unsigned.apk")
        val selectedSet = selected.toSet()
        val failed = mutableListOf<String>()

        logger.withJavaLogging {
            log("Reading APK and decoding resources...")
            val patcherFn = patcher(
                apkFile = inputApk,
                temporaryFilesPath = tmp,
                aaptBinaryPath = aapt,
                frameworkFileDirectory = framework.absolutePath,
            ) { _, _ -> selectedSet }

            // 6. Apply patches. A single patch failing must NOT abort the whole
            //    build (ReVanced continues + reports), so we log + collect failures
            //    and keep going; the failed patch's changes are not applied.
            log("Applying patches...")
            val result = patcherFn { patchResult ->
                val ex = patchResult.exception
                if (ex != null) {
                    log("SKIPPED (failed): ${patchResult.patch.name}: ${ex.message}")
                    failed.add(patchResult.patch.name ?: "?")
                } else {
                    log("OK: ${patchResult.patch.name}")
                }
            }
            if (failed.isNotEmpty()) {
                log("Note: ${failed.size} patch(es) skipped: ${failed.joinToString(", ")}")
            }

            // 7. Write patched dex + resources onto a copy of the source APK.
            //    Mirrors Session.run: copy input -> result.applyTo(patched).
            log("Writing patched APK...")
            inputApk.copyTo(unsigned, overwrite = true)
            result.applyTo(unsigned)
        }

        // 8. Mute UI sounds (post-step; toggleable): swap each sound/*.m4a entry
        //    for the bundled silent clip.
        val toSign: File
        val muted = File(cache, "GameHubPlus_muted.apk")
        if (mute) {
            muteSounds(unsigned, muted)
            toSign = muted
        } else {
            toSign = unsigned
        }

        // 9. Sign with the bundled debug keystore (apksig; aligns too).
        log("Signing...")
        ApkSign(ctx).sign(toSign, output, minSdk = 29)
        log("Done: ${output.name}")

        tmp.deleteRecursively()
        unsigned.delete()
        if (mute) muted.delete()
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
