package com.vector.messenger.calling

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.design.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CallHistoryDialog(chat: Long, onClose: () -> Unit) {
    val vm = LocalCallHistory.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(chat, vm) { vm.open(chat); onDispose { vm.close() } }
    DefaultDialog("Telegram calls", onClose) {
        Text(TelegramCallingCapability.explanation, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.records, key = { it.message }, contentType = { "call_history" }) { record ->
                Column {
                    Text(record.description, color = if (!record.outgoing && record.outcome == "Missed") MaterialTheme.colorScheme.error else DefaultDesign.colors.text,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(callDate(record.date) + if (record.seconds > 0) " · ${record.seconds / 60}:${(record.seconds % 60).toString().padStart(2, '0')}" else "",
                        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            item(key = "page") {
                if (state.loading) Text("Loading call history…", color = DefaultDesign.colors.secondary)
                state.error?.let { Text(it, color = DefaultDesign.colors.secondary) }
                if (state.loaded && state.records.isEmpty()) Text(if (state.more) "No calls in the history scanned so far." else "No calls in this conversation.",
                    color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
                if (!state.loading && state.more) DefaultAction(if (state.error != null) "Retry" else "Load older calls", vm::loadMore, compact = true)
            }
        }
        DefaultAction("Done", onClose, compact = true)
    }
}
private fun callDate(seconds: Long) = if (seconds <= 0) "" else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
