package com.belugachat.app

import android.os.Looper
import com.belugachat.app.chat.GenerationService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationServiceTest {
    @Test fun goesForegroundThenStopsWhenNothingIsStreaming() {
        val service = Robolectric.buildService(GenerationService::class.java).create().startCommand(0, 1).get()
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        assertFalse(shadow.isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(shadow.isStoppedBySelf)
    }
}
