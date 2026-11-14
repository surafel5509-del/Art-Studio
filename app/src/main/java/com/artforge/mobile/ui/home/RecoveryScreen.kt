package com.artforge.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
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
import com.artforge.mobile.ui.common.EmptyState
import com.artforge.mobile.ui.common.GestureIconButton
import com.artforge.mobile.ui.common.Hairline
import com.artforge.mobile.ui.common.PillButton
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
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GestureIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 42)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Recovery", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
                Text(
                    "Autosaves kept after an unclean exit",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                )
            }
        }
        Hairline()
        if (recoveries.isEmpty()) {
            EmptyState(
                title = "Nothing to recover",
                message = "Autosave snapshots appear here after an unexpected shutdown.",
                icon = Icons.Filled.History,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                items(recoveries) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ForgeColors.Graphite)
                            .border(1.dp, ForgeColors.Hairline, RoundedCornerShape(16.dp))
                            .clickable { onRestore(entry) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(ForgeColors.Accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = ForgeColors.Accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Snapshot ${entry.projectId.take(8)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = ForgeColors.TextPrimary,
                            )
                            Text(
                                java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(entry.savedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = ForgeColors.TextMuted,
                            )
                        }
                        TextButton(onClick = { onDiscard(entry.projectId) }) {
                            Text("Discard", color = ForgeColors.Danger, style = MaterialTheme.typography.labelLarge)
                        }
                        PillButton("Restore", { onRestore(entry) }, selected = true)
                    }
                }
            }
        }
    }
}
