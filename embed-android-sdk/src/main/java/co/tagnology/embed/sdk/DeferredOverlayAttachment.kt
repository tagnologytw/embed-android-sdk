package co.tagnology.embed.sdk

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Attaches/detaches an overlay to a root ViewGroup (typically the DecorView)
 * via posted messages instead of synchronously.
 *
 * Composition and disposal of a Compose effect can both run inside a layout
 * traversal (e.g. a RecyclerView recycling a ComposeView item during
 * dispatchLayout). Mutating the DecorView's children while
 * FrameLayout.layoutChildren is iterating them NPEs on a stale child index,
 * so both mutations must be deferred out of the traversal.
 *
 * [dispose] cancels a not-yet-run attach so no overlay is ever leaked, and
 * runs [onDetached] in the same posted message as removeView so cleanup (e.g.
 * WebView.destroy) happens only after the overlay has left the tree.
 */
internal class DeferredOverlayAttachment(
    private val root: ViewGroup,
    private val overlay: View,
    private val onDetached: () -> Unit,
) {
    private companion object {
        const val TAG = "EmbedSDK"
    }

    private var disposed = false

    // A plain main-looper Handler rather than View.post: View.post on a view
    // that is not attached to a window queues the runnable until attach, which
    // would silently defer forever for a detached root.
    private val handler = Handler(Looper.getMainLooper())

    private val attachRunnable = Runnable {
        if (disposed) return@Runnable
        root.addView(overlay)
        Log.d(TAG, "lightbox overlay attached")
    }

    fun attach() {
        handler.post(attachRunnable)
    }

    fun dispose() {
        if (disposed) return
        Log.d(TAG, "lightbox overlay detach scheduled")
        disposed = true
        handler.removeCallbacks(attachRunnable)
        handler.post {
            // removeView is a no-op if attachRunnable never ran.
            root.removeView(overlay)
            onDetached()
            Log.d(TAG, "lightbox overlay detached")
        }
    }
}
