package com.ghplus.patcher.engine

import android.content.Context
import java.io.File

/**
 * Resolves the bundled aapt2 native binary.
 *
 * Ported from ReVanced Manager v2.6.0
 * app/src/main/java/app/revanced/manager/patcher/aapt/Aapt.kt
 * (and its LibraryResolver). The binary ships as
 * jniLibs/<abi>/libaapt2.so; with `useLegacyPackaging = true` the installer
 * extracts it to applicationInfo.nativeLibraryDir and marks it executable.
 */
object Aapt {
    fun binary(context: Context): File? {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        return dir.list { _, name -> !File(name).isDirectory && name.contains("aapt") }
            ?.firstOrNull()
            ?.let { dir.resolve(it) }
    }
}
