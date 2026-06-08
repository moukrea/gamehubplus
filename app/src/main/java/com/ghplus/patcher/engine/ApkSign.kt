package com.ghplus.patcher.engine

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs (and zip-aligns) an APK with Google's apksig, using the bundled debug
 * keystore (assets/debug.keystore, alias `androiddebugkey`, password `android`).
 *
 * apksig aligns the zip itself (setAlignFileSize), so no separate zipalign step
 * is required — this replaces the uber-apk-signer + zipalign pair used by the
 * desktop CLI.
 */
class ApkSign(private val ctx: Context) {

    private val storePass = "android".toCharArray()
    private val keyAlias = "androiddebugkey"

    private fun loadSigner(): ApkSigner.SignerConfig {
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("debug.keystore").use { ks.load(it, storePass) }
        val key = ks.getKey(keyAlias, storePass) as PrivateKey
        val chain = ks.getCertificateChain(keyAlias)
            .map { it as X509Certificate }
        return ApkSigner.SignerConfig.Builder(keyAlias, key, chain).build()
    }

    /**
     * Sign [input] -> [output] (must be different files). minSdk must match the
     * patched app's manifest minSdk (GameHub targets API 29+).
     */
    fun sign(input: File, output: File, minSdk: Int = 29) {
        val signer = ApkSigner.Builder(listOf(loadSigner()))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setAlignFileSize(true)
            .build()
        signer.sign()
    }
}
