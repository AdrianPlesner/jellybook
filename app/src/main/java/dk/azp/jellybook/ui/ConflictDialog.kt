package dk.azp.jellybook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dk.azp.jellybook.data.local.ProgressConflict
import dk.azp.jellybook.ui.book.CheckpointSummary

/** Shown when this device has unsynced progress and the server position moved in the meantime. */
@Composable
fun ProgressConflictDialog(
    conflict: ProgressConflict,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Which progress should be kept?") },
        text = {
            Column {
                Text(
                    "“${conflict.title}” was also played elsewhere while this device was offline. Pick the checkpoint to continue from; the other one is discarded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    CheckpointSummary("This device", conflict.localPositionMs, conflict.localUpdatedAtEpochMs, Modifier.weight(1f))
                    CheckpointSummary("Server", conflict.serverPositionMs, conflict.serverUpdatedAtEpochMs, Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onKeepLocal) { Text("Keep this device") } },
        dismissButton = {
            Row {
                TextButton(onClick = onLater) { Text("Later") }
                TextButton(onClick = onKeepServer) { Text("Keep server") }
            }
        },
    )
}
