package co.tagnology.embed.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the FloatingMedia native height policy.
 *
 * 91APP reported that the category page's filter / sort bar could not be
 * tapped. Cause: after the user closed the floating media, the embed posted
 * `resize {display:none}` and hid its iframe, but the native WebView ignored
 * all height reports for FloatingMedia and stayed a transparent 126x224dp
 * box that swallowed every touch underneath it.
 */
class FloatingMediaHeightTest {

    private val expandedPx = 588 // 224dp @ 2.625

    @Test
    fun hiddenEmbedCollapsesTheNativeWebView() {
        // The wrapper measures a display:none iframe at 0 and reports max(0, 1) = 1.
        assertEquals(FLOATING_MEDIA_COLLAPSED_HEIGHT_PX, resolveFloatingMediaHeightPx(1f, expandedPx))
        assertEquals(FLOATING_MEDIA_COLLAPSED_HEIGHT_PX, resolveFloatingMediaHeightPx(0f, expandedPx))
        assertEquals(FLOATING_MEDIA_COLLAPSED_HEIGHT_PX, resolveFloatingMediaHeightPx(-5f, expandedPx))
    }

    @Test
    fun visibleEmbedAlwaysUsesTheFixedBoxHeight() {
        // FloatingMedia ignores the embed's own height and keeps 224dp (iOS parity),
        // whatever the wrapper measures once the iframe is visible.
        assertEquals(expandedPx, resolveFloatingMediaHeightPx(224f, expandedPx))
        assertEquals(expandedPx, resolveFloatingMediaHeightPx(180f, expandedPx))
        assertEquals(expandedPx, resolveFloatingMediaHeightPx(2f, expandedPx))
        assertEquals(expandedPx, resolveFloatingMediaHeightPx(10000f, expandedPx))
    }

    @Test
    fun reopeningAfterCollapseRestoresTheBox() {
        var current = resolveFloatingMediaHeightPx(224f, expandedPx)
        assertEquals(expandedPx, current)
        current = resolveFloatingMediaHeightPx(1f, expandedPx)
        assertEquals(FLOATING_MEDIA_COLLAPSED_HEIGHT_PX, current)
        current = resolveFloatingMediaHeightPx(224f, expandedPx)
        assertEquals(expandedPx, current)
    }
}
