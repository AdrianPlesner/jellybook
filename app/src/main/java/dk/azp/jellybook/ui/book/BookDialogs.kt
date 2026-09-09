package dk.azp.jellybook.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.playback.SleepTimerState
import dk.azp.jellybook.ui.formatClock
import dk.azp.jellybook.ui.formatRelativeTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    activeTimer: SleepTimerState,
    chapters: List<Chapter>,
    currentChapter: Chapter?,
    onMinutes: (Int) -> Unit,
    onChapterEnd: (Chapter) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    var customMinutes by remember { mutableStateOf("") }
    val upcoming = remember(chapters, currentChapter) {
        val startIndex = currentChapter?.let { chapters.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        chapters.drop(startIndex)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (activeTimer.isActive) {
                    Text(activeTimerDescription(activeTimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                }
                Text("Stop after", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                        AssistChip(onClick = { onMinutes(minutes) }, label = { Text("$minutes min") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter(Char::isDigit).take(3) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { customMinutes.toIntOrNull()?.takeIf { it > 0 }?.let(onMinutes) }, enabled = (customMinutes.toIntOrNull() ?: 0) > 0) {
                        Text("Start")
                    }
                }
                if (upcoming.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Stop at the end of a chapter", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(upcoming, key = { it.index }) { chapter ->
                            ListItem(
                                headlineContent = { Text(chapter.title) },
                                supportingContent = { Text("Ends at ${formatClock(chapter.endMs)}") },
                                modifier = Modifier.clickable { onChapterEnd(chapter) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeTimer.isActive) {
                TextButton(onClick = onTurnOff) { Text("Turn off") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun activeTimerDescription(timer: SleepTimerState): String {
    val endsAt = timer.endsAtEpochMs
    return when {
        endsAt != null -> {
            val remaining = ((endsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
            "Active: stops in about $remaining min"
        }
        timer.label != null -> "Active: ${timer.label}"
        else -> "Active"
    }
}

@Composable
fun BookmarkNameDialog(
    title: String,
    initialName: String,
    positionMs: Long,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("At ${formatClock(positionMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RemoveDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove download?") },
        text = { Text("The offline copy is deleted from this device. Your progress and bookmarks are kept.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CheckpointSummary(label: String, positionMs: Long, updatedAtEpochMs: Long?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(formatClock(positionMs), style = MaterialTheme.typography.titleLarge)
        Text(
            updatedAtEpochMs?.let { "Updated ${formatRelativeTime(it)}" } ?: "Unknown time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
