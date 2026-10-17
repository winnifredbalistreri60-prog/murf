package com.uzairansar.hermex

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.uzairansar.hermex.agent.MurfAvatarGallery
import org.junit.Rule
import org.junit.Test
import java.io.File

class MurfAvatarPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renderGallery() {
        rule.mainClock.autoAdvance = false
        rule.setContent { MurfAvatarGallery(Modifier.testTag("gallery")) }
        rule.mainClock.advanceTimeBy(400)
        val bmp = rule.onNodeWithTag("gallery").captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "murf-avatars.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
