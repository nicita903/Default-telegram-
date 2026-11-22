package com.vector.messenger.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Display grouping only; message order, pagination and stored timestamps are untouched. */
internal fun messageDay(timestamp: Long, zone: ZoneId): LocalDate? =
    if (timestamp <= 0) null else runCatching { Instant.ofEpochSecond(timestamp).atZone(zone).toLocalDate() }.getOrNull()

@Composable
fun MessageDateSeparator(timestamp: Long, olderTimestamp: Long?) {
    val configuration = LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    val day = remember(timestamp, zone) { messageDay(timestamp, zone) } ?: return
    val older = remember(olderTimestamp, zone) { olderTimestamp?.let { messageDay(it, zone) } }
    if (day == older) return
    val locale = configuration.locales.get(0) ?: Locale.getDefault()
    val label = remember(day, locale) { day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
    val colors = DefaultDesign.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.background(colors.panel.copy(alpha = .8f), DefaultDesign.selectionShape)
            .padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = colors.text, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
    }
}
