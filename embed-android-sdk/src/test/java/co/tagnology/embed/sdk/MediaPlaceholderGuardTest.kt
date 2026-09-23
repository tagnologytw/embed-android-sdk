package co.tagnology.embed.sdk

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the script that replaces Chromium's default gray
 * play-button artwork (drawn over poster-less <video> elements before the first
 * frame is decodable) with a black overlay and a spinner.
 *
 * WebView JS cannot run under Robolectric, so these tests pin the script's
 * contract: it must reach same-origin iframes (the widget wrapper hosts the
 * embed in one, which is why v1.0.3 still showed the artwork on FloatingMedia),
 * it must draw the loading overlay, and it must never leave a video hidden
 * forever.
 */
class MediaPlaceholderGuardTest {

    private val script = INJECT_HIDE_MEDIA_PLACEHOLDER_JS

    @Test
    fun guardIsIdempotentPerDocument() {
        assertTrue(script.contains("__tagnologyPosterGuardInjected"))
        assertTrue(script.contains("__tagnologyPosterGuardObserved"))
    }

    @Test
    fun guardReachesSameOriginIframes() {
        assertTrue("must scan <iframe> elements", script.contains("querySelectorAll('iframe')"))
        assertTrue("must read frame.contentDocument", script.contains("frame.contentDocument"))
        assertTrue("must re-attach when the iframe (re)loads", script.contains("frame.addEventListener('load'"))
        assertTrue("dynamically added iframes must be guarded too", script.contains("node.tagName === 'IFRAME'"))
    }

    @Test
    fun guardHidesOnlyPosterlessVideosWithoutAFrame() {
        assertTrue(script.contains("!video.poster && video.readyState < 2"))
        assertTrue(script.contains("video.style.setProperty('opacity', '0', 'important')"))
    }

    @Test
    fun guardShowsBlackOverlayWithSpinnerInsteadOfPlayArtwork() {
        assertTrue(script.contains("tagnology-media-loading"))
        assertTrue("overlay must be opaque black so the artwork cannot bleed through", script.contains("background:#000"))
        assertTrue(script.contains("@keyframes tagnologySpin"))
        assertTrue("overlay must not intercept touches", script.contains("pointer-events:none"))
        assertTrue("overlay must be created in the video's own document (iframe case)", script.contains("video.ownerDocument"))
    }

    @Test
    fun guardRestoresOnFirstFrameAndNeverHidesForever() {
        for (event in listOf("loadeddata", "playing", "timeupdate", "error", "emptied")) {
            assertTrue("must restore on '$event'", script.contains("'$event'"))
        }
        assertTrue(script.contains("var SAFETY_MS = 30000"))
        assertTrue(script.contains("setTimeout(restore, SAFETY_MS)"))
        assertTrue("restore must remove the overlay", script.contains("hideLoading(video)"))
    }
}
