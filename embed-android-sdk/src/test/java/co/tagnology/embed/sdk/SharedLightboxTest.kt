package co.tagnology.embed.sdk

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The lightbox is shared per page and window (91APP's category page used to
 * load up to seven lightbox pages at once), and its WebView must stay clear of
 * the navigation bar (91APP: product cards at the bottom of a story sat under
 * the 3-button / gesture bar).
 */
@RunWith(RobolectricTestRunner::class)
class SharedLightboxTest {

    private val page = "https://partnertest7.91app.com/v2/official/salepagecategory/532417"

    private class RecordingClient : LightboxClient {
        var dismissed = 0
        override fun onLightboxEvent(event: EmbedWidgetEvent) = Unit
        override fun onLightboxDismissed() {
            dismissed++
        }
    }

    private fun newHost(activity: Activity): View {
        val content = FrameLayout(activity)
        activity.setContentView(content)
        return View(activity).also { content.addView(it) }
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun slotsOfTheSamePageShareOneLightbox() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = newHost(activity)
        val a = RecordingClient()
        val b = RecordingClient()

        val first = SharedLightbox.acquire(activity, page, host)
        val second = SharedLightbox.acquire(activity, page, host)
        assertSame(first, second)
        assertEquals(1, SharedLightbox.sharedCountForTest())

        SharedLightbox.release(first, a)
        assertEquals("still held by the other slot", 1, SharedLightbox.sharedCountForTest())
        SharedLightbox.release(second, b)
        assertEquals(0, SharedLightbox.sharedCountForTest())
    }

    @Test
    fun otherPagesAndWindowsGetTheirOwnLightbox() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val otherActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val client = RecordingClient()

        val base = SharedLightbox.acquire(activity, page, newHost(activity))
        val otherPage = SharedLightbox.acquire(activity, "$page/other", base.let { newHost(activity) })
        val otherWindow = SharedLightbox.acquire(otherActivity, page, newHost(otherActivity))
        assertNotSame(base, otherPage)
        assertNotSame(base, otherWindow)

        listOf(base, otherPage, otherWindow).forEach { SharedLightbox.release(it, client) }
        assertEquals(0, SharedLightbox.sharedCountForTest())
    }

    @Test
    fun dismissNotifiesOnlyTheOwnerOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = newHost(activity)
        val owner = RecordingClient()
        val other = RecordingClient()
        val lightbox = SharedLightbox.acquire(activity, page, host)
        shadowOf(Looper.getMainLooper()).idle()

        lightbox.open(owner, """{"eventType":"click"}""", host)
        assertTrue(lightbox.isOpenFor(owner))
        assertFalse(lightbox.isOpenFor(other))

        lightbox.closeFor(other)
        assertTrue("another slot cannot close it", lightbox.isVisible)

        lightbox.dismiss("back")
        lightbox.dismiss("back")
        assertEquals(1, owner.dismissed)
        assertEquals(0, other.dismissed)
        assertFalse(lightbox.isVisible)

        SharedLightbox.release(lightbox, owner)
    }

    @Test
    fun releasingTheOwnerClosesTheLightbox() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = newHost(activity)
        val owner = RecordingClient()
        val other = RecordingClient()
        val lightbox = SharedLightbox.acquire(activity, page, host)
        SharedLightbox.acquire(activity, page, host)
        lightbox.open(owner, null, host)

        SharedLightbox.release(lightbox, owner)
        assertFalse(lightbox.isVisible)
        assertEquals("disposal is not a user dismiss", 0, owner.dismissed)

        SharedLightbox.release(lightbox, other)
    }

    @Test
    fun webViewAvoidsStatusAndNavigationBars() {
        val insets = resolveLightboxContentInsets(
            barsLeft = 0, barsTop = 136, barsRight = 0, barsBottom = 126,
            overlayLeft = 0, overlayTop = 0, overlayRight = 0, overlayBottom = 0,
            hasWindowInsets = true, fallbackTop = 136,
        )
        assertEquals(LightboxContentInsets(0, 136, 0, 126), insets)
    }

    @Test
    fun insetsTheOverlayAlreadyAvoidsAreNotAppliedTwice() {
        // Non edge-to-edge window: the overlay itself already ends above the nav bar.
        val insets = resolveLightboxContentInsets(
            barsLeft = 0, barsTop = 136, barsRight = 0, barsBottom = 126,
            overlayLeft = 0, overlayTop = 0, overlayRight = 0, overlayBottom = 126,
            hasWindowInsets = true, fallbackTop = 136,
        )
        assertEquals(LightboxContentInsets(0, 136, 0, 0), insets)
    }

    @Test
    fun landscapeNavigationBarAndCutoutOnTheSide() {
        val insets = resolveLightboxContentInsets(
            barsLeft = 80, barsTop = 0, barsRight = 126, barsBottom = 0,
            overlayLeft = 0, overlayTop = 0, overlayRight = 0, overlayBottom = 0,
            hasWindowInsets = true, fallbackTop = 63,
        )
        assertEquals(LightboxContentInsets(80, 0, 126, 0), insets)
    }

    @Test
    fun withoutWindowInsetsFallsBackToStatusBarHeight() {
        val insets = resolveLightboxContentInsets(
            barsLeft = 0, barsTop = 0, barsRight = 0, barsBottom = 0,
            overlayLeft = 0, overlayTop = 0, overlayRight = 0, overlayBottom = 0,
            hasWindowInsets = false, fallbackTop = 63,
        )
        assertEquals(LightboxContentInsets(0, 63, 0, 0), insets)
    }

    @Test
    fun onlyThePagesOwnToggleIsACleanClose() {
        assertTrue(isCleanPageClose("""{"eventType":"toggleLB","open":false}"""))
        assertFalse(isCleanPageClose("""{"eventType":"toggleLB","open":false,"reason":"native_touch_close_zone"}"""))
        assertFalse(isCleanPageClose("""{"eventType":"toggleLB","open":false,"reason":"close_zone_click"}"""))
        assertFalse(isCleanPageClose("not json"))
    }
}
