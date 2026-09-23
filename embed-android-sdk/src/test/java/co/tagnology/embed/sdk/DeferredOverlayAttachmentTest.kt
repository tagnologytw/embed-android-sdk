package co.tagnology.embed.sdk

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for the lightbox overlay's deferred DecorView mutations.
 *
 * Synchronously adding/removing the overlay from a Compose effect can run
 * inside a layout traversal (e.g. RecyclerView recycling a ComposeView during
 * dispatchLayout) and crash FrameLayout.layoutChildren with an NPE on a stale
 * child index — see mechanismNote test. DeferredOverlayAttachment must
 * therefore never touch the root's children synchronously, and must never
 * leak the overlay when disposal races the posted attach.
 *
 * The overlay must also land on the window's DecorView, never on a detached
 * subtree: a host inside a recycled RecyclerView cell reports the cell as its
 * rootView while it sits in the pool, which squeezed the lightbox into the
 * list item (91APP report) — see the *detachedHost* tests.
 *
 * Robolectric's paused main looper lets each test assert the state between
 * "scheduled" and "executed".
 */
@RunWith(RobolectricTestRunner::class)
class DeferredOverlayAttachmentTest {

    private lateinit var activity: Activity
    private lateinit var content: FrameLayout
    private lateinit var decor: ViewGroup
    private lateinit var host: View
    private lateinit var overlay: View
    private var detachedCount = 0
    private lateinit var attachment: DeferredOverlayAttachment

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        content = FrameLayout(activity)
        activity.setContentView(content)
        decor = activity.window.decorView as ViewGroup
        host = View(activity)
        content.addView(host)
        assertTrue("test precondition: host is attached to the window", host.isAttachedToWindow)
        overlay = View(activity)
        attachment = DeferredOverlayAttachment(host, overlay) { detachedCount++ }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun attachDoesNotTouchRootSynchronously() {
        val before = decor.childCount
        attachment.attach()

        assertNull("overlay must not attach during the current traversal", overlay.parent)
        assertEquals(before, decor.childCount)

        idleMainLooper()
        assertSame(decor, overlay.parent)
    }

    @Test
    fun attachTargetsTheWindowDecorViewNotTheHostContainer() {
        attachment.attach()
        idleMainLooper()

        assertSame(decor, overlay.parent)
        assertNotSame(content, overlay.parent)
    }

    @Test
    fun disposeDoesNotTouchRootSynchronously() {
        attachment.attach()
        idleMainLooper()
        assertSame(decor, overlay.parent)

        attachment.dispose()

        assertSame("overlay must not detach during the current traversal", decor, overlay.parent)
        assertEquals(0, detachedCount)

        idleMainLooper()
        assertNull(overlay.parent)
        assertEquals(1, detachedCount)
    }

    @Test
    fun disposeBeforePostedAttachRunsNeverAttachesAndStillCleansUp() {
        // The race the guard exists for: composition and disposal both happen
        // inside one traversal (e.g. a RecyclerView bind + recycle), before
        // any posted message has run.
        val before = decor.childCount
        attachment.attach()
        attachment.dispose()

        idleMainLooper()

        assertNull("cancelled attach must never add the overlay", overlay.parent)
        assertEquals(before, decor.childCount)
        assertEquals("onDetached cleanup must still run exactly once", 1, detachedCount)
    }

    @Test
    fun disposeIsIdempotent() {
        attachment.attach()
        idleMainLooper()

        attachment.dispose()
        attachment.dispose()
        idleMainLooper()

        assertNull(overlay.parent)
        assertEquals(1, detachedCount)
    }

    @Test
    fun attachAfterDisposeStaysDetached() {
        attachment.dispose()
        attachment.attach()

        idleMainLooper()

        assertNull(overlay.parent)
        assertEquals(1, detachedCount)
    }

    /**
     * The 91APP symptom: the host lives in a RecyclerView cell that is being
     * re-bound while detached (in the recycled pool). Its rootView is the cell,
     * so a naive `host.rootView.addView(overlay)` puts the full-screen lightbox
     * inside the list item. The overlay must instead wait and land on the
     * DecorView once the cell is attached to the window.
     */
    @Test
    fun detachedHost_waitsForWindowAttachThenTargetsDecorView() {
        val cell = FrameLayout(activity)
        val detachedHost = View(activity)
        cell.addView(detachedHost)
        assertFalse(detachedHost.isAttachedToWindow)
        assertSame("precondition: a detached host reports its subtree top as rootView", cell, detachedHost.rootView)

        val detachedOverlay = View(activity)
        val detachedAttachment = DeferredOverlayAttachment(detachedHost, detachedOverlay) { detachedCount++ }
        detachedAttachment.attach()
        idleMainLooper()

        assertNull("overlay must not be added while the host has no window", detachedOverlay.parent)
        assertEquals("overlay must never be added into the cell", 1, cell.childCount)

        // The cell is laid out into the list -> attached to the window.
        content.addView(cell)
        assertTrue(detachedHost.isAttachedToWindow)
        assertNull("attach on window-attach must still be deferred", detachedOverlay.parent)

        idleMainLooper()
        assertSame(decor, detachedOverlay.parent)
        assertEquals("overlay must never be added into the cell", 1, cell.childCount)
    }

    @Test
    fun detachedHost_disposeBeforeAttachCancelsWaitAndCleansUp() {
        val cell = FrameLayout(activity)
        val detachedHost = View(activity)
        cell.addView(detachedHost)
        val detachedOverlay = View(activity)
        val detachedAttachment = DeferredOverlayAttachment(detachedHost, detachedOverlay) { detachedCount++ }
        detachedAttachment.attach()
        idleMainLooper()

        detachedAttachment.dispose()
        idleMainLooper()
        assertEquals(1, detachedCount)

        // Attaching the cell later must not resurrect the cancelled overlay.
        content.addView(cell)
        idleMainLooper()
        assertNull(detachedOverlay.parent)
        assertEquals(1, detachedCount)
    }

    /**
     * Documents WHY the deferral exists: with the real framework FrameLayout,
     * removing a child while the parent is laying out its children NPEs on a
     * stale index — the crash 91APP reported from DecorView. If this ever
     * stops throwing on a future framework, the deferral becomes optional.
     */
    @Test
    fun mechanismNote_removingChildDuringParentLayoutCrashesFrameLayout() {
        val context = activity
        val decor = FrameLayout(context)
        val victim = View(context)
        val mutator = object : View(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                decor.removeView(victim)
            }
        }
        decor.addView(mutator, FrameLayout.LayoutParams(100, 100))
        decor.addView(View(context), FrameLayout.LayoutParams(100, 100))
        decor.addView(victim, FrameLayout.LayoutParams(100, 100))
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        )

        val result = runCatching { decor.layout(0, 0, 1000, 1000) }

        assertTrue(
            "expected the framework NPE that motivates the deferral, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is NullPointerException,
        )
        assertFalse(result.isSuccess)
    }
}
