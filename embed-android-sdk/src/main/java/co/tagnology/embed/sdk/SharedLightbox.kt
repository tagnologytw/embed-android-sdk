package co.tagnology.embed.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** Receives what happens in a [SharedLightbox] while this widget owns it. */
internal interface LightboxClient {
    fun onLightboxEvent(event: EmbedWidgetEvent)
    fun onLightboxDismissed()
}

/** Content insets (px) that keep the lightbox page clear of the system bars. */
internal data class LightboxContentInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Margins for the lightbox WebView inside the full-window overlay.
 *
 * [barsLeft]..[barsBottom] are the window's raw system-bar/cutout insets and
 * [overlayLeft]..[overlayBottom] how far the overlay's edges sit from the
 * window edges. The WebView must not reach under the status bar (header would
 * be hidden) nor under the navigation bar (91APP: the post caption and product
 * cards at the bottom of a story sat under the 3-button / gesture bar). Insets
 * the overlay already avoids by its own position are not applied twice.
 * When the window reports no insets at all, [fallbackTop] (the status bar
 * height resource) keeps the old top-only behaviour.
 */
internal fun resolveLightboxContentInsets(
    barsLeft: Int,
    barsTop: Int,
    barsRight: Int,
    barsBottom: Int,
    overlayLeft: Int,
    overlayTop: Int,
    overlayRight: Int,
    overlayBottom: Int,
    hasWindowInsets: Boolean,
    fallbackTop: Int,
): LightboxContentInsets {
    if (!hasWindowInsets) return LightboxContentInsets(0, fallbackTop.coerceAtLeast(0), 0, 0)
    return LightboxContentInsets(
        left = (barsLeft - overlayLeft).coerceAtLeast(0),
        top = (barsTop - overlayTop).coerceAtLeast(0),
        right = (barsRight - overlayRight).coerceAtLeast(0),
        bottom = (barsBottom - overlayBottom).coerceAtLeast(0),
    )
}

/**
 * Whether a `toggleLB open:false` came from the lightbox page closing itself.
 * The SDK's injected close helpers (close guard, native close zone) post the
 * same event with a `reason`, but the page has not run its own close then.
 */
internal fun isCleanPageClose(payloadJson: String): Boolean {
    return runCatching { !JSONObject(payloadJson).has("reason") }.getOrDefault(false)
}

/**
 * One lightbox (overlay + WebView on embed.tagnology.co/lightBox) per page and
 * window, shared by every [EmbedWidgetView] slot of that page.
 *
 * The page is loaded as soon as the first slot with widgets appears, so a tap
 * only has to post the click message (same as the web embed's hidden
 * lightbox iframe and the iOS SDK). Previously each slot — including the six
 * FIXED_* slots and slots with no widgets — loaded its own lightbox page, so
 * on 91APP's category page up to seven copies loaded at once and the one that
 * was tapped was often still loading (black, then a white loading page).
 *
 * While the page has not yet shown the tapped content a native spinner is
 * drawn over the black overlay, so the wait never reads as a black screen.
 */
internal class SharedLightbox private constructor(
    private val key: Key,
    context: Context,
) {
    internal data class Key(val window: Any, val canonicalPage: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lightboxUrl = "https://embed.tagnology.co/lightBox?page=${key.canonicalPage}"
    private val statusBarFallbackPx = getStatusBarHeightPx(context)

    private var refCount = 0
    private var activeClient: LightboxClient? = null
    private var attachment: DeferredOverlayAttachment? = null
    private var destroyed = false

    var isVisible = false
        private set
    private var isContentLoaded = false
    private var isLightboxReady = false
    private var pendingMessageJson: String? = null
    private var dispatchAttemptCount = 0
    private val maxDispatchAttempts = 2

    private val hideSpinnerRunnable = Runnable { spinner.visibility = View.GONE }

    private val overlay = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(android.graphics.Color.BLACK)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        visibility = View.GONE
    }

    private val webView: WebView = createWebView(context)

    init {
        overlay.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = statusBarFallbackPx
            }
        )
        val spinnerSizePx = (40 * context.resources.displayMetrics.density).toInt()
        overlay.addView(spinner, FrameLayout.LayoutParams(spinnerSizePx, spinnerSizePx, Gravity.CENTER))

        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            updateContentInsets()
            insets
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentInsets() }
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ViewCompat.requestApplyInsets(v)
                updateContentInsets()
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /**
     * Reads the window's raw insets (a DecorView child may get them already
     * consumed by the DecorView in non edge-to-edge windows) and turns them
     * into WebView margins.
     */
    private fun updateContentInsets() {
        val rootInsets = ViewCompat.getRootWindowInsets(overlay)
        val bars = rootInsets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val root = overlay.rootView
        val location = IntArray(2)
        overlay.getLocationInWindow(location)
        val resolved = resolveLightboxContentInsets(
            barsLeft = bars?.left ?: 0,
            barsTop = bars?.top ?: 0,
            barsRight = bars?.right ?: 0,
            barsBottom = bars?.bottom ?: 0,
            overlayLeft = location[0],
            overlayTop = location[1],
            overlayRight = (root.width - location[0] - overlay.width).coerceAtLeast(0),
            overlayBottom = (root.height - location[1] - overlay.height).coerceAtLeast(0),
            hasWindowInsets = bars != null && overlay.isAttachedToWindow,
            fallbackTop = statusBarFallbackPx,
        )
        val params = webView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.leftMargin == resolved.left && params.topMargin == resolved.top &&
            params.rightMargin == resolved.right && params.bottomMargin == resolved.bottom
        ) return
        params.setMargins(resolved.left, resolved.top, resolved.right, resolved.bottom)
        // Called from a layout-change callback: apply on the next frame.
        overlay.post { webView.layoutParams = params }
        Log.d(TAG, "lightbox apply insets=$resolved")
    }

    private fun attachVia(host: View) {
        if (attachment != null) return
        attachment = DeferredOverlayAttachment(
            host = host,
            overlay = overlay,
            onDetached = {
                // Runs in the same posted message as removeView, so the
                // WebView is off the tree before it is destroyed.
                webView.removeJavascriptInterface("tagnologyEvent")
                webView.stopLoading()
                webView.destroy()
            },
        ).also { it.attach() }
    }

    fun isOpenFor(client: LightboxClient): Boolean = isVisible && activeClient === client

    fun open(client: LightboxClient, messageJson: String?, host: View) {
        if (overlay.parent == null && host.isAttachedToWindow) {
            // The slot that attached first may have left the window before its
            // deferred attach ran; attach through the slot being tapped. Safe
            // here: open runs from a posted bridge message, not a layout pass.
            (host.rootView as? ViewGroup)?.addView(overlay)
            Log.d(TAG, "lightbox overlay attached on open")
        }
        activeClient = client
        pendingMessageJson = messageJson
        dispatchAttemptCount = 0
        isVisible = true
        overlay.visibility = View.VISIBLE
        overlay.requestFocus()
        ViewCompat.requestApplyInsets(overlay)
        showSpinner()
        dispatchPendingMessageIfNeeded("show")
    }

    /**
     * Hides the lightbox and tells its current owner, unless already hidden.
     * Only a close the page did itself ([pageClosedItself]) leaves the page in
     * a clean state; after any other close (back, the native close zone) the
     * page still has the item open — a FloatingMedia reel even takes priority
     * over the next story or post that is clicked — and a video may keep
     * playing, so the page is reloaded (same as the iOS SDK).
     */
    fun dismiss(reason: String, pageClosedItself: Boolean = false) {
        if (!isVisible) return
        val client = activeClient
        hide(resetPage = !pageClosedItself)
        Log.d(TAG, "lightbox close reason=$reason")
        client?.onLightboxDismissed()
    }

    /** Hides the lightbox if [client] owns it, without calling it back. */
    fun closeFor(client: LightboxClient) {
        if (activeClient === client) hide(resetPage = true)
    }

    private fun hide(resetPage: Boolean) {
        isVisible = false
        activeClient = null
        pendingMessageJson = null
        overlay.visibility = View.GONE
        mainHandler.removeCallbacks(hideSpinnerRunnable)
        spinner.visibility = View.GONE
        if (resetPage && !destroyed) {
            Log.d(TAG, "lightbox reload to reset page state")
            isContentLoaded = false
            isLightboxReady = false
            webView.loadUrl(lightboxUrl)
        }
    }

    private fun showSpinner() {
        mainHandler.removeCallbacks(hideSpinnerRunnable)
        spinner.visibility = View.VISIBLE
        // Safety net for a page that never reports toggleLB.
        mainHandler.postDelayed(hideSpinnerRunnable, SPINNER_SAFETY_MS)
    }

    private fun onContentShown() {
        mainHandler.removeCallbacks(hideSpinnerRunnable)
        // toggleLB is posted as the page opens the item; give it a frame to paint.
        mainHandler.postDelayed(hideSpinnerRunnable, SPINNER_HIDE_DELAY_MS)
    }

    private fun dispatchPendingMessageIfNeeded(reason: String) {
        if (destroyed || !isVisible) return
        if (!isContentLoaded) return
        if (!isLightboxReady && reason != "fallback") return
        val messageToSend = pendingMessageJson
        if (messageToSend.isNullOrBlank()) return
        if (dispatchAttemptCount >= maxDispatchAttempts) return
        dispatchAttemptCount += 1
        Log.d(
            TAG,
            "lightbox dispatch attempt=$dispatchAttemptCount reason=$reason payload length=${messageToSend.length} summary=${summarizeLightboxClickPayload(messageToSend)}"
        )
        Log.d(TAG, "lightbox dispatch payload raw=$messageToSend")
        Log.d(TAG, "lightbox dispatch payload keys=${summarizePayloadKeys(messageToSend)}")
        pendingMessageJson = null
        webView.evaluateJavascript(buildDispatchMessageScript(messageToSend)) {
            Log.d(TAG, "lightbox dispatch click message done attempt=$dispatchAttemptCount reason=$reason")
        }
    }

    private fun handlePageEvent(payloadJson: String) {
        val type = parseEventType(payloadJson)
        if (type.equals("readyLB", ignoreCase = true)) {
            isLightboxReady = true
            Log.d(TAG, "lightbox event type=$type payload=$payloadJson")
            dispatchPendingMessageIfNeeded("readyLB")
            return
        }
        Log.d(TAG, "lightbox event type=$type payload=$payloadJson")
        val client = activeClient
        if (type.equals("toggleLB", ignoreCase = true)) {
            val open = runCatching {
                val json = JSONObject(payloadJson)
                when {
                    json.has("open") -> json.optBoolean("open", false)
                    json.optJSONObject("data")?.has("open") == true -> json.optJSONObject("data")?.optBoolean("open", false) ?: false
                    else -> false
                }
            }.getOrDefault(false)
            if (open) {
                pendingMessageJson = null
                onContentShown()
            } else {
                dismiss("page_toggle", pageClosedItself = isCleanPageClose(payloadJson))
            }
        }
        client?.onLightboxEvent(EmbedWidgetEvent(type = type, payloadJson = payloadJson))
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(context: Context): WebView {
        val eventBridge = object {
            @JavascriptInterface
            fun postEvent(payloadJson: String) {
                mainHandler.post { if (!destroyed) handlePageEvent(payloadJson) }
            }

            @JavascriptInterface
            fun postMessage(payloadJson: String) {
                postEvent(payloadJson)
            }
        }

        return WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    val level = consoleMessage.messageLevel().name
                    Log.d(
                        TAG,
                        "lightbox console level=$level line=${consoleMessage.lineNumber()} source=${consoleMessage.sourceId()} message=${consoleMessage.message()}"
                    )
                    return true
                }
            }
            addJavascriptInterface(eventBridge, "tagnologyEvent")
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val widthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val heightPx = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                    val closeZoneLeft = widthPx * 0.82f
                    val closeZoneTop = maxOf(120f, heightPx * 0.16f)
                    val inCloseZone = event.x >= closeZoneLeft && event.y <= closeZoneTop
                    if (inCloseZone) {
                        Log.d(TAG, "lightbox close-zone touch x=${event.x} y=${event.y} w=$widthPx h=$heightPx")
                        evaluateJavascript(
                            """
                                (function() {
                                  try {
                                    if (window.__tagnologyNotifyEvent) {
                                      window.__tagnologyNotifyEvent({ eventType: 'toggleLB', open: false, reason: 'native_touch_close_zone' });
                                    } else if (window.tagnologyEvent && window.tagnologyEvent.postEvent) {
                                      window.tagnologyEvent.postEvent(JSON.stringify({ eventType: 'toggleLB', open: false, reason: 'native_touch_close_zone' }));
                                    }
                                  } catch (e) {}
                                })();
                            """.trimIndent(),
                            null
                        )
                        dismiss("user_dismiss")
                        return@setOnTouchListener true
                    }
                }
                false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val target = request?.url?.toString().orEmpty()
                    if (target.contains("/undefined")) {
                        Log.e(
                            TAG,
                            "lightbox request undefined url=$target mainFrame=${request?.isForMainFrame} method=${request?.method} hasGesture=${request?.hasGesture()}"
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    val mainFrame = request?.isForMainFrame == true
                    if (mainFrame && isUndefinedLightboxUrl(target)) {
                        Log.e(TAG, "lightbox navigate undefined url=$target")
                        // Prevent blank screen caused by main-frame navigation to /undefined.
                        return true
                    }
                    if (mainFrame && shouldOpenOutsideLightbox(target, request?.hasGesture() == true)) {
                        val launched = openExternalUrl(context, target)
                        Log.d(TAG, "lightbox external_link url=$target launched=$launched")
                        return launched
                    }
                    if (target.contains("/undefined")) {
                        Log.e(TAG, "lightbox navigate undefined url=$target")
                    } else {
                        Log.d(TAG, "lightbox navigate url=$target")
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isContentLoaded = false
                    isLightboxReady = false
                    Log.d(TAG, "lightbox started url=$url")
                    evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                    evaluateJavascript(INJECT_HIDE_MEDIA_PLACEHOLDER_JS, null)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        evaluateJavascript(INJECT_LIGHTBOX_DEBUG_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isContentLoaded = true
                    Log.d(TAG, "lightbox finished url=$url")
                    evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                    evaluateJavascript(INJECT_HIDE_MEDIA_PLACEHOLDER_JS, null)
                    evaluateJavascript(INJECT_LIGHTBOX_CLOSE_GUARD_JS, null)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        evaluateJavascript(INJECT_LIGHTBOX_OBSERVABILITY_JS, null)
                        evaluateJavascript(INJECT_LIGHTBOX_DEBUG_JS, null)
                    }
                    postDelayed({ dispatchPendingMessageIfNeeded("fallback") }, 1000)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        postDelayed({
                            evaluateJavascript(REPORT_LIGHTBOX_STATE_JS) { result ->
                                Log.d(TAG, "lightbox state snapshot=$result")
                            }
                        }, 1200)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "lightbox error url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    Log.e(TAG, "lightbox httpError url=${request?.url} status=${errorResponse?.statusCode}")
                }
            }
            Log.d(TAG, "lightbox preload url=$lightboxUrl")
            loadUrl(lightboxUrl)
        }
    }

    companion object {
        private const val TAG = "EmbedSDK"
        private const val SPINNER_HIDE_DELAY_MS = 150L
        private const val SPINNER_SAFETY_MS = 15_000L

        private val shared = HashMap<Key, SharedLightbox>()

        /**
         * Returns the page's lightbox for [host]'s window, creating (and
         * preloading) it on first use. Every call must be paired with [release].
         */
        fun acquire(context: Context, canonicalPage: String, host: View): SharedLightbox {
            val key = Key(window = context.findActivity() ?: context, canonicalPage = canonicalPage)
            val lightbox = shared.getOrPut(key) { SharedLightbox(key, context) }
            lightbox.refCount += 1
            lightbox.attachVia(host)
            Log.d(TAG, "lightbox acquire page=$canonicalPage refCount=${lightbox.refCount}")
            return lightbox
        }

        /**
         * Drops [client]'s use; closes the lightbox if [client] still has it
         * open, and tears it down once no slot of the page is left.
         */
        fun release(lightbox: SharedLightbox, client: LightboxClient) {
            if (lightbox.activeClient === client) lightbox.hide(resetPage = lightbox.refCount > 1)
            lightbox.refCount -= 1
            Log.d(TAG, "lightbox release page=${lightbox.key.canonicalPage} refCount=${lightbox.refCount}")
            if (lightbox.refCount > 0) return
            shared.remove(lightbox.key)
            lightbox.destroyed = true
            lightbox.mainHandler.removeCallbacksAndMessages(null)
            val attachment = lightbox.attachment
            if (attachment != null) {
                attachment.dispose()
            } else {
                lightbox.webView.destroy()
            }
        }

        internal fun sharedCountForTest(): Int = shared.size
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
