package com.vector.messenger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.vector.messenger.design.auroraColors
import com.vector.messenger.design.backdropScrimAlpha
import com.vector.messenger.design.canBlurBackdrop
import com.vector.messenger.design.messageDay
import com.vector.messenger.feature.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** The app has exactly one theme now; these assertions pin its canvas, lights and contrast. */
class AuroraThemeTest {
    @Test fun singleThemeUsesOneDarkCanvasAndThreeAccents() {
        val base = auroraColors(0)
        assertEquals(Color(0xFF070910), base.base)
        assertEquals(Color.Transparent, base.canvas)
        assertEquals(Color(0xFF7C8CFF), base.accent)
        assertEquals(Color(0xFF48E3C0), base.secondaryAccent)
        assertEquals(Color(0xFFA78BFA), auroraColors(1).accent)
        assertEquals(Color(0xFF48E3C0), auroraColors(2).accent)
        assertEquals(Color(0xFF7C8CFF), auroraColors(2).secondaryAccent)
        // Out-of-range stored values clamp instead of producing a fourth theme.
        assertEquals(base, auroraColors(-1))
        assertEquals(auroraColors(2), auroraColors(9))
    }

    @Test fun amoledKeepsPureBlackSurfacesWithoutChangingTheAccent() {
        val amoled = auroraColors(1, amoled = true)
        assertEquals(Color.Black, amoled.base)
        assertEquals(Color.Black, amoled.panel)
        assertEquals(Color.Black, amoled.mediaCanvas)
        assertEquals(Color.Transparent, amoled.canvas)
        assertEquals(auroraColors(1).accent, amoled.accent)
        assertEquals(auroraColors(1).text, amoled.text)
    }

    @Test fun textRemainsReadableOnEveryAccentAndSurface() {
        fun contrast(a: Color, b: Color): Float = (max(a.luminance(), b.luminance()) + .05f) / (min(a.luminance(), b.luminance()) + .05f)
        for (accent in 0..2) for (amoled in listOf(false, true)) {
            val colors = auroraColors(accent, amoled)
            for (surface in listOf(colors.base, colors.panel, colors.raised, colors.outgoing)) {
                assertTrue("Primary $accent/$amoled", contrast(colors.text, surface) >= 4.5f)
                assertTrue("Secondary $accent/$amoled", contrast(colors.secondary, surface) >= 4.5f)
            }
            assertTrue("Action $accent/$amoled", contrast(colors.onAccent, colors.accent) >= 4.5f)
        }
    }

    @Test fun changingAppearanceLeavesGestureAndMotionSettingsIndependent() {
        val original = AppearanceSettings(distance = 48, hold = 320, speed = 125, zoom = 8,
            sensitivity = 120, autoLock = false, haptics = false, reducedMotion = true)
        val themed = original.copy(accent = 2, amoledBlack = true).normalized()
        assertEquals(original.distance, themed.distance)
        assertEquals(original.hold, themed.hold)
        assertEquals(original.reducedMotion, themed.reducedMotion)
        assertEquals(original.haptics, themed.haptics)
    }

    @Test fun dateHeadersUseExistingTimestampsAndRespectLocalDayBoundaries() {
        val timestamp = Instant.parse("2026-11-15T22:30:00Z").epochSecond
        assertEquals(LocalDate.of(2026, 11, 15), messageDay(timestamp, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 11, 16), messageDay(timestamp, ZoneOffset.ofHours(2)))
    }

    @Test fun missingAndInvalidDatesDoNotFabricateMessageHeaders() {
        assertNull(messageDay(0, ZoneOffset.UTC))
        assertNull(messageDay(-1, ZoneOffset.UTC))
        assertNull(messageDay(Long.MAX_VALUE, ZoneOffset.UTC))
    }

    @Test fun menuStaysOpaqueWheneverTheBackdropBlurCannotRender() {
        // API 29/30 have no render effect, so a translucent sheet would show a sharp list behind it.
        assertFalse(canBlurBackdrop(29, glassBlur = 3))
        assertFalse(canBlurBackdrop(30, glassBlur = 3))
        // Radius zero falls back exactly the same way.
        assertFalse(canBlurBackdrop(33, glassBlur = 0))
        assertEquals(.92f, backdropScrimAlpha(canBlurBackdrop(30, glassBlur = 2)), 0f)
        assertTrue(backdropScrimAlpha(false) >= .9f)
    }

    @Test fun menuSoftensItsScrimOnlyWhenTheBlurActuallyRenders() {
        assertTrue(canBlurBackdrop(31, glassBlur = 1))
        assertTrue(canBlurBackdrop(36, glassBlur = 3))
        assertEquals(.62f, backdropScrimAlpha(canBlurBackdrop(36, glassBlur = 2)), 0f)
        assertTrue(backdropScrimAlpha(true) < backdropScrimAlpha(false))
    }
}
