package com.vector.messenger

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

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
