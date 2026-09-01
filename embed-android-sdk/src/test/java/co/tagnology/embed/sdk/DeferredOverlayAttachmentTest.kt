package co.tagnology.embed.sdk

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
 * Robolectric's paused main looper lets each test assert the state between
 * "scheduled" and "executed".
 */
@RunWith(RobolectricTestRunner::class)
class DeferredOverlayAttachmentTest {

    private val context = RuntimeEnvironment.getApplication()
    private val root = FrameLayout(context)
    private val overlay = View(context)
    private var detachedCount = 0
    private val attachment = DeferredOverlayAttachment(root, overlay) { detachedCount++ }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun attachDoesNotTouchRootSynchronously() {
        attachment.attach()

        assertNull("overlay must not attach during the current traversal", overlay.parent)
        assertEquals(0, root.childCount)

        idleMainLooper()
        assertSame(root, overlay.parent)
    }

    @Test
    fun disposeDoesNotTouchRootSynchronously() {
        attachment.attach()
        idleMainLooper()
        assertSame(root, overlay.parent)

        attachment.dispose()

        assertSame("overlay must not detach during the current traversal", root, overlay.parent)
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
        attachment.attach()
        attachment.dispose()

        idleMainLooper()

        assertNull("cancelled attach must never add the overlay", overlay.parent)
        assertEquals(0, root.childCount)
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
     * Documents WHY the deferral exists: with the real framework FrameLayout,
     * removing a child while the parent is laying out its children NPEs on a
     * stale index — the crash 91APP reported from DecorView. If this ever
     * stops throwing on a future framework, the deferral becomes optional.
     */
    @Test
    fun mechanismNote_removingChildDuringParentLayoutCrashesFrameLayout() {
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
