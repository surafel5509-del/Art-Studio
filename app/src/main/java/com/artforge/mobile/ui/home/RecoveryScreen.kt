package com.artforge.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artforge.mobile.data.db.RecoveryEntity
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Crash-recovery list. Each entry is an autosave snapshot kept after an unclean
 * exit; restoring writes it back over the project package.
 */
@Composable
fun RecoveryScreen(
    recoveries: List<RecoveryEntity>,
    onBack: () -> Unit,
    onRestore: (RecoveryEntity) -> Unit,
    onDiscard: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ForgeColors.Charcoal)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = ForgeColors.TextPrimary) }
            Text("Recovery", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
        }
        if (recoveries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing to recover", style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextSecondary)
                Text(
                    "Autosave snapshots appear here after an unexpected shutdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                items(recoveries) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ForgeColors.Graphite)
                            .clickable { onRestore(entry) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.projectId.take(8),
                                style = MaterialTheme.typography.bodyMedium,
                                color = ForgeColors.TextPrimary,
                            )
                            Text(
                                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.savedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = ForgeColors.TextMuted,
                            )
                        }
                        TextButton(onClick = { onRestore(entry) }) { Text("Restore") }
                        TextButton(onClick = { onDiscard(entry.projectId) }) { Text("Discard", color = ForgeColors.Danger) }
                    }
                }
            }
        }
    }
}
