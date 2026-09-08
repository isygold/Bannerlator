package com.winlator.star.ui.dialogs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.screens.OutlinedAlertDialog

/**
 * Modal dialog shown on app start when a newer VEGAS release is available.
 *
 * Displays current version, latest version, and release notes. The user can:
 * - "Skip": dismisses the dialog. After 2 skips for the same version, it never appears again.
 * - "Open Releases": opens the GitHub releases page in the browser.
 *
 * Skipped by the caller if the version is already dismissed (skip count >= 2).
 */
@Composable
fun VegasUpdateDialog(
    currentVersion: String,
    latestTag: String,
    releaseBody: String,
    onSkip: () -> Unit,
    onOpenReleases: () -> Unit
) {
    val cleanTag = latestTag.removePrefix("vegas-").removePrefix("v")

    OutlinedAlertDialog(
        onDismissRequest = onSkip,
        title = {
            Text(
                text = "VEGAS Update Available",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Version comparison
                Text(
                    text = "Current:  $currentVersion",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Latest:   $cleanTag",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Release notes
                if (releaseBody.isNotBlank()) {
                    Text(
                        text = "Release Notes",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = releaseBody.trim(),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenReleases) {
                Text("Open Releases")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Skip")
            }
        }
    )
}

/**
 * Helper to open the VEGAS releases page in the browser.
 */
fun openVegasReleasesPage(context: android.content.Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://github.com/isygold/vegas-releases/releases")
    )
    context.startActivity(intent)
}
