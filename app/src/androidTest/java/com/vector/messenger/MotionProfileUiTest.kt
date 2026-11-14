package com.vector.messenger

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.*
import com.vector.core.demo.DemoChatRepository
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exploratory debug/instrumentation replay, not a Macrobenchmark or an FPS guarantee.
 * Same workload is run before/after. Runtime counters are applied-change batches, not
 * component recomposition counts; FrameMetrics cover the Activity window, not dialogs. */
@OptIn(ExperimentalComposeRuntimeApi::class)
class MotionProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun before() {
        appearance.before()
        compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(compose.activity)[PreferencesViewModel::class.java].appearance { it.copy(haptics = false) } }
    }
    @After fun after() { compose.mainClock.autoAdvance = true; appearance.after() }

    private inner class Sampler : AutoCloseable {
        private val thread = HandlerThread("motion-frame-metrics").apply { start() }
        private val handler = Handler(thread.looper)
        private val lock = Any()
        private var frames: MutableList<Pair<Long, Long>>? = null
        private var dropped = 0
        private val infos = ConcurrentHashMap.newKeySet<RecomposerInfo>()
        private val observing = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val job = observing.launch { Recomposer.runningRecomposers.collect { infos.addAll(it) } }
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) synchronized(lock) {
                frames?.let {
                    val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
                    if (duration > 0) it += duration to deadline
                    dropped += dropCount
                }
            }
        }
        init { compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, handler) } }
        private fun drain() { val latch = CountDownLatch(1); handler.post { latch.countDown() }; check(latch.await(2, TimeUnit.SECONDS)) }
        fun measure(name: String, trial: Int, workload: () -> Unit): JSONObject {
            compose.waitForIdle(); drain()
            infos.addAll(Recomposer.runningRecomposers.value)
            val start = infos.associateWith { it.changeCount }
            synchronized(lock) { frames = mutableListOf(); dropped = 0 }
            workload()
            compose.waitForIdle(); drain()
            val (samples, reportsDropped) = synchronized(lock) { val sample = frames.orEmpty().toList(); frames = null; sample to dropped }
            val applied = infos.sumOf { (it.changeCount - (start[it] ?: 0L)).coerceAtLeast(0) }
            val sorted = samples.map { it.first / 1_000_000.0 }.sorted()
            fun percentile(fraction: Double): Any = if (sorted.isEmpty()) JSONObject.NULL else sorted[((sorted.size - 1) * fraction).toInt()]
            return JSONObject().put("workload", name).put("trial", trial).put("appliedChanges", applied)
                .put("frames", samples.size).put("totalDurationP50Ms", percentile(.5)).put("totalDurationP95Ms", percentile(.95))
                .put("deadlineFrames", samples.count { it.second > 0 }).put("missedDeadlineFrames", samples.count { it.second > 0 && it.first >= it.second })
                .put("droppedMetricReports", reportsDropped)
        }
        override fun close() {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            observing.cancel(); runBlocking { job.join() }
            thread.quitSafely(); thread.join(2000)
            check(!thread.isAlive) { "Frame sampler thread did not stop" }
        }
    }
    private fun write(name: String, measurements: JSONArray) {
        val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("motionProfileLabel", "check").replace(Regex("[^a-zA-Z0-9_-]"), "")
        val directory = arguments.getString("additionalTestOutputDir")?.let(::File) ?: File(compose.activity.externalMediaDirs.first(), "motion-profile")
        directory.mkdirs()
        File(directory, "motion-$label-$name.json").writeText(JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("build", "debug-instrumented").put("label", label).put("method", "Compose frame-clock replay; Activity FrameMetrics; applied-change batches")
            .put("samples", measurements).toString(2))
    }
    private fun pullCancel(layer: SemanticsNodeInteraction, start: Offset) {
        val step = with(compose.density) { 3.dp.toPx() }
        layer.performTouchInput { down(start) }
        repeat(20) { layer.performTouchInput { moveBy(Offset(0f, step), 16) }; compose.mainClock.advanceTimeByFrame() }
        layer.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(600)
    }
    @Test fun profilePullPressAndNavigation() {
        val measurements = JSONArray()
        // Unmeasured warm-up, including first composition of each search scope.
        openChatSearch(compose)
        compose.onNodeWithContentDescription("Switch to Music Search").performClick()
        compose.onNodeWithContentDescription("Switch to Chat Search").performClick()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        Sampler().use { sample ->
            repeat(3) { trial ->
                compose.mainClock.autoAdvance = false
                measurements.put(sample.measure("chats_pull_cancel", trial) {
                    val list = compose.onNodeWithTag("chat_list")
                    val bounds = list.fetchSemanticsNode().boundsInRoot
                    pullCancel(list, Offset(bounds.width / 2, 30f))
                })
                measurements.put(sample.measure("icon_press_cancel", trial) {
                    val icon = compose.onNodeWithContentDescription("Open navigation")
                    repeat(3) {
                        icon.performTouchInput { down(center) }
                        compose.mainClock.advanceTimeBy(64)
                        icon.performTouchInput { cancel() }
                        compose.mainClock.advanceTimeBy(400)
                    }
                })
                compose.mainClock.autoAdvance = true
                openChatSearch(compose)
                compose.mainClock.autoAdvance = false
                measurements.put(sample.measure("search_pull_cancel", trial) {
                    val layer = compose.onNodeWithTag("search_layer")
                    val layerBounds = layer.fetchSemanticsNode().boundsInRoot
                    val header = compose.onNodeWithTag("search_scope").fetchSemanticsNode().boundsInRoot.center
                    pullCancel(layer, header - layerBounds.topLeft)
                })
                compose.mainClock.autoAdvance = true
                compose.onNodeWithContentDescription("Close search").performClick()
                measurements.put(sample.measure("module_search_roundtrip", trial) {
                    compose.onNodeWithContentDescription("Open navigation").performClick()
                    compose.onNodeWithText("Music").performClick()
                    compose.onNodeWithContentDescription("Open navigation").performClick()
                    compose.onNodeWithText("Chats").performClick()
                    openChatSearch(compose)
                    compose.onNodeWithContentDescription("Switch to Music Search").performClick()
                    compose.onNodeWithContentDescription("Switch to Chat Search").performClick()
                    compose.onNodeWithContentDescription("Close search").performClick()
                })
                compose.onNodeWithTag("chat_list").assertIsDisplayed()
            }
        }
        write("interactions", measurements)
    }
    @Test fun profileLongChatListScrolling() {
        val repository = object : ChatRepository by DemoChatRepository() {
            override val chats = MutableStateFlow((1L..80L).map { Chat(it, "Profile fixture $it", "Test-only preview", "12:00") })
        }
        lateinit var chats: ChatsViewModel
        compose.runOnUiThread {
            val provider = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatsViewModel(repository) as T
            })
            chats = provider.get("profileChats", ChatsViewModel::class.java)
            compose.activity.setContent { DefaultTheme(ThemeMode.Dark) { ChatsScreen(chats, {}) } }
        }
        compose.waitUntil(3000) { chats.filteredChats.value.size == 80 }
        val measurements = JSONArray()
        Sampler().use { sample ->
            repeat(3) { trial ->
                val list = compose.onNodeWithTag("chat_list")
                list.performScrollToIndex(20)
                compose.mainClock.autoAdvance = false
                measurements.put(sample.measure("long_list_scroll", trial) {
                    val bounds = list.fetchSemanticsNode().boundsInRoot
                    val step = with(compose.density) { 10.dp.toPx() }
                    repeat(6) { swipe ->
                        val direction = if (swipe % 2 == 0) -1 else 1
                        list.performTouchInput { down(Offset(bounds.width / 2, bounds.height / 2)) }
                        repeat(24) { list.performTouchInput { moveBy(Offset(0f, direction * step), 16) }; compose.mainClock.advanceTimeByFrame() }
                        list.performTouchInput { up() }
                        compose.mainClock.advanceTimeBy(600)
                    }
                })
                compose.mainClock.autoAdvance = true
            }
        }
        write("scroll", measurements)
    }
}
