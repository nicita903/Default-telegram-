package com.vector.messenger.feature.media

import android.content.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.*
import com.vector.core.media.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

val LocalMediaViewModel = staticCompositionLocalOf<MediaViewModel?> { null }
data class MediaPresentation(val session: Long = 0, val file: MediaFile? = null, val thumbnail: MediaFile? = null)

@HiltViewModel
class MediaViewModel @Inject constructor(private val repository: MediaRepository) : ViewModel() {
    val state = repository.state
    private val errorState = MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    init {
        viewModelScope.launch { state.map { it.session }.distinctUntilChanged().collect {
            TelegramContentProvider.clear(); MediaImages.clear(); errorState.value = null
        } }
    }
    fun loadMore() = repository.loadMore()
    fun observe(item: MediaItem): Flow<MediaPresentation> = state.map {
        MediaPresentation(it.session, it.files[item.media.file.id], item.media.thumbnail?.let { thumbnail -> it.files[thumbnail.id] })
    }.distinctUntilChanged()
    fun download(item: MediaItem) { errorState.value = null; repository.request(item) }
    fun preview(item: MediaItem) = repository.request(item, managed = false, priority = 1)
    fun pause(id: Int) = repository.pause(id)
    fun remove(id: Int) = repository.remove(id)
    fun external(context: Context, item: MediaItem, share: Boolean) {
        errorState.value = null
        viewModelScope.launch {
            try {
                val file = repository.exportFile(item) ?: run { errorState.value = "File unavailable or sharing restricted. Download again if needed."; return@launch }
                val path = file.path ?: return@launch
                val uri = TelegramContentProvider.uri(context, path, item.media.name, item.media.mime)
                val intent = if (share) Intent(Intent.ACTION_SEND).setType(item.media.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.media.mime)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("Telegram attachment", uri)
                context.startActivity(if (share) Intent.createChooser(intent, "Share file") else intent)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ActivityNotFoundException) { errorState.value = "No application can open this file." }
            catch (_: Exception) { errorState.value = "Unable to open this file. Check its download and permissions." }
        }
    }
}
