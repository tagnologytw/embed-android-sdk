package co.tagnology.embed.sdk

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Attaches/detaches a full-screen overlay to the window's root ViewGroup (the
 * DecorView) that hosts [host], via posted messages instead of synchronously.
 *
 * Two hazards are handled here:
 *
 * 1. Composition and disposal of a Compose effect can both run inside a layout
 *    traversal (e.g. a RecyclerView recycling a ComposeView item during
 *    dispatchLayout). Mutating the DecorView's children while
 *    FrameLayout.layoutChildren is iterating them NPEs on a stale child index,
 *    so both mutations are deferred out of the traversal.
 *
 * 2. The root is resolved lazily, at attach time, and only once [host] really
 *    sits in a window. `View.getRootView()` just walks the parent chain, so on
 *    a detached host (e.g. a pooled RecyclerView cell whose composition is kept
 *    alive and recomposed while off-screen) it returns the top of the detached
 *    subtree — the cell itself — instead of the DecorView. Attaching there
 *    rendered the lightbox as a small WebView squeezed into the list item that
 *    scrolled with the list (reported by 91APP). While attached,
 *    getRootView() returns AttachInfo.mRootView (the real window root) even
 *    if RecyclerView has temporarily scrapped the cell, so the only unsafe
 *    state is "not attached": attachment then waits for
 *    onViewAttachedToWindow.
 *
 * [dispose] cancels a not-yet-run attach so no overlay is ever leaked, and
 * runs [onDetached] in the same posted message as removeView so cleanup (e.g.
 * WebView.destroy) happens only after the overlay has left the tree.
 */
internal class DeferredOverlayAttachment(
    private val host: View,
    private val overlay: View,
    private val onDetached: () -> Unit,
) {
    private companion object {
        const val TAG = "EmbedSDK"
    }

    private var disposed = false
    private var attachStateListener: View.OnAttachStateChangeListener? = null

    // A plain main-looper Handler rather than View.post: View.post on a view
    // that is not attached to a window queues the runnable until attach, which
    // would silently defer forever for a detached root.
    private val handler = Handler(Looper.getMainLooper())

    private val attachRunnable = Runnable { tryAttach() }

    private fun tryAttach() {
        if (disposed) return
        if (overlay.parent != null) return
        if (!host.isAttachedToWindow) {
            Log.d(TAG, "lightbox overlay host not attached, waiting for window")
            waitForHostAttach()
            return
        }
        val root = host.rootView as? ViewGroup
        if (root == null) {
            Log.e(TAG, "lightbox overlay skipped: window root is not a ViewGroup")
            return
        }
        root.addView(overlay)
        Log.d(TAG, "lightbox overlay attached root=${root.javaClass.simpleName}")
    }

    private fun waitForHostAttach() {
        if (attachStateListener != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                host.removeOnAttachStateChangeListener(this)
                attachStateListener = null
                if (disposed) return
                // onViewAttachedToWindow fires from inside the parent's
                // addView (often mid-layout), so keep the deferral.
                handler.post(attachRunnable)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        attachStateListener = listener
        host.addOnAttachStateChangeListener(listener)
    }

    fun attach() {
        handler.post(attachRunnable)
    }

    fun dispose() {
        if (disposed) return
        Log.d(TAG, "lightbox overlay detach scheduled")
        disposed = true
        handler.removeCallbacks(attachRunnable)
        attachStateListener?.let { host.removeOnAttachStateChangeListener(it) }
        attachStateListener = null
        handler.post {
            // No-op if attachRunnable never ran (or never found a window).
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            onDetached()
            Log.d(TAG, "lightbox overlay detached")
        }
    }
}
