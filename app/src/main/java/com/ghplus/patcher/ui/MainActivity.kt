package com.ghplus.patcher.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
        // phase: 0 = prompt, 1 = downloading, 2 = needs "install unknown apps" permission
        var phase by remember { mutableStateOf(0) }
        var progress by remember { mutableStateOf(-1f) }
        var failed by remember { mutableStateOf(false) }

        // Re-runnable update check. Toasts "Up to date" only when explicitly invoked.
        fun checkForUpdates(announce: Boolean) {
            if (checking) return
            checking = true
            lifecycleScope.launch {
                val latest = UpdateChecker.fetchLatest()
                checking = false
                // An explicit check (announce) ignores a prior "Skip"; the silent
                // launch check respects it so a skipped version doesn't nag.
                val skipped = !announce && latest != null &&
                    UpdateChecker.skippedVersionCode(ctx) == latest.versionCode
                if (latest != null && UpdateChecker.isNewer(latest) && !skipped) {
                    phase = 0
                    progress = -1f
                    failed = false
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

        fun startUpdate(rel: Release) {
            // First run usually lacks the per-app "install unknown apps" grant;
            // send the user to the settings screen, then they tap Retry.
            if (!UpdateChecker.canInstall(ctx)) {
                phase = 2
                UpdateChecker.openInstallSettings(ctx)
                return
            }
            phase = 1
            failed = false
            progress = -1f
            lifecycleScope.launch {
                try {
                    val apk = UpdateChecker.download(ctx, rel) { progress = it }
                    UpdateChecker.installApk(ctx, apk)
                    update = null // the system installer takes over
                } catch (_: Throwable) {
                    failed = true
                    phase = 0
                }
            }
        }

        update?.let { rel ->
            AlertDialog(
                onDismissRequest = { if (phase != 1) update = null },
                icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                title = {
                    Text(
                        when (phase) {
                            1 -> "Updating"
                            2 -> "Allow installs"
                            else -> "Update available"
                        },
                    )
                },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        when (phase) {
                            1 -> {
                                Text(
                                    if (progress >= 0f) "Downloading v${rel.versionName} — ${(progress * 100).toInt()}%"
                                    else "Downloading v${rel.versionName}…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                if (progress >= 0f) {
                                    LinearProgressIndicator(
                                        progress = progress,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            2 -> Text(
                                "To install updates, allow “install unknown apps” for GameHub+ " +
                                    "Patcher in the settings screen that just opened, then come back " +
                                    "and tap Retry.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            else -> {
                                Text(
                                    "Version ${rel.versionName} is available " +
                                        "(you have ${BuildConfig.VERSION_NAME}).",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (failed) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Download failed — check your connection and try again.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (rel.notes.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        rel.notes.trim().let { if (it.length > 600) it.take(600) + "…" else it },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (phase != 1) {
                        Button(onClick = { startUpdate(rel) }) {
                            Text(if (phase == 2) "Retry" else "Update")
                        }
                    }
                },
                dismissButton = {
                    when (phase) {
                        0 -> Row {
                            TextButton(onClick = {
                                UpdateChecker.setSkippedVersionCode(ctx, rel.versionCode)
                                update = null
                            }) { Text("Skip") }
                            TextButton(onClick = { update = null }) { Text("Later") }
                        }
                        2 -> TextButton(onClick = { update = null }) { Text("Later") }
                    }
                },
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

                Text("Mods applied", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The full GameHub+ mod set is applied as one bundle — no " +
                        "per-patch toggles (they were error-prone to split).",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (feature in PatchRunner.FEATURES.keys) {
                    Text(
                        "• $feature",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Divider()

                Button(
                    enabled = apkUri != null && !busy,
                    onClick = {
                        val enabled = PatchRunner.FEATURES.keys.toSet()
                        busy = true
                        logLines.add("--- patching ---")
                        lifecycleScope.launch {
                            try {
                                val out = withContext(Dispatchers.IO) {
                                    runEngine(apkUri!!, enabled) { logLines.add(it) }
                                }
                                logLines.add("Output: ${out.name}")
                                installApk(out)
                            } catch (e: Throwable) {
                                // Full detail so it can be copied + reported.
                                logLines.add("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                                e.cause?.let { logLines.add("  caused by: ${it.javaClass.simpleName}: ${it.message}") }
                                e.stackTrace.take(8).forEach { logLines.add("  at $it") }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(if (busy) "Working..." else "Patch & Install")
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    val clipboard = LocalClipboardManager.current
                    TextButton(
                        enabled = logLines.isNotEmpty(),
                        onClick = {
                            clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                            Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Copy log") }
                }
                Divider()
                // Selectable so individual lines / the whole error can be copied.
                SelectionContainer {
                    Column {
                        for (line in logLines) {
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    /** Copy the chosen APK from the content Uri to cache, then run the patcher. */
    private fun runEngine(
        apkUri: Uri,
        enabledFeatures: Set<String>,
        log: (String) -> Unit,
    ): File {
        val src = File(cacheDir, "source.apk")
        contentResolver.openInputStream(apkUri).use { input ->
            requireNotNull(input) { "cannot open chosen APK" }
            src.outputStream().use { input.copyTo(it) }
        }
        return PatchRunner(this, log).run(src, enabledFeatures)
    }

    /** Launch the system package installer for the patched APK (shared with self-update). */
    private fun installApk(apk: File) = UpdateChecker.installApk(this, apk)
}
