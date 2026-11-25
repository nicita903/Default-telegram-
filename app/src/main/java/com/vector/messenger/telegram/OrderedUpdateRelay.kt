package com.vector.messenger.telegram

import android.content.Context
import android.os.SystemClock
import com.vector.core.messaging.SessionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Lossless FIFO per feature. A slow subscriber must never block TDLib receive/RPC completion.
 * Epoch validation happens at delivery too, so buffered events cannot cross account boundaries.
 */
internal class OrderedUpdateRelay<T>(scope: CoroutineScope, private val context: Context,
    private val epoch: () -> Long, private val authorized: () -> Boolean) {
    private class Event<T>(val epoch: Long, val value: T)
    private val queue = Channel<Event<T>>(Channel.UNLIMITED)
    private val output = MutableSharedFlow<Event<T>>(extraBufferCapacity = 64)
    init {
        scope.launch(Dispatchers.IO) {
            for (event in queue) {
                if (event.epoch != epoch() || !authorized()) continue
                val start = SystemClock.elapsedRealtime()
                output.emit(event)
                val duration = SystemClock.elapsedRealtime() - start
                if (duration >= 100) SessionDiagnostics.mark(context, SessionEvent.DispatchDelayed, duration)
            }
        }
    }
    fun emit(value: T) { check(queue.trySend(Event(epoch(), value)).isSuccess) }
    fun asSharedFlow(): Flow<T> = output.asSharedFlow().filter { it.epoch == epoch() && authorized() }.map { it.value }
}
