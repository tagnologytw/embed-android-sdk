package co.tagnology.embed.sdk

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for widget links that leave the embed.
 *
 * 91APP reported that tapping a post in a wall whose click event is "go to Instagram"
 * replaced the whole widget with the Instagram page. The embed calls
 * `window.open(instagramUrl, "_blank")`; without multiple-window support the widget
 * WebView navigated its own main frame there, and Instagram's redirect to an
 * `intent://` app link then showed "Webpage not available".
 */
@RunWith(RobolectricTestRunner::class)
class WidgetExternalLinkTest {

    @Test
    fun mainFrameNavigationToInstagramLeavesTheWidget() {
        assertTrue(shouldOpenOutsideWidget("https://www.instagram.com/p/DRvsalggSBt", isMainFrame = true))
        assertTrue(shouldOpenOutsideWidget("https://www.youtube.com/shorts/abc", isMainFrame = true))
    }

    @Test
    fun nonWebSchemesLeaveTheWidget() {
        assertTrue(
            shouldOpenOutsideWidget(
                "intent://applink.instagram.com/p/DRvsalggSBt#Intent;action=android.intent.action.VIEW;scheme=https;end",
                isMainFrame = true,
            )
        )
        assertTrue(shouldOpenOutsideWidget("line://ti/p/@abc", isMainFrame = true))
        assertTrue(shouldOpenOutsideWidget("mailto:a@b.co", isMainFrame = true))
    }

    @Test
    fun embedOwnPagesStayInTheWidget() {
        assertFalse(shouldOpenOutsideWidget("https://embed.tagnology.co/display?folderId=x", isMainFrame = true))
        assertFalse(shouldOpenOutsideWidget("about:blank", isMainFrame = true))
        assertFalse(shouldOpenOutsideWidget("data:text/html,hi", isMainFrame = true))
    }

    @Test
    fun subFrameNavigationsAreNeverIntercepted() {
        assertFalse(shouldOpenOutsideWidget("https://www.instagram.com/p/DRvsalggSBt", isMainFrame = false))
    }

    @Test
    fun intentUrlIsParsedIntoABrowsableIntent() {
        val intent = buildExternalIntent(
            "intent://applink.instagram.com/p/DRvsalggSBt?utm_source=instagramweb" +
                "#Intent;action=android.intent.action.VIEW;scheme=https;" +
                "S.browser_fallback_url=https%3A%2F%2Fwww.instagram.com%2Fp%2FDRvsalggSBt;end"
        )
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://applink.instagram.com/p/DRvsalggSBt?utm_source=instagramweb", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.component)
        assertEquals("https://www.instagram.com/p/DRvsalggSBt", intent.getStringExtra("browser_fallback_url"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun httpUrlBecomesAViewIntent() {
        val intent = buildExternalIntent("https://www.instagram.com/p/DRvsalggSBt")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.instagram.com/p/DRvsalggSBt", intent.dataString)
    }
}
