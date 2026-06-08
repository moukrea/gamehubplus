package com.ghplus.patcher.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ghplus.patcher.BuildConfig
import com.ghplus.patcher.engine.PatchRunner
import com.ghplus.patcher.update.Release
import com.ghplus.patcher.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePrefs.load(ctx)) }
            AppTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PatcherScreen(
                        themeMode = themeMode,
                        onCycleTheme = {
                            val next = themeMode.next()
                            themeMode = next
                            ThemePrefs.save(ctx, next)
                        },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PatcherScreen(themeMode: ThemeMode, onCycleTheme: () -> Unit) {
        val ctx = LocalContext.current
        var apkUri by remember { mutableStateOf<Uri?>(null) }
        var apkLabel by remember { mutableStateOf("No APK selected") }
        var busy by remember { mutableStateOf(false) }
        val logLines = remember { mutableStateListOf<String>() }

        // --- self-update state ---
        var update by remember { mutableStateOf<Release?>(null) }
        var checking by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }

        // Re-runnable update check. Toasts "Up to date" only when explicitly invoked.
        fun checkForUpdates(announce: Boolean) {
            if (checking) return
            checking = true
            lifecycleScope.launch {
                val latest = UpdateChecker.fetchLatest()
                checking = false
                if (latest != null && UpdateChecker.isNewer(latest)) {
                    update = latest
                } else if (announce) {
                    Toast.makeText(ctx, "Up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Kick off a silent check on first composition.
        LaunchedEffect(Unit) { checkForUpdates(announce = false) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                apkUri = uri
                apkLabel = uri.lastPathSegment ?: uri.toString()
                logLines.add("Selected: $apkLabel")
            }
        }

        update?.let { rel ->
            UpdateDialog(
                release = rel,
                downloading = downloading,
                onUpdate = {
                    downloading = true
                    lifecycleScope.launch {
                        try {
                            val apk = UpdateChecker.download(ctx, rel)
                            UpdateChecker.installApk(ctx, apk)
                            update = null
                        } catch (e: Throwable) {
                            Toast.makeText(
                                ctx,
                                "Update failed: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            downloading = false
                        }
                    }
                },
                onDismiss = { if (!downloading) update = null },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GameHub+ Patcher") },
                    actions = {
                        IconButton(
                            enabled = !checking,
                            onClick = { checkForUpdates(announce = true) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Check for updates",
                            )
                        }
                        val (icon, desc) = when (themeMode) {
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto to "Theme: System"
                            ThemeMode.LIGHT -> Icons.Default.LightMode to "Theme: Light"
                            ThemeMode.DARK -> Icons.Default.DarkMode to "Theme: Dark"
                        }
                        IconButton(onClick = onCycleTheme) {
                            Icon(imageVector = icon, contentDescription = desc)
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Applies the bundled GameHub+ ReVanced patch set on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Divider()

                Button(onClick = { picker.launch("application/vnd.android.package-archive") }) {
                    Text("Select GameHub APK")
                }
                Text(apkLabel, style = MaterialTheme.typography.bodySmall)
                Divider()

                Button(
                    enabled = apkUri != null && !busy,
                    onClick = {
                        busy = true
                        logLines.add("--- patching ---")
                        lifecycleScope.launch {
                            try {
                                val out = withContext(Dispatchers.IO) {
                                    runEngine(apkUri!!) { logLines.add(it) }
                                }
                                logLines.add("Output: ${out.name}")
                                installApk(out)
                            } catch (e: Throwable) {
                                logLines.add("ERROR: ${e.message}")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(if (busy) "Working..." else "Patch & Install")
                }

                Spacer(Modifier.height(8.dp))
                Text("Log", style = MaterialTheme.typography.titleSmall)
                Divider()
                for (line in logLines) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun UpdateDialog(
        release: Release,
        downloading: Boolean,
        onUpdate: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val notes = release.notes.trim().let {
            if (it.length > 500) it.take(500) + "…" else it
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text("Update available — v${BuildConfig.VERSION_CODE} → v${release.versionCode}")
            },
            text = {
                Column {
                    Text(
                        "Version ${release.versionName} (build ${release.versionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(enabled = !downloading, onClick = onUpdate) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Update")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !downloading, onClick = onDismiss) { Text("Later") }
            },
        )
    }

    /** Copy the chosen APK from the content Uri to cache, then run the patcher. */
    private fun runEngine(apkUri: Uri, log: (String) -> Unit): File {
        val src = File(cacheDir, "source.apk")
        contentResolver.openInputStream(apkUri).use { input ->
            requireNotNull(input) { "cannot open chosen APK" }
            src.outputStream().use { input.copyTo(it) }
        }
        return PatchRunner(this, log).run(src)
    }

    /** Launch the system package installer for the patched APK (shared with self-update). */
    private fun installApk(apk: File) = UpdateChecker.installApk(this, apk)
}
