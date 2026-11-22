package com.vector.messenger.feature

import android.content.Context
import android.icu.text.BreakIterator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vector.messenger.design.*

private val EmojiCategories = linkedMapOf(
    "Faces" to "😀 😃 😄 😁 😆 😅 😂 🙂 🙃 😉 😊 😍 🥰 😘 😎 🤔 😢 😭 😡 🤗 🫠 🥹 😴 🤣",
    "People" to "👍 👎 👏 🙌 👋 🤝 🙏 💪 ✌️ 🤞 🫶 👌 🤷 🤦 🧑‍💻 👨‍👩‍👧‍👦",
    "Nature" to "🐶 🐱 🦊 🐼 🐻 🦋 🐝 🌻 🌸 🌳 🍀 🌍 🌙 ☀️ ⭐ 🌈",
    "Food" to "🍎 🍊 🍓 🍉 🍇 🥑 🥐 🍞 🍕 🍔 🍟 🍣 🍰 🍫 ☕ 🍵",
    "Things" to "🎉 🎊 🎁 🎈 🎵 🎧 🎮 ⚽ 🏆 🚀 🚗 ✈️ 💡 📚 📷 💻",
    "Symbols" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💯 🔥 ✨ ✅ ❌ ❗ ❓ ♻️",
).mapValues { it.value.split(' ') }

internal fun insertEmoji(value: TextFieldValue, emoji: String): TextFieldValue {
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(value.text) }
    val rawStart = value.selection.min.coerceIn(0, value.text.length)
    val rawEnd = value.selection.max.coerceIn(rawStart, value.text.length)
    val start = if (boundaries.isBoundary(rawStart)) rawStart else boundaries.preceding(rawStart).coerceAtLeast(0)
    val end = if (rawStart == rawEnd) start else if (boundaries.isBoundary(rawEnd)) rawEnd else boundaries.following(rawEnd).coerceAtMost(value.text.length)
    return TextFieldValue(value.text.replaceRange(start, end, emoji), TextRange(start + emoji.length))
}

/** Unicode-only picker. Recents contain emoji from this catalog, never message text. */
@Composable
internal fun ConversationEmojiPicker(onInsert: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("emoji_recents", Context.MODE_PRIVATE) }
    val catalog = remember { EmojiCategories.values.flatten().toSet() }
    var recent by remember { mutableStateOf(preferences.getString("items", "").orEmpty().split('|').filter { it in catalog }.take(24)) }
    var category by rememberSaveable { mutableStateOf("Faces") }
    Column(Modifier.fillMaxWidth().height(248.dp).semantics { paneTitle = "Emoji picker" }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (listOf("Recent") + EmojiCategories.keys).forEach { name ->
                Box(Modifier.height(48.dp).widthIn(min = 48.dp).clickable(role = Role.Tab) { category = name }
                    .padding(horizontal = 12.dp).semantics { selected = category == name }, contentAlignment = Alignment.Center) {
                    Text(name, color = if (category == name) DefaultDesign.colors.accent else DefaultDesign.colors.secondary,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val entries = if (category == "Recent") recent else EmojiCategories[category].orEmpty()
        if (entries.isEmpty()) Text("Your recently used emoji appear here.", color = DefaultDesign.colors.secondary,
            modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        LazyVerticalGrid(GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(entries, key = { it }) { emoji ->
                Box(Modifier.height(48.dp).clickable(role = Role.Button, onClickLabel = "Insert $emoji") {
                    onInsert(emoji)
                    recent = (listOf(emoji) + recent.filterNot { it == emoji }).take(24)
                    preferences.edit().putString("items", recent.joinToString("|")).apply()
                }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
            }
        }
    }
}
