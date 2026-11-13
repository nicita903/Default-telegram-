package com.vector.messenger

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule

fun awaitConversationKeyboardClosed(compose: AndroidComposeTestRule<*, MainActivity>) {
    compose.runOnUiThread {
        val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
    }
    compose.waitUntil(3000) {
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = compose.onNodeWithTag("message_composer").fetchSemanticsNode().boundsInRoot.bottom
        composerBottom > rootBottom - 100f * compose.density.density
    }
    compose.waitForIdle()
}

/** Captures only the app's Compose surface, never other apps or user account data. */
fun saveDesignScreenshot(node: SemanticsNodeInteraction, name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: File(context.externalMediaDirs.first(), "design-review")
    directory.mkdirs()
    File(directory, "design-$name.png").outputStream().use { stream ->
        node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
}
