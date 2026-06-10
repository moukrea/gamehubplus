package com.ghplus.patcher.engine

import android.content.Context
import app.revanced.library.ApkSigner
import app.revanced.library.ApkUtils
import app.revanced.library.ApkUtils.applyTo
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatches
import app.revanced.patcher.patcher
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import java.util.Base64
import java.util.Date

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

        // On-device signing identity. The keystore is generated once with
        // ReVanced's own ApkSigner (BKS/BouncyCastle, the only format its
        // ApkUtils.signApk can read) and persisted, so every re-patch reuses the
        // same cert and GameHub+ updates in place.
        private const val KEY_ALIAS = "GameHubPlus"
        private const val KEY_PASSWORD = "gamehubplus"

        // Post-process feature labels (mods applied to the patched APK, not bundle
        // patches). Shared between the FEATURES map and run() so the toggle and the
        // gate can't drift.
        const val FEAT_RENAME = "Rename Profile→Settings"
        const val FEAT_STRIP_ACCOUNT = "Remove account/login section"
        const val FEAT_HIDE_TABS = "Hide Play/Home/Leaderboard tabs"
        const val FEAT_NO_PRIVACY = "Skip Privacy Policy dialog"
        const val FEAT_NO_RECS = "Skip \"Customize your home\" screen"
        const val FEAT_NO_NOTIF = "Skip notification permission"

        // First-launch onboarding gate flags. We PERSIST these true at app startup
        // (Loxh;->c = putBoolean+apply) so the screens never show AND the consent-
        // gated init (Firebase etc.) still runs. Faking the read instead crashes.
        //   app_agreement_agreed                   = Privacy Policy dialog (ao8.e)
        //   onboarding_custom_recommendation_shown = "Customize your home" (zmn.F)
        val WRITE_TRUE_KEYS = listOf("app_agreement_agreed", "onboarding_custom_recommendation_shown")

        /** Always applied (rebrand so it installs alongside stock + the per-game
         *  id capture that the menu-row features depend on). Not user-toggleable.
         *
         *  "Rewrite custom permissions per variant" namespaces upstream-baked
         *  custom permissions (e.g. com.xiaoji.egggame.permission.C2D_MESSAGE) to
         *  the new package. Without it — together with the updateProviders /
         *  updatePermissions options set on "Change package name" below — the
         *  patched app keeps com.xiaoji.egggame.* provider authorities and
         *  permissions, which collide with a stock GameHub install and the
         *  package installer rejects it as "conflicts with an existing package"
         *  (INSTALL_FAILED_CONFLICTING_PROVIDER / _DUPLICATE_PERMISSION). */
        val ALWAYS: List<String> = listOf(
            "Change package name",
            "Change app name",
            "Per-game menu id capture (shared)",
            "Rewrite custom permissions per variant",
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
                // The gof.a hook (OfflineComponentList) is what actually makes
                // custom components appear in the in-game picker — without it the
                // merge writes to a store the picker never reads.
                "Offline component picker — local list",
            ),
            // Global "GameHub+ mods" hub (GOG + Custom Components). Registers the
            // exported GameHubPlusModsActivity AND wires it into the Settings page:
            // the "entry" patch redirects the Logout confirm (pth crh.a -> nth) to
            // launch the hub; the "label" patch relabels the row + dialog to
            // "GameHub+ mods" via an in-place same-length CVR overwrite.
            "GameHub+ mods hub" to listOf(
                "GameHub+ mods activity",
                "Settings GameHub+ mods entry",
                "Settings GameHub+ mods label",
            ),
            "Mute UI sounds" to emptyList(),
            // Post-process mods (no bundle patch; applied to the patched APK in
            // run() and gated on these keys being enabled). See FEAT_* above.
            FEAT_RENAME to emptyList(),
            FEAT_STRIP_ACCOUNT to emptyList(),
            FEAT_HIDE_TABS to emptyList(),
            FEAT_NO_PRIVACY to emptyList(),
            FEAT_NO_RECS to emptyList(),
            FEAT_NO_NOTIF to emptyList(),
        )

        // Compose sound assets to silence (see CLAUDE.md). Entry names inside the
        // output APK zip whose path matches this prefix + .m4a are replaced with
        // the bundled silent clip.
        private const val SOUND_PREFIX =
            "assets/composeResources/com.xiaoji.egggame.core/files/sound/"

        // Bottom-nav "Profile" label lives in the home module's Compose string
        // tables (.cvr text: "string|<key>|<base64-utf8>"). We relabel it to
        // "Settings" per locale — the original GameHub+ rename that the bannerhub
        // bundle never carried.
        private const val HOME_CVR_DIR =
            "assets/composeResources/com.xiaoji.egggame.features.home/"
        private const val NAV_KEY = "features_home_nav_profile"
        private val NAV_RENAME: Map<String, String> = linkedMapOf(
            "values" to "Settings",
            "values-zh-rCN" to "设置",
            "values-ja-rJP" to "設定",
            "values-pt-rBR" to "Configurações",
            "values-ru-rRU" to "Настройки",
        )
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
        byName["Change package name"]?.options?.let { opts ->
            opts["packageName"] = OPT_PACKAGE_NAME
            // Rewrite provider authorities (7 of them, all com.xiaoji.egggame.*)
            // and the DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to the new package
            // so they don't collide with a stock GameHub install. The app's
            // libraries build these authorities from packageName at runtime, so
            // renaming the manifest to match the (now-renamed) package is correct.
            opts["updateProviders"] = true
            opts["updatePermissions"] = true
        }
        byName["Change app name"]?.options?.set("appName", OPT_APP_NAME)
        log("Selected ${selected.size} patches (${enabledFeatures.size} features)")

        // 5. Build the patcher (decodes manifest/resources). Mirrors Session.run's
        //    patcher(apkFile, temporaryFilesPath, frameworkFileDirectory, aaptBinaryPath).
        val output = File(cache, "GameHubPlus_patched.apk")
        val unsigned = File(cache, "GameHubPlus_unsigned.apk")
        // Apply ALL patches (no per-patch toggles, per user) EXCEPT "Change app
        // icon": the user wants GameHub's OFFICIAL icons kept, not BannerHub's
        // (that patch overwrites the launcher/splash/auth/wine drawables). The
        // patcher's compatibility check self-skips the 6.0.4-only patches.
        val selectedSet = allPatches.filterNot { it.name == "Change app icon" }.toSet()
        val failed = mutableListOf<String>()

        // Mute UI sounds on the SOURCE (toggleable) BEFORE patching, so the
        // patcher re-encodes a clean, aligned output. Doing it AFTER (re-zipping
        // the finished apk) re-compresses resources.arsc and breaks alignment,
        // which Android rejects as "App not installed".
        val patchInput = if (mute) {
            val m = File(cache, "GameHubPlus_muted_src.apk")
            muteSounds(inputApk, m)
            m
        } else {
            inputApk
        }

        logger.withJavaLogging {
            log("Reading APK and decoding resources...")
            val patcherFn = patcher(
                apkFile = patchInput,
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

            // 7. Write patched dex + resources onto a copy of the (muted) input.
            //    Mirrors Session.run: copy input -> result.applyTo(patched).
            log("Writing patched APK...")
            patchInput.copyTo(unsigned, overwrite = true)
            result.applyTo(unsigned)
        }

        // 7b. The original GameHub+ mods the bannerhub bundle never carried,
        //     applied as post-steps on the patched (still unsigned) APK and gated
        //     on their feature toggles. Signing follows and re-aligns.
        // Our original GameHub+ mods, always applied (no toggles). The login
        // strip is the merge-partner of Banner's "Bypass login": bypass makes the
        // app usable under a synthetic identity, strip removes the now-dead
        // account/login/logout UI.
        log("Applying GameHub+ UI mods...")
        renameNavProfile(unsigned)
        stripAccountSection(unsigned)
        hideNavTabs(unsigned)
        forceSkipComposables(unsigned)
        writeAgreementPrefs(unsigned)
        forceNotifDenied(unsigned)
        unrequestNotifPermission(unsigned)

        // 8. Sign via ReVanced's ApkUtils.signApk — the canonical path that
        //    zip-aligns, keeps resources.arsc STORED, and writes v2/v3 sigs, i.e.
        //    an INSTALLABLE apk (raw apksig alone wasn't enough on Android 11+).
        log("Signing...")
        ApkUtils.signApk(unsigned, output, KEY_ALIAS, signingKeyStore())
        log("Done: ${output.name}")

        if (patchInput != inputApk) patchInput.delete()
        tmp.deleteRecursively()
        unsigned.delete()
        return output
    }

    /**
     * Returns the keystore to sign with, generating it on first use.
     *
     * ReVanced's ApkUtils.signApk loads the keystore via
     * KeyStore.getInstance("BKS", "BC") — a BouncyCastle BKS store. A stock
     * Android debug.keystore is PKCS12, which that loader rejects with
     * "Wrong version of key store". So we mint the keystore with the library's
     * OWN ApkSigner (guaranteed BKS) and persist it in filesDir; reusing it on
     * every patch keeps a stable signing cert so GameHub+ updates in place.
     */
    private fun signingKeyStore(): ApkUtils.KeyStoreDetails {
        val ksFile = File(ctx.filesDir, "gamehubplus-signing.bks")
        if (!ksFile.exists()) {
            log("Generating signing keystore (first run)...")
            val notAfter = Date(System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000)
            val pair = ApkSigner.newPrivateKeyCertificatePair(KEY_ALIAS, notAfter)
            val ks = ApkSigner.newKeyStore(
                setOf(ApkSigner.KeyStoreEntry(KEY_ALIAS, KEY_PASSWORD, pair)),
            )
            ksFile.outputStream().use { ks.store(it, KEY_PASSWORD.toCharArray()) }
        }
        return ApkUtils.KeyStoreDetails(ksFile, KEY_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
    }

    /**
     * Copies [input] to [output], replacing every compose sound .m4a entry with
     * the bundled silent clip.
     *
     * CRITICAL: this uses apkzlib's ZFile — the SAME zip writer ReVanced's
     * ApkUtils.applyTo uses — instead of java.util.zip. java.util.zip's
     * ZipOutputStream always emits a data descriptor for DEFLATED entries (it
     * can't seek back to patch the local header), so every entry comes out with
     * general-purpose bit 3 set. applyTo (ZFile) then copies those entries but
     * regenerates a central directory with bit 3 CLEAR, producing an
     * LFH/CD "data descriptor presence mismatch" that apksig rejects at signing
     * ("Malformed ZIP entry ... LFH: true, CD: false"). ZFile writes AGP-clean,
     * descriptor-free entries (like the original APK), so the mismatch never
     * arises. ZFile.close() rewrites the whole archive normalized.
     */
    private fun muteSounds(input: File, output: File) {
        val silent = ctx.assets.open("silent.m4a").use { it.readBytes() }
        input.copyTo(output, overwrite = true)
        var replaced = 0
        ZFile.openReadWrite(output).use { zf ->
            val names = zf.entries()
                .map { it.centralDirectoryHeader.name }
                .filter { it.startsWith(SOUND_PREFIX) && it.endsWith(".m4a") }
            for (name in names) {
                // mayCompress = false -> STORED (m4a is already compressed).
                zf.add(name, silent.inputStream(), false)
                replaced++
            }
        }
        log("Muted $replaced UI sound asset(s)")
    }

    /**
     * Relabels the bottom-nav "Profile" entry to "Settings" (per locale) by
     * editing the home module's Compose `.cvr` string tables in [apk]. Each line
     * is `string|<key>|<base64-utf8>[|...]`; we swap the value for [NAV_KEY].
     * Ports the original `patch_rename.py` into the on-device flow.
     */
    private fun renameNavProfile(apk: File) {
        var n = 0
        ZFile.openReadWrite(apk).use { zf ->
            for ((loc, word) in NAV_RENAME) {
                val name = "$HOME_CVR_DIR$loc/strings.commonMain.cvr"
                val entry = zf.get(name) ?: continue
                val lines = String(entry.read(), Charsets.UTF_8).split("\n")
                val b64 = Base64.getEncoder().encodeToString(word.toByteArray(Charsets.UTF_8))
                var found = false
                val out = lines.map { line ->
                    val p = line.split("|")
                    if (!found && p.size >= 3 && p[0] == "string" && p[1] == NAV_KEY) {
                        found = true
                        (listOf("string", NAV_KEY, b64) + p.drop(3)).joinToString("|")
                    } else {
                        line
                    }
                }
                if (found) {
                    zf.add(name, out.joinToString("\n").toByteArray(Charsets.UTF_8).inputStream(), true)
                    n++
                }
            }
        }
        log("Renamed Profile->Settings in $n nav locale(s)")
    }

    /**
     * Removes the account / login-register / logout block from the profile page
     * by zeroing the gating flag `Lash;->b` in its <init>: inserts a
     * `const/4 vX, 0` right before the `iput-boolean vX, vY, Lash;->b:Z` store.
     * Ports the original `patch_login.py` (a smali edit) as a dexlib2 transform
     * on the patched dex. Scans each classes*.dex for `Lash;` and rewrites only
     * the one that holds it.
     */
    private fun stripAccountSection(apk: File) {
        val dexNames = ZFile.openReadOnly(apk).use { zf ->
            zf.entries().map { it.centralDirectoryHeader.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
        }
        for (dexName in dexNames) {
            val dexBytes = ZFile.openReadOnly(apk).use { it.get(dexName)!!.read() }
            val tmpIn = File.createTempFile("dexin", ".dex", ctx.cacheDir).apply { writeBytes(dexBytes) }
            val dex = DexFileFactory.loadDexFile(tmpIn, Opcodes.getDefault())
            if (dex.classes.none { it.type == "Lash;" }) {
                tmpIn.delete()
                continue
            }

            var patched = false
            val newClasses: List<ClassDef> = dex.classes.map { cd ->
                if (cd.type != "Lash;") return@map cd
                val newDirect = cd.directMethods.map dm@{ m ->
                    val impl = m.implementation ?: return@dm m
                    var targetIdx = -1
                    var reg = -1
                    impl.instructions.forEachIndexed { i, ins ->
                        if (targetIdx < 0 && ins.opcode == Opcode.IPUT_BOOLEAN && ins is ReferenceInstruction) {
                            val r = ins.reference
                            if (r is FieldReference && r.definingClass == "Lash;" && r.name == "b" && r.type == "Z") {
                                targetIdx = i
                                reg = (ins as TwoRegisterInstruction).registerA
                            }
                        }
                    }
                    if (targetIdx < 0) return@dm m
                    val mut = MutableMethodImplementation(impl)
                    val zero: BuilderInstruction = if (reg <= 15) {
                        BuilderInstruction11n(Opcode.CONST_4, reg, 0)
                    } else {
                        BuilderInstruction21s(Opcode.CONST_16, reg, 0)
                    }
                    mut.addInstruction(targetIdx, zero)
                    patched = true
                    ImmutableMethod(
                        m.definingClass, m.name, m.parameters, m.returnType,
                        m.accessFlags, m.annotations, m.hiddenApiRestrictions, mut,
                    )
                }
                ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile,
                    cd.annotations, cd.staticFields, cd.instanceFields, newDirect, cd.virtualMethods,
                )
            }
            tmpIn.delete()
            if (!patched) {
                log("strip: Lash;->b store not found")
                return
            }
            val tmpOut = File.createTempFile("dexout", ".dex", ctx.cacheDir).apply { delete() }
            DexFileFactory.writeDexFile(tmpOut.absolutePath, ImmutableDexFile(dex.opcodes, newClasses))
            val outBytes = tmpOut.readBytes()
            tmpOut.delete()
            ZFile.openReadWrite(apk).use { it.add(dexName, outBytes.inputStream(), true) }
            log("Stripped account/login section ($dexName, Lash;->b forced false)")
            return
        }
        log("strip: Lash; not found in any dex")
    }

    /**
     * Hides the Play / Home / Leaderboard bottom-nav tabs (leaving Library +
     * Settings), by shrinking the two `[Lwy8;` tab arrays the nav builder
     * `Ldi9;-><init>` fills. The builder constructs a portrait list (1st
     * `filled-new-array`) and a landscape list (2nd); each tab is one register.
     * Ports `tabs.py` — verified on 6.0.8 against the route literals:
     *   portrait  regs {v3=HOME, v5=PLAY, v10=LEADERBOARD, v1=LIBRARY, v6=PROFILE}
     *   landscape regs {v1=LIBRARY, v3=PLAY, v5=HOME, v6=LEADERBOARD, v4=PROFILE}
     * We rebuild each filled-new-array with the play/home/leaderboard registers
     * dropped. A register-set guard aborts (leaving the nav intact) if a future
     * GameHub changes the array shape, so it can't silently corrupt the nav.
     */
    private fun hideNavTabs(apk: File) {
        // Per-array (portrait, landscape): the full expected register set, and
        // the registers to drop (play, home, leaderboard).
        val expect = listOf(setOf(1, 3, 5, 6, 10), setOf(1, 3, 4, 5, 6))
        val remove = listOf(setOf(5, 3, 10), setOf(3, 5, 6))

        val dexNames = ZFile.openReadOnly(apk).use { zf ->
            zf.entries().map { it.centralDirectoryHeader.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
        }
        for (dexName in dexNames) {
            val dexBytes = ZFile.openReadOnly(apk).use { it.get(dexName)!!.read() }
            val tmpIn = File.createTempFile("dexin", ".dex", ctx.cacheDir).apply { writeBytes(dexBytes) }
            val dex = DexFileFactory.loadDexFile(tmpIn, Opcodes.getDefault())
            if (dex.classes.none { it.type == "Ldi9;" }) {
                tmpIn.delete()
                continue
            }

            var changed = false
            val newClasses: List<ClassDef> = dex.classes.map { cd ->
                if (cd.type != "Ldi9;") return@map cd
                val newDirect = cd.directMethods.map dm@{ m ->
                    val impl = m.implementation ?: return@dm m
                    val insns = impl.instructions.toList()
                    val arrIdx = insns.indices.filter { i ->
                        val ins = insns[i]
                        ins.opcode == Opcode.FILLED_NEW_ARRAY && ins is ReferenceInstruction &&
                            (ins.reference as? TypeReference)?.type == "[Lwy8;"
                    }
                    if (arrIdx.size != 2) return@dm m
                    val mut = MutableMethodImplementation(impl)
                    for (n in 0..1) {
                        val ins = insns[arrIdx[n]] as FiveRegisterInstruction
                        val regs = listOf(
                            ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG,
                        ).take(ins.registerCount)
                        if (regs.toSet() != expect[n]) {
                            log("hide-tabs: di9 array #$n shape changed; leaving nav intact")
                            return@dm m
                        }
                        val keep = regs.filter { it !in remove[n] }
                        val ref = (insns[arrIdx[n]] as ReferenceInstruction).reference
                        mut.replaceInstruction(
                            arrIdx[n],
                            BuilderInstruction35c(
                                Opcode.FILLED_NEW_ARRAY, keep.size,
                                keep.getOrElse(0) { 0 }, keep.getOrElse(1) { 0 }, keep.getOrElse(2) { 0 },
                                keep.getOrElse(3) { 0 }, keep.getOrElse(4) { 0 }, ref,
                            ),
                        )
                    }
                    changed = true
                    ImmutableMethod(
                        m.definingClass, m.name, m.parameters, m.returnType,
                        m.accessFlags, m.annotations, m.hiddenApiRestrictions, mut,
                    )
                }
                if (!changed) cd else ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile,
                    cd.annotations, cd.staticFields, cd.instanceFields, newDirect, cd.virtualMethods,
                )
            }
            tmpIn.delete()
            if (!changed) {
                log("hide-tabs: di9 nav arrays not found")
                return
            }
            val tmpOut = File.createTempFile("dexout", ".dex", ctx.cacheDir).apply { delete() }
            DexFileFactory.writeDexFile(tmpOut.absolutePath, ImmutableDexFile(dex.opcodes, newClasses))
            val outBytes = tmpOut.readBytes()
            tmpOut.delete()
            ZFile.openReadWrite(apk).use { it.add(dexName, outBytes.inputStream(), true) }
            log("Hid Play/Home/Leaderboard nav tabs ($dexName)")
            return
        }
        log("hide-tabs: Ldi9; not found in any dex")
    }

    /**
     * Renders nothing for specific Compose composables by forcing their
     * startRestartGroup skip. A composable body is
     * `if (composer.Y(...)) { render } else { composer.b0() }`; inserting
     * `const/4 vReg, 0` before the IF_EQZ that tests Y's result makes it always
     * take the else branch (skipToGroupEnd) -> empty group -> nothing drawn.
     *
     * Removes the leftover profile banner (gn8.f portrait header + bn8.i landscape
     * avatar card), the gear sub-menu "Login/Register" row (q2a.r), and the
     * "Join community" promo banner (mdj.q + mdj.p, two layouts). Each target was
     * located dex-level via its label field reader, then proven on a real device
     * through the desktop DexEdit port. Skips ONLY the avatar/banner pieces — the
     * Settings/Downloads buttons (ek8.k) stay (landscape's only settings entry).
     */
    private fun forceSkipComposables(apk: File) {
        // (classType, methodName) composables to render nothing.
        val targets = listOf(
            "Lgn8;" to "f", // portrait profile banner
            "Lq2a;" to "r", // gear sub-menu "Login/Register" row
            "Lbn8;" to "i", // landscape profile avatar card
            "Lmdj;" to "q", // "Join community" banner (layout 1)
            "Lmdj;" to "p", // "Join community" banner (layout 2)
            "Lyrh;" to "invoke", // Settings account/login section — PORTRAIT (incl Login/Register)
            "Lji5;" to "b", // Settings account/login section — LANDSCAPE twin
        )
        val byClass: Map<String, List<String>> = targets.groupBy({ it.first }, { it.second })
        val dexNames = ZFile.openReadOnly(apk).use { zf ->
            zf.entries().map { it.centralDirectoryHeader.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
        }
        for (dexName in dexNames) {
            val dexBytes = ZFile.openReadOnly(apk).use { it.get(dexName)!!.read() }
            val tmpIn = File.createTempFile("dexin", ".dex", ctx.cacheDir).apply { writeBytes(dexBytes) }
            val dex = DexFileFactory.loadDexFile(tmpIn, Opcodes.getDefault())
            if (dex.classes.none { it.type in byClass.keys }) {
                tmpIn.delete()
                continue
            }
            var changed = false
            val newClasses: List<ClassDef> = dex.classes.map { cd ->
                val wanted = byClass[cd.type] ?: return@map cd
                // Scan BOTH direct and virtual methods: static composables (gn8.f) are
                // direct, but lambda/interface invokes (yrh.invoke = account section)
                // are virtual — the original direct-only scan silently missed them.
                val skip = fun(m: Method): Method {
                    if (m.name !in wanted) return m
                    val impl = m.implementation ?: return m
                    val insns = impl.instructions.toList()
                    val yIdx = insns.indexOfFirst { ins ->
                        ins.opcode == Opcode.INVOKE_VIRTUAL && ins is ReferenceInstruction &&
                            (ins.reference as? MethodReference)
                                ?.let { it.definingClass == "Lfo8;" && it.name == "Y" } == true
                    }
                    if (yIdx < 0) {
                        log("forceSkip: no Lfo8;->Y in ${cd.type}->${m.name}")
                        return m
                    }
                    val ifIdx = (yIdx + 1 until insns.size)
                        .firstOrNull { insns[it].opcode == Opcode.IF_EQZ } ?: return m
                    val reg = (insns[ifIdx] as OneRegisterInstruction).registerA
                    val mut = MutableMethodImplementation(impl)
                    val zero: BuilderInstruction = if (reg <= 15) {
                        BuilderInstruction11n(Opcode.CONST_4, reg, 0)
                    } else {
                        BuilderInstruction21s(Opcode.CONST_16, reg, 0)
                    }
                    mut.addInstruction(ifIdx, zero)
                    changed = true
                    log("forceSkip ${cd.type}->${m.name}")
                    return ImmutableMethod(
                        m.definingClass, m.name, m.parameters, m.returnType,
                        m.accessFlags, m.annotations, m.hiddenApiRestrictions, mut,
                    )
                }
                ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile,
                    cd.annotations, cd.staticFields, cd.instanceFields,
                    cd.directMethods.map(skip), cd.virtualMethods.map(skip),
                )
            }
            tmpIn.delete()
            if (!changed) continue
            val tmpOut = File.createTempFile("dexout", ".dex", ctx.cacheDir).apply { delete() }
            DexFileFactory.writeDexFile(tmpOut.absolutePath, ImmutableDexFile(dex.opcodes, newClasses))
            val outBytes = tmpOut.readBytes()
            tmpOut.delete()
            ZFile.openReadWrite(apk).use { it.add(dexName, outBytes.inputStream(), true) }
        }
        log("Removed profile banner + gear Login/Register + Join banner")
    }

    /**
     * Persists the first-launch onboarding gate flags ([WRITE_TRUE_KEYS]) to true
     * at the start of `AndroidApp.onCreate`, reusing the `Loxh` prefs object the
     * method already loads to read app_agreement_agreed. Inserting the writes right
     * before that existing read makes the app run its real "already cleared" path:
     * the Privacy Policy dialog and the "Customize your home" recs screen never
     * show, and the consent-gated init (FirebaseApp.initializeApp) still runs — so
     * the agreed path does not crash (faking the read instead skips that init).
     */
    private fun writeAgreementPrefs(apk: File) {
        val dexNames = ZFile.openReadOnly(apk).use { zf ->
            zf.entries().map { it.centralDirectoryHeader.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
        }
        for (dexName in dexNames) {
            val dexBytes = ZFile.openReadOnly(apk).use { it.get(dexName)!!.read() }
            val tmpIn = File.createTempFile("dexin", ".dex", ctx.cacheDir).apply { writeBytes(dexBytes) }
            val dex = DexFileFactory.loadDexFile(tmpIn, Opcodes.getDefault())
            if (dex.classes.none { it.type == "Lcom/xiaoji/egggame/AndroidApp;" }) { tmpIn.delete(); continue }
            var patched = false
            val newClasses: List<ClassDef> = dex.classes.map { cd ->
                if (cd.type != "Lcom/xiaoji/egggame/AndroidApp;") return@map cd
                val newVirtual = cd.virtualMethods.map vm@{ m ->
                    val impl = m.implementation ?: return@vm m
                    if (m.name != "onCreate") return@vm m
                    val insns = impl.instructions.toList()
                    val anchor = insns.indexOfFirst { ins ->
                        (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                            ins is ReferenceInstruction &&
                            (ins.reference as? StringReference)?.string == "app_agreement_agreed"
                    }
                    if (anchor < 0) return@vm m
                    val vKey = (insns[anchor] as OneRegisterInstruction).registerA
                    var vOxh = -1
                    for (i in anchor - 1 downTo maxOf(0, anchor - 3)) {
                        if (insns[i].opcode == Opcode.MOVE_RESULT_OBJECT) {
                            vOxh = (insns[i] as OneRegisterInstruction).registerA; break
                        }
                    }
                    var vBool = -1
                    for (i in anchor + 1 until minOf(insns.size, anchor + 7)) {
                        if (insns[i].opcode == Opcode.MOVE_RESULT) {
                            vBool = (insns[i] as OneRegisterInstruction).registerA; break
                        }
                    }
                    if (vOxh < 0 || vBool < 0 || vOxh > 15 || vKey > 15 || vBool > 15) {
                        log("writeAgreementPrefs: unsafe registers (oxh=$vOxh key=$vKey bool=$vBool)")
                        return@vm m
                    }
                    val mut = MutableMethodImplementation(impl)
                    val seq = mutableListOf<BuilderInstruction>()
                    for (key in WRITE_TRUE_KEYS) {
                        seq.add(BuilderInstruction21c(Opcode.CONST_STRING, vKey, ImmutableStringReference(key)))
                        seq.add(BuilderInstruction11n(Opcode.CONST_4, vBool, 1))
                        seq.add(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, vOxh, vKey, vBool, 0, 0,
                            ImmutableMethodReference("Loxh;", "c", listOf("Ljava/lang/String;", "Z"), "V")))
                    }
                    for (k in seq.indices.reversed()) mut.addInstruction(anchor, seq[k])
                    patched = true
                    ImmutableMethod(
                        m.definingClass, m.name, m.parameters, m.returnType,
                        m.accessFlags, m.annotations, m.hiddenApiRestrictions, mut,
                    )
                }
                ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile,
                    cd.annotations, cd.staticFields, cd.instanceFields, cd.directMethods, newVirtual,
                )
            }
            tmpIn.delete()
            if (!patched) continue
            val tmpOut = File.createTempFile("dexout", ".dex", ctx.cacheDir).apply { delete() }
            DexFileFactory.writeDexFile(tmpOut.absolutePath, ImmutableDexFile(dex.opcodes, newClasses))
            val outBytes = tmpOut.readBytes(); tmpOut.delete()
            ZFile.openReadWrite(apk).use { it.add(dexName, outBytes.inputStream(), true) }
            log("Persisted onboarding gate flags true (AndroidApp.onCreate, $dexName)")
            return
        }
        log("writeAgreementPrefs: AndroidApp not found")
    }

    /**
     * Forces `Lrj8;->M(Context)Lpwf;` (the notification-permission decision) to
     * return `Lpwf;->c` ("Denied") immediately, so the app never asks for the
     * POST_NOTIFICATIONS runtime permission and treats notifications as off —
     * replaying the user-declined path the app already handles.
     */
    private fun forceNotifDenied(apk: File) {
        val dexNames = ZFile.openReadOnly(apk).use { zf ->
            zf.entries().map { it.centralDirectoryHeader.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
        }
        for (dexName in dexNames) {
            val dexBytes = ZFile.openReadOnly(apk).use { it.get(dexName)!!.read() }
            val tmpIn = File.createTempFile("dexin", ".dex", ctx.cacheDir).apply { writeBytes(dexBytes) }
            val dex = DexFileFactory.loadDexFile(tmpIn, Opcodes.getDefault())
            if (dex.classes.none { it.type == "Lrj8;" }) { tmpIn.delete(); continue }
            var patched = false
            val newClasses: List<ClassDef> = dex.classes.map { cd ->
                if (cd.type != "Lrj8;") return@map cd
                val newDirect = cd.directMethods.map dm@{ m ->
                    val impl = m.implementation ?: return@dm m
                    if (m.name != "M" || m.returnType != "Lpwf;") return@dm m
                    val mut = MutableMethodImplementation(impl)
                    mut.addInstruction(0, BuilderInstruction21c(
                        Opcode.SGET_OBJECT, 0, ImmutableFieldReference("Lpwf;", "c", "Lpwf;")))
                    mut.addInstruction(1, BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
                    patched = true
                    ImmutableMethod(
                        m.definingClass, m.name, m.parameters, m.returnType,
                        m.accessFlags, m.annotations, m.hiddenApiRestrictions, mut,
                    )
                }
                ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile,
                    cd.annotations, cd.staticFields, cd.instanceFields, newDirect, cd.virtualMethods,
                )
            }
            tmpIn.delete()
            if (!patched) continue
            val tmpOut = File.createTempFile("dexout", ".dex", ctx.cacheDir).apply { delete() }
            DexFileFactory.writeDexFile(tmpOut.absolutePath, ImmutableDexFile(dex.opcodes, newClasses))
            val outBytes = tmpOut.readBytes(); tmpOut.delete()
            ZFile.openReadWrite(apk).use { it.add(dexName, outBytes.inputStream(), true) }
            log("Notification permission: rj8.M -> Denied ($dexName)")
            return
        }
        log("forceNotifDenied: rj8 not found")
    }

    /**
     * Un-declares POST_NOTIFICATIONS in the (binary AXML) manifest by renaming the
     * permission string in place (same byte length, UTF-16LE) to a bogus name.
     * An undeclared runtime permission is auto-denied by Android with NO system
     * dialog, so the notification prompt never shows and notifications can't be
     * posted — path-independent (covers requests from any GameHub or bannerhub /
     * push-SDK code path, unlike the rj8.M decision hook alone).
     */
    private fun unrequestNotifPermission(apk: File) {
        val old = "android.permission.POST_NOTIFICATIONS".toByteArray(Charsets.UTF_16LE)
        val bogus = "android.permission.POST_NOTIFICATIONX".toByteArray(Charsets.UTF_16LE)
        ZFile.openReadWrite(apk).use { zf ->
            val entry = zf.get("AndroidManifest.xml") ?: run { log("notif: manifest not found"); return }
            val data = entry.read()
            var i = indexOf(data, old, 0)
            if (i < 0) { log("notif: POST_NOTIFICATIONS not declared (nothing to do)"); return }
            var n = 0
            while (i >= 0) {
                System.arraycopy(bogus, 0, data, i, bogus.size) // same length -> in-place
                n++
                i = indexOf(data, old, i + old.size)
            }
            zf.add("AndroidManifest.xml", data.inputStream(), true)
            log("notif: un-declared POST_NOTIFICATIONS in manifest ($n)")
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
