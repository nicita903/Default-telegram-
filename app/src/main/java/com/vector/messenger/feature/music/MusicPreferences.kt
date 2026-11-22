package com.vector.messenger.feature.music

import android.content.Context
import com.vector.core.music.*
import org.json.JSONArray
import org.json.JSONObject

/** Private local identifiers only: never persists Telegram paths, credentials or track metadata. */
class MusicPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("music_library_v1", Context.MODE_PRIVATE)
    private val playback = context.getSharedPreferences("music_playback_v1", Context.MODE_PRIVATE)
    private var last: MusicSettings? = null
    fun load(): MusicSettings = runCatching {
        val base = decodeMusicSettings(JSONObject(prefs.getString("state", "{}") ?: "{}"))
        val queue = playback.getString("queue", null)?.let { decodeMusicSettings(JSONObject().put("queue", JSONObject(it))).queue } ?: base.queue
        base.copy(queue = queue).also { last = it }
    }.getOrDefault(MusicSettings())
    fun save(value: MusicSettings) {
        // Progress writes must not rewrite a potentially large playlist file every few seconds.
        if (last?.playlists != value.playlists || last?.recent != value.recent)
            prefs.edit().putString("state", encodeMusicSettings(value.copy(queue = MusicQueue())).toString()).apply()
        playback.edit().putString("queue", encodeMusicSettings(MusicSettings(queue = value.queue)).getJSONObject("queue").toString()).apply()
        last = value
    }
}
internal fun decodeMusicSettings(json: JSONObject): MusicSettings {
    fun keys(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotBlank) }.distinct().take(1000)
    val queue = json.optJSONObject("queue") ?: JSONObject()
    val playlists = json.optJSONArray("playlists")
    return MusicSettings((0 until (playlists?.length() ?: 0)).mapNotNull { index -> playlists?.optJSONObject(index)?.let {
        MusicPlaylist(it.getString("id"), it.optString("name").take(80), keys(it.optJSONArray("keys")))
    } }.take(200), keys(json.optJSONArray("recent")).take(100), MusicQueue(keys(queue.optJSONArray("keys")),
        queue.optInt("index"), queue.optLong("position"), queue.optBoolean("shuffle"),
        runCatching { MusicRepeat.valueOf(queue.optString("repeat")) }.getOrDefault(MusicRepeat.Off), queue.optDouble("speed", 1.0).toFloat()).normalized())
}
internal fun encodeMusicSettings(value: MusicSettings) = JSONObject()
    .put("playlists", JSONArray().apply { value.playlists.filterNot { it.readOnly }.take(200).forEach {
        put(JSONObject().put("id", it.id).put("name", it.name).put("keys", JSONArray(it.keys.take(1000))))
    } }).put("recent", JSONArray(value.recent.take(100)))
    .put("queue", value.queue.normalized().let { JSONObject().put("keys", JSONArray(it.keys)).put("index", it.index)
        .put("position", it.position).put("shuffle", it.shuffle).put("repeat", it.repeat.name).put("speed", it.speed.toDouble()) })
