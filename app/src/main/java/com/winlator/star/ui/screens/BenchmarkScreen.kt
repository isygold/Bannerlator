@file:OptIn(ExperimentalMaterial3Api::class)

package com.winlator.star.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.profiler.ProfilerRecommendations
import com.winlator.star.profiler.ProfilerResultsDialog
import com.winlator.star.profiler.ProfilerSession
import com.winlator.star.widget.FpsCounter
import com.winlator.star.widget.HudMetrics

@Composable
fun BenchmarkScreen(vm: BenchmarkViewModel = viewModel()) {
    val context = LocalContext.current
    val containers by vm.containers.collectAsState()
    val selectedContainerId by vm.selectedContainerId.collectAsState()
    val exePath by vm.exePath.collectAsState()
    val loggingEnabled by vm.loggingEnabled.collectAsState()

    // Cached profiler results
    var showResults by remember { mutableStateOf(false) }
    var profilerSession by remember { mutableStateOf<ProfilerSession?>(null) }
    var profilerRecs by remember { mutableStateOf<List<ProfilerRecommendations.Recommendation>>(emptyList()) }

    // Check for cached / fresh results when screen loads or resumes
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("profiler_results", android.content.Context.MODE_PRIVATE)
        val ts = prefs.getLong("last_timestamp", 0L)
        val raw = prefs.getString("last_summary", null)
        val freshResults = prefs.getBoolean("fresh_results", false)
        if (ts > 0L && raw != null) {
            val summary = ProfilerSession.Summary.fromJson(raw)
            if (summary != null) {
                val session = ProfilerSession(FpsCounter(), HudMetrics(context))
                session.summary = summary
                profilerSession = session
                profilerRecs = ProfilerRecommendations.analyze(summary)
                showResults = true
            }
        }
        if (freshResults) {
            prefs.edit().putBoolean("fresh_results", false).apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Benchmark Tool",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "Run a 30-second profiling session to measure FPS, CPU, and get config recommendations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Container picker
        Text("Container", style = MaterialTheme.typography.labelLarge)
        if (containers.isEmpty()) {
            Text(
                text = "No containers found. Create a container first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            val selectedName = containers.find { it.id == selectedContainerId }?.name ?: "Select container"
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Container") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    containers.forEach { container ->
                        DropdownMenuItem(
                            text = { Text(container.name) },
                            onClick = {
                                vm.selectContainer(container.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        // EXE path input
        Text("Executable Path (optional)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = exePath,
            onValueChange = { vm.setExePath(it) },
            label = { Text("Path to .exe or game") },
            placeholder = { Text("e.g. /home/user/Games/game.exe") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            text = "Leave empty to launch the container's default shortcut.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Logging toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Session Logging", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Save structured JSON log for each profiling run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = loggingEnabled,
                onCheckedChange = { vm.setLoggingEnabled(it) },
            )
        }

        HorizontalDivider()

        // Start button
        Button(
            onClick = {
                val intent = Intent(context, XServerDisplayActivity::class.java).apply {
                    putExtra("container_id", selectedContainerId)
                    putExtra("profile_mode", true)
                    putExtra("logging_enabled", loggingEnabled)
                    if (exePath.isNotBlank()) {
                        putExtra("benchmark_exe", exePath.trim())
                    }
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedContainerId >= 0 && containers.isNotEmpty(),
        ) {
            Text("Start Profiling (30s)")
        }

        // Last results summary (compact, always visible when available)
        profilerSession?.summary?.let { summary ->
            HorizontalDivider()
            Text("Last Results", style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    ResultRow("Average FPS", String.format("%.1f", summary.avgFps))
                    ResultRow("1% Lows", String.format("%.1f", summary.low1Fps))
                    ResultRow("0.1% Lows", String.format("%.1f", summary.low01Fps))
                    ResultRow("Avg CPU", "${summary.avgCpuPercent ?: "N/A"}%")
                    ResultRow("Avg GPU Load", "${summary.avgGpuLoadPercent ?: "N/A"}%")
                    ResultRow("Duration", "${summary.durationSec}s")
                    ResultRow("Readings", "${summary.readingCount}")

                    if (profilerRecs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Recommendations", style = MaterialTheme.typography.labelLarge)
                        profilerRecs.forEach { rec ->
                            Text(
                                text = "• ${rec.key}: ${rec.value}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // Show full results dialog
        if (showResults && profilerSession != null) {
            ProfilerResultsDialog(
                session = profilerSession!!,
                recommendations = profilerRecs,
                onApplySettings = { /* save to prefs — open VEGAS config to apply */ },
                onReProfile = { showResults = false },
                onDismiss = { showResults = false },
            )
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
