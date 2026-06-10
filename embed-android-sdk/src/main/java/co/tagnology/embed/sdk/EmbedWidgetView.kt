package co.tagnology.embed.sdk

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

@Composable
@SuppressLint("SetJavaScriptEnabled")
fun EmbedWidgetView(
    pageUrl: String,
    position: EmbedPosition,
    modifier: Modifier = Modifier,
    onError: ((EmbedWidgetLoadError) -> Unit)? = null,
    onClick: ((EmbedWidgetClick) -> Unit)? = null,
    onEvent: ((EmbedWidgetEvent) -> Unit)? = null,
    enableLightbox: Boolean = true,
) {
    val tag = "EmbedSDK"
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val canonicalPage = remember(pageUrl) { canonicalizePageUrl(pageUrl) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    var widgets by remember(pageUrl, position) { mutableStateOf<List<EmbedWidgetItem>>(emptyList()) }
    var showLightbox by remember { mutableStateOf(false) }
    var lightboxMessageJson by remember { mutableStateOf<String?>(null) }
    var lastEventJson by remember { mutableStateOf<String?>(null) }
    var lastEventAtMs by remember { mutableStateOf(0L) }
    val widgetsById = remember(widgets) { widgets.associateBy { it.folderId } }

    fun logLightbox(message: String) {
        Log.d(tag, "lightbox $message")
    }

    fun handleEventFromIframe(eventJson: String, sourceWidgetId: String?) {
        val now = System.currentTimeMillis()
        if (lastEventJson == eventJson && (now - lastEventAtMs) < 300) {
            logLightbox("ignore duplicate event sourceWidgetId=$sourceWidgetId")
            return
        }
        lastEventJson = eventJson
        lastEventAtMs = now

        val eventType = parseEventType(eventJson)
        onEvent?.invoke(EmbedWidgetEvent(type = eventType, payloadJson = eventJson))
        logLightbox("receive eventType=$eventType sourceWidgetId=$sourceWidgetId")
        if (eventType.equals("click", ignoreCase = true)) {
            parseClickEvent(
                payloadJson = eventJson,
                sourceWidgetId = sourceWidgetId,
                fallbackPosition = position,
                fallbackPageUrl = pageUrl,
                widgetsById = widgetsById,
            )?.let { click -> onClick?.invoke(click) }
        }

        if (!enableLightbox || DEBUG_DISABLE_LIGHTBOX) return
        if (shouldOpenLightbox(eventJson)) {
            if (!showLightbox) {
                lightboxMessageJson = normalizeLightboxMessage(eventJson, sourceWidgetId)
                val summary = summarizeLightboxClickPayload(lightboxMessageJson)
                showLightbox = true
                logLightbox("open reason=click_event eventType=$eventType sourceWidgetId=$sourceWidgetId summary=$summary")
            }
        }
        if (shouldCloseLightbox(eventJson)) {
            if (showLightbox) {
                showLightbox = false
                lightboxMessageJson = null
                logLightbox("close reason=event eventType=$eventType")
            } else {
                logLightbox("ignore close reason=already_hidden eventType=$eventType")
            }
        }
    }

    LaunchedEffect(pageUrl, position) {
        logLightbox("init hidden=true pageUrl=$pageUrl slot=$position")
        val result = EmbedAndroidSDK.getWidgets(pageUrl = pageUrl, position = position)
        if (result.isSuccess) {
            widgets = result.getOrDefault(emptyList())
            Log.d(tag, "render slot=$position widgets=${widgets.size} pageUrl=$pageUrl")
        } else {
            val message = result.exceptionOrNull()?.message.orEmpty()
            val statusCode = when {
                message.contains("428") -> 428
                message.contains("204") -> 204
                else -> 520
            }
            Log.e(tag, "render fail slot=$position status=$statusCode message=$message")
            onError?.invoke(
                EmbedWidgetLoadError(
                    statusCode = statusCode,
                    message = message,
                    pageUrl = pageUrl,
                    position = position,
                )
            )
        }
    }
    LaunchedEffect(showLightbox) {
        logLightbox("state visible=$showLightbox")
    }

    Column(modifier = modifier.fillMaxWidth()) {
        widgets.forEach { widget ->
            val context = LocalContext.current
            key(widget.folderId) {
                var webViewHeightPx by remember(widget.folderId) { mutableStateOf(1) }
                var hasTrackedEmbedView by remember(widget.folderId, pageUrl) { mutableStateOf(false) }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { webViewHeightPx.toDp() })
                        .onGloballyPositioned { coordinates ->
                            if (hasTrackedEmbedView) return@onGloballyPositioned
                            val bounds = coordinates.boundsInWindow()
                            val width = bounds.width
                            val height = bounds.height
                            if (width <= 0f || height <= 0f) return@onGloballyPositioned

                            val visibleWidth = (min(bounds.right, screenWidthPx) - max(bounds.left, 0f)).coerceAtLeast(0f)
                            val visibleHeight = (min(bounds.bottom, screenHeightPx) - max(bounds.top, 0f)).coerceAtLeast(0f)
                            if (visibleWidth <= 0f || visibleHeight <= 0f) return@onGloballyPositioned

                            val visibleRatio = (visibleWidth * visibleHeight) / (width * height)
                            if (visibleRatio >= 0.3f) {
                                val tracked = EmbedAnalyticsTracker.markWidgetVisible(widget = widget)
                                if (tracked) hasTrackedEmbedView = true
                            }
                        },
                    factory = {
                        Log.d(tag, "webview create widget=${widget.folderId} slot=${widget.position}")
                        val resizeBridge = object {
                            @JavascriptInterface
                            fun postHeight(height: Float) {
                                mainHandler.post {
                                    // JS reports CSS px; convert to Android px.
                                    val pixelDensity = context.resources.displayMetrics.density
                                    val nextHeightPx = (height * pixelDensity).toInt().coerceAtLeast(2)
                                    if (nextHeightPx != webViewHeightPx) {
                                        webViewHeightPx = nextHeightPx
                                        Log.d(tag, "widget=${widget.folderId} slot=${widget.position} heightCss=$height heightPx=$webViewHeightPx density=$pixelDensity")
                                    }
                                }
                            }
                        }

                        val eventBridge = object {
                            @JavascriptInterface
                            fun postEvent(payloadJson: String) {
                                mainHandler.post { handleEventFromIframe(payloadJson, widget.folderId) }
                            }

                            @JavascriptInterface
                            fun postMessage(payloadJson: String) {
                                mainHandler.post { handleEventFromIframe(payloadJson, widget.folderId) }
                            }
                        }

                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                webViewHeightPx,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                settings.safeBrowsingEnabled = true
                            }
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            webChromeClient = WebChromeClient()

                            addJavascriptInterface(resizeBridge, "tagnologyResize")
                            addJavascriptInterface(eventBridge, "tagnologyEvent")
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    Log.d(tag, "webview started widget=${widget.folderId} slot=${widget.position} url=$url")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.d(tag, "webview finished widget=${widget.folderId} slot=${widget.position}")
                                    evaluateJavascript(INJECT_RESIZE_JS, null)
                                    postDelayed({ evaluateJavascript(REPORT_HEIGHT_JS, null) }, 50)
                                    postDelayed({ evaluateJavascript(REPORT_HEIGHT_JS, null) }, 300)
                                    postDelayed({ evaluateJavascript(REPORT_HEIGHT_JS, null) }, 800)
                                }
                            }

                            setOnClickListener {
                                onClick?.invoke(
                                    EmbedWidgetClick(
                                        folderId = widget.folderId,
                                        folderName = widget.folderName,
                                        position = widget.position,
                                        mediaId = widget.mediaId,
                                        url = widget.clickUrl,
                                    )
                                )
                            }
                            val iframeUrl = buildEmbedDisplayUrl(widget = widget, pageUrl = pageUrl)
                            val html = widget.html ?: buildEmbedIframeHtml(iframeUrl = iframeUrl)
                            Log.d(tag, "webview load widget=${widget.folderId} slot=${widget.position} source=${if (widget.html == null) "embed.tagnology iframe" else "custom html"}")
                            if (widget.html == null) {
                                Log.d(tag, "iframe url widget=${widget.folderId} slot=${widget.position} url=$iframeUrl")
                            }
                            loadDataWithBaseURL("https://embed.tagnology.co", html, "text/html", "utf-8", null)
                            postDelayed({ evaluateJavascript(REPORT_HEIGHT_JS, null) }, 1000)
                        }
                    },
                    update = { webView ->
                        val current = webView.layoutParams
                        if (current.height != webViewHeightPx) {
                            current.height = webViewHeightPx
                            webView.layoutParams = current
                        }
                    }
                )
            }
        }
    }

    if (!DEBUG_DISABLE_LIGHTBOX) {
        LightboxOverlayHost(
            visible = showLightbox,
            pendingMessageJson = lightboxMessageJson,
            canonicalPage = canonicalPage,
            onPendingMessageConsumed = {
                lightboxMessageJson = null
            },
            onEvent = { event ->
                onEvent?.invoke(event)
                if (shouldCloseLightbox(event.payloadJson)) {
                    showLightbox = false
                }
            },
            onDismiss = {
                showLightbox = false
                lightboxMessageJson = null
                logLightbox("close reason=user_dismiss")
            },
        )
    }
}

// Temporary switch for debugging startup white overlay issue.
private const val DEBUG_DISABLE_LIGHTBOX = false
private const val DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX = false

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun LightboxOverlayHost(
    visible: Boolean,
    pendingMessageJson: String?,
    canonicalPage: String,
    onPendingMessageConsumed: () -> Unit,
    onEvent: (EmbedWidgetEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val tag = "EmbedSDK"
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val lightboxUrl = remember(canonicalPage) {
        "https://embed.tagnology.co/lightBox?page=$canonicalPage"
    }

    var dispatchAttemptCount by remember(pendingMessageJson) { mutableStateOf(0) }
    var isContentLoaded by remember { mutableStateOf(false) }
    var isLightboxReady by remember { mutableStateOf(false) }
    var localPendingMessageJson by remember(pendingMessageJson) { mutableStateOf(pendingMessageJson) }
    var lightboxWebViewRef by remember { mutableStateOf<WebView?>(null) }
    var overlayContainerRef by remember { mutableStateOf<FrameLayout?>(null) }
    val maxDispatchAttempts = 2
    BackHandler(enabled = visible) {
        onDismiss()
    }

    fun dispatchPendingMessageIfNeeded(reason: String) {
        val webView = lightboxWebViewRef ?: return
        if (!isContentLoaded) return
        if (!isLightboxReady && reason != "fallback") return
        if (localPendingMessageJson.isNullOrBlank()) return
        if (dispatchAttemptCount >= maxDispatchAttempts) return
        dispatchAttemptCount += 1
        val messageToSend = localPendingMessageJson ?: return
        Log.d(
            tag,
            "lightbox dispatch attempt=$dispatchAttemptCount reason=$reason payload length=${messageToSend.length} summary=${summarizeLightboxClickPayload(messageToSend)}"
        )
        Log.d(tag, "lightbox dispatch payload raw=$messageToSend")
        Log.d(tag, "lightbox dispatch payload keys=${summarizePayloadKeys(messageToSend)}")
        val script = buildDispatchMessageScript(messageToSend)
        localPendingMessageJson = null
        webView.evaluateJavascript(script) {
            Log.d(tag, "lightbox dispatch click message done attempt=$dispatchAttemptCount reason=$reason")
            onPendingMessageConsumed()
        }
    }
    val hostView = LocalView.current
    val context = LocalContext.current
    DisposableEffect(canonicalPage) {
        val root = hostView.rootView as? ViewGroup
        if (root == null) {
            return@DisposableEffect onDispose {}
        }

        val overlay = FrameLayout(context).apply {
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

                val eventBridge = object {
                    @JavascriptInterface
                    fun postEvent(payloadJson: String) {
                        mainHandler.post {
                            val type = parseEventType(payloadJson)
                            if (type.equals("readyLB", ignoreCase = true)) {
                                isLightboxReady = true
                                dispatchPendingMessageIfNeeded("readyLB")
                                Log.d(tag, "lightbox event type=$type payload=$payloadJson")
                                return@post
                            }
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
                                    onPendingMessageConsumed()
                                } else {
                                    onDismiss()
                                }
                            }

                            Log.d(tag, "lightbox event type=$type payload=$payloadJson")
                            onEvent(EmbedWidgetEvent(type = type, payloadJson = payloadJson))
                        }
                    }

                    @JavascriptInterface
                    fun postMessage(payloadJson: String) {
                        postEvent(payloadJson)
                    }
                }

        val webView = WebView(context).apply {
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
                        tag,
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
                        Log.d(tag, "lightbox close-zone touch x=${event.x} y=${event.y} w=$widthPx h=$heightPx")
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
                        onDismiss()
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
                            tag,
                            "lightbox request undefined url=$target mainFrame=${request?.isForMainFrame} method=${request?.method} hasGesture=${request?.hasGesture()}"
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    val mainFrame = request?.isForMainFrame == true
                    if (mainFrame && isUndefinedLightboxUrl(target)) {
                        Log.e(tag, "lightbox navigate undefined url=$target")
                        // Prevent blank screen caused by main-frame navigation to /undefined.
                        return true
                    }
                    if (mainFrame && shouldOpenOutsideLightbox(target, request?.hasGesture() == true)) {
                        val launched = openExternalUrl(context, target)
                        Log.d(tag, "lightbox external_link url=$target launched=$launched")
                        return launched
                    }
                    if (target.contains("/undefined")) {
                        Log.e(tag, "lightbox navigate undefined url=$target")
                    } else {
                        Log.d(tag, "lightbox navigate url=$target")
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isContentLoaded = false
                    isLightboxReady = false
                    Log.d(tag, "lightbox started url=$url")
                    evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        evaluateJavascript(INJECT_LIGHTBOX_DEBUG_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isContentLoaded = true
                    Log.d(tag, "lightbox finished url=$url")
                    evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                    evaluateJavascript(INJECT_LIGHTBOX_CLOSE_GUARD_JS, null)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        evaluateJavascript(INJECT_LIGHTBOX_OBSERVABILITY_JS, null)
                    }
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        evaluateJavascript(INJECT_LIGHTBOX_DEBUG_JS, null)
                    }
                    postDelayed({ dispatchPendingMessageIfNeeded("fallback") }, 1000)
                    if (DEBUG_LIGHTBOX_FORCE_LAYOUT_FIX) {
                        postDelayed({
                            evaluateJavascript(REPORT_LIGHTBOX_STATE_JS) { result ->
                                Log.d(tag, "lightbox state snapshot=$result")
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
                    Log.e(tag, "lightbox error url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    Log.e(tag, "lightbox httpError url=${request?.url} status=${errorResponse?.statusCode}")
                }
            }
            Log.d(tag, "lightbox url=$lightboxUrl")
            loadUrl(lightboxUrl)
        }

        val statusBarOffsetPx = getStatusBarHeightPx(context)
        overlay.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = statusBarOffsetPx
            }
        )
        Log.d(tag, "lightbox apply statusBarOffsetPx=$statusBarOffsetPx")
        root.addView(overlay)
        lightboxWebViewRef = webView
        overlayContainerRef = overlay
        Log.d(tag, "lightbox overlay attached")

        onDispose {
            Log.d(tag, "lightbox overlay detached")
            overlayContainerRef = null
            lightboxWebViewRef = null
            root.removeView(overlay)
            webView.removeJavascriptInterface("tagnologyEvent")
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(visible) {
        overlayContainerRef?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            overlayContainerRef?.requestFocus()
            dispatchPendingMessageIfNeeded("show")
        }
    }
}

private fun buildEmbedDisplayUrl(widget: EmbedWidgetItem, pageUrl: String): String {
    val canonicalPage = canonicalizePageUrl(pageUrl)
    return buildString {
        append("https://embed.tagnology.co/display?folderId=${widget.folderId}&page=$canonicalPage")
        if (widget.layout.equals("floatingmedia", ignoreCase = true)) {
            append("&fullScreen=true")
        }
    }
}

private fun getStatusBarHeightPx(context: android.content.Context): Int {
    val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    if (resId <= 0) return 0
    return context.resources.getDimensionPixelSize(resId).coerceAtLeast(0)
}

private fun buildEmbedIframeHtml(iframeUrl: String): String {
    val isFloatingMedia = iframeUrl.contains("fullScreen=true")
    val containerHtml = if (isFloatingMedia) {
        """
        <div id="embed-container">
          <iframe id="embed-frame" src="$iframeUrl" scrolling="no" frameborder="0" allow="fullscreen; autoplay; picture-in-picture" playsinline></iframe>
        </div>
        """.trimIndent()
    } else {
        """<iframe id="embed-frame" src="$iframeUrl" scrolling="no" frameborder="0" allow="fullscreen; autoplay; picture-in-picture" playsinline></iframe>"""
    }

    val floatingHtmlBodyCss = if (isFloatingMedia) {
        """
        html, body {
          max-height: 224px !important;
          display: flex !important;
          align-items: flex-start !important;
          justify-content: flex-start !important;
        }
        """.trimIndent()
    } else ""

    val containerCss = if (isFloatingMedia) {
        """
        #embed-container {
          width: 100%;
          height: 100%;
          max-width: 126px !important;
          max-height: 224px !important;
          position: relative;
          overflow: hidden !important;
          background: transparent;
          top: 0 !important;
          left: 0 !important;
          margin: 0 !important;
          padding: 0 !important;
          visibility: visible !important;
          opacity: 1 !important;
          z-index: 1 !important;
          display: block !important;
        }
        """.trimIndent()
    } else ""

    val iframeCss = if (isFloatingMedia) {
        """
        #embed-frame {
          border: none !important;
          width: 126px !important;
          height: 224px !important;
          max-width: 126px !important;
          max-height: 224px !important;
          position: relative !important;
          display: block !important;
          overflow: hidden !important;
          background: transparent;
          box-sizing: border-box !important;
          visibility: visible !important;
          opacity: 1 !important;
          z-index: 1 !important;
        }
        """.trimIndent()
    } else {
        """
        #embed-frame {
          border: 0;
          width: 100%;
          min-height: 1px;
          height: 1px;
          position: relative;
          display: block !important;
          overflow: hidden;
          background: transparent;
        }
        """.trimIndent()
    }

    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              background: transparent;
              overflow: hidden;
            }
            $floatingHtmlBodyCss
            $containerCss
            $iframeCss
          </style>
        </head>
        <body>
          $containerHtml
          <script>
            $INJECT_EVENT_BRIDGE_JS
            (function () {
              var frame = document.getElementById('embed-frame');
              if (!frame) return;
              var isFloatingMedia = ${if (isFloatingMedia) "true" else "false"};
              var hasResizeFromEmbed = false;
              var lastNativeHeight = 0;

              function applyHeight(next) {
                var h = Number(next);
                if (!h || Number.isNaN(h)) return null;
                h = Math.max(1, Math.round(h));
                frame.style.setProperty('height', h + 'px', 'important');
                return h;
              }

              function notifyNative(height) {
                var h = Number(height);
                if (!h || Number.isNaN(h)) return;
                h = Math.max(1, Math.round(h));
                if (Math.abs(h - lastNativeHeight) <= 1) return;
                lastNativeHeight = h;
                if (window.tagnologyResize && window.tagnologyResize.postHeight) {
                  window.tagnologyResize.postHeight(h);
                }
              }

              function normalizeHeightValue(value) {
                if (typeof value === 'number' && Number.isFinite(value)) return value;
                if (typeof value === 'string') {
                  var sanitized = value.replace(/[^0-9.\-]/g, '');
                  var n = parseFloat(sanitized);
                  return Number.isNaN(n) ? null : n;
                }
                return null;
              }

              function extractHeightFromPayload(data) {
                if (!data) return null;
                var property = data.property || {};
                var candidates = [
                  data.height,
                  data.size && data.size.height,
                  data.data && data.data.height,
                  property.height,
                  property.minHeight,
                  property.maxHeight,
                  property['--height'],
                  property['--tagnology-height']
                ];
                for (var i = 0; i < candidates.length; i += 1) {
                  var v = normalizeHeightValue(candidates[i]);
                  if (v) return v;
                }
                return null;
              }

              function getRawHeightFromProperty(property) {
                if (!property || typeof property !== 'object') return null;
                var candidates = [
                  property.height,
                  property.minHeight,
                  property.maxHeight,
                  property['--height'],
                  property['--tagnology-height']
                ];
                for (var i = 0; i < candidates.length; i += 1) {
                  if (candidates[i] !== undefined && candidates[i] !== null) return String(candidates[i]);
                }
                return null;
              }

              function shouldDeferHeightSync(rawHeight, property) {
                if (property && String(property.position || '').toLowerCase() === 'fixed') return true;
                if (!rawHeight) return false;
                return /[a-z%]/i.test(rawHeight);
              }

              function applyPropertyStyles(property) {
                if (!property || typeof property !== 'object') return;
                for (var key in property) {
                  if (!Object.prototype.hasOwnProperty.call(property, key)) continue;
                  var value = property[key];
                  if (value === undefined || value === null) continue;
                  if (isFloatingMedia && String(key).toLowerCase() === 'position') continue;
                  frame.style.setProperty(String(key), String(value), 'important');
                }
                if (isFloatingMedia) {
                  frame.style.setProperty('position', 'relative', 'important');
                }
              }

              function notifyNativeEvent(payload) {
                if (window.__tagnologyNotifyEvent) {
                  window.__tagnologyNotifyEvent(payload || {});
                }
              }

              window.addEventListener('message', function (event) {
                var origin = event && event.origin ? String(event.origin) : '';
                if (origin && origin.indexOf('tagnology.co') === -1) return;
                var data = event.data || {};
                notifyNativeEvent(data);
                if (data.eventType === 'resize') {
                  hasResizeFromEmbed = true;
                  var property = data.property || null;
                  applyPropertyStyles(property);

                  var rawHeight = getRawHeightFromProperty(property);
                  var defer = shouldDeferHeightSync(rawHeight, property);
                  var reportedHeight = frame.getBoundingClientRect().height || 1;
                  if (defer) {
                    notifyNative(reportedHeight);
                    return;
                  }

                  var nextHeight = extractHeightFromPayload(data) || reportedHeight;
                  var applied = applyHeight(nextHeight);
                  if (applied) notifyNative(applied);
                }
              }, false);

              setTimeout(function () { notifyNative(frame.getBoundingClientRect().height || 1); }, 50);
              setTimeout(function () { notifyNative(frame.getBoundingClientRect().height || 1); }, 300);
              setTimeout(function () { notifyNative(frame.getBoundingClientRect().height || 1); }, 800);
              setTimeout(function () {
                if (!hasResizeFromEmbed) {
                  notifyNative(Math.max(frame.getBoundingClientRect().height || 1, 180));
                }
              }, 1500);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun parseEventType(payloadJson: String): String {
    return runCatching {
        extractEventType(JSONObject(payloadJson))
    }.getOrElse {
        when {
            payloadJson.contains("toggleLB", ignoreCase = true) -> "toggleLB"
            payloadJson.contains("readyLB", ignoreCase = true) -> "readyLB"
            payloadJson.contains("close", ignoreCase = true) -> "close"
            else -> "unknown"
        }
    }
}

private fun parseClickEvent(
    payloadJson: String,
    sourceWidgetId: String?,
    fallbackPosition: EmbedPosition,
    fallbackPageUrl: String,
    widgetsById: Map<String, EmbedWidgetItem>,
): EmbedWidgetClick? {
    return runCatching {
        val root = JSONObject(payloadJson)
        if (!extractEventType(root).equals("click", ignoreCase = true)) return@runCatching null
        val item = root.optJSONObject("item") ?: root.optJSONObject("data") ?: return@runCatching null

        val folderId = extractString(item, "folderId")
            ?: sourceWidgetId
            ?: return@runCatching null
        val widget = widgetsById[folderId]

        val position = extractPosition(item) ?: widget?.position ?: fallbackPosition
        val folderName = extractString(item, "folderName") ?: widget?.folderName.orEmpty()
        val mediaId = extractString(item, "mediaId", "mediaID", "media_id")
        val url = extractString(item, "url", "productUrl", "clickUrl", "href")
            ?: widget?.clickUrl
            ?: fallbackPageUrl

        EmbedWidgetClick(
            folderId = folderId,
            folderName = folderName,
            position = position,
            mediaId = mediaId,
            url = url,
        )
    }.getOrNull()
}

private fun extractPosition(json: JSONObject): EmbedPosition? {
    val value = extractString(json, "embedLocation", "position", "slot")
    return runCatching { EmbedPosition.valueOf(value ?: return null) }.getOrNull()
}

private fun extractString(json: JSONObject, vararg keys: String): String? {
    keys.forEach { key ->
        val value = json.opt(key) ?: return@forEach
        when (value) {
            is String -> if (value.isNotBlank()) return value
            is Number -> return value.toString()
        }
    }
    return null
}

private fun extractEventType(json: JSONObject): String {
    val direct = listOf("eventType", "type", "event", "action")
        .asSequence()
        .map { key -> json.optString(key).trim() }
        .firstOrNull { it.isNotBlank() }
    if (!direct.isNullOrBlank()) return direct

    val nested = json.optJSONObject("data")
    if (nested != null) {
        val nestedType = listOf("eventType", "type", "event", "action")
            .asSequence()
            .map { key -> nested.optString(key).trim() }
            .firstOrNull { it.isNotBlank() }
        if (!nestedType.isNullOrBlank()) return nestedType
    }
    return "unknown"
}

private fun shouldOpenLightbox(payloadJson: String): Boolean {
    return runCatching {
        val json = JSONObject(payloadJson)
        val type = extractEventType(json).lowercase()
        if (type != "click") {
            return@runCatching false
        }

        val item = json.optJSONObject("item") ?: json.optJSONObject("data")
        val disabledFromJson = json.optBoolean("disabledLightBox", false)
        val disabledFromItem = item?.optBoolean("disabledLightBox", false) ?: false
        !(disabledFromJson || disabledFromItem)
    }.getOrDefault(false)
}

private fun shouldCloseLightbox(payloadJson: String): Boolean {
    return runCatching {
        val json = JSONObject(payloadJson)
        val type = extractEventType(json).lowercase()
        if (type == "togglelb") {
            val open = when {
                json.has("open") -> json.optBoolean("open", true)
                json.optJSONObject("data")?.has("open") == true -> json.optJSONObject("data")?.optBoolean("open", true) ?: true
                else -> true
            }
            return@runCatching !open
        }
        type == "close" || type == "close_lightbox" || type == "closelightbox"
    }.getOrElse {
        val raw = payloadJson.lowercase()
        raw.contains("togglelb") && (raw.contains("\"open\":false") || raw.contains("'open':false") || raw.contains("open=false")) ||
            raw.contains("close_lightbox") ||
            raw.contains("closelightbox")
    }
}

private fun normalizeLightboxMessage(payloadJson: String, sourceWidgetId: String?): String {
    return runCatching {
        val json = JSONObject(payloadJson)
        val type = json.optString("eventType").lowercase()
        if (type != "click") return@runCatching payloadJson

        val rawItem = json.optJSONObject("item") ?: json.optJSONObject("data") ?: return@runCatching payloadJson
        val item = JSONObject(rawItem.toString())
        val folderId = item.optString("folderId").takeIf { it.isNotBlank() } ?: sourceWidgetId
        if (!folderId.isNullOrBlank() && item.optString("folderId").isNullOrBlank()) {
            item.put("folderId", folderId)
        }
        // Keep click payload aligned with web/ios behavior: avoid mutating highlight payload shape.
        if (item.optString("layout").isNullOrBlank()) {
            val layoutFromSettings = item.optJSONObject("settings")?.optString("layout")
            if (!layoutFromSettings.isNullOrBlank()) {
                item.put("layout", layoutFromSettings)
            }
        }

        JSONObject()
            .put("eventType", "click")
            .put("item", item)
            .toString()
    }.getOrElse { payloadJson }
}

private fun buildDispatchMessageScript(messageJson: String): String {
    val quotedPayload = JSONObject.quote(messageJson)
    return """
        (function() {
          try {
            var raw = $quotedPayload;
            var data = raw;
            try { data = JSON.parse(raw); } catch (e) {}
            window.__tagnologyLastDispatchedMessage = data;
            try {
              window.dispatchEvent(new MessageEvent('message', {
                data: data,
                origin: 'https://embed.tagnology.co'
              }));
            } catch (e) {}
          } catch (e) {}
        })();
    """.trimIndent()
}

private fun isUndefinedLightboxUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed == "undefined" || trimmed == "/undefined") return true
    return trimmed.endsWith("/undefined") || trimmed.contains("/undefined?")
}

private fun shouldOpenOutsideLightbox(url: String, hasGesture: Boolean): Boolean {
    if (!hasGesture) return false
    return runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https", "mailto", "tel", "line", "intent")) return false
        if (scheme == "mailto" || scheme == "tel" || scheme == "line" || scheme == "intent") return true
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return false
        host != "embed.tagnology.co" && host != "www.embed.tagnology.co"
    }.getOrDefault(false)
}

private fun openExternalUrl(context: android.content.Context, url: String): Boolean {
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.e("EmbedSDK", "lightbox openExternalUrl fail url=$url err=${it.message}")
        false
    }
}

private fun canonicalizePageUrl(pageUrl: String): String {
    return runCatching {
        val uri = Uri.parse(pageUrl)
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val path = uri.encodedPath.orEmpty()
        if (scheme.isNotBlank() && host.isNotBlank()) {
            "$scheme://$host$path"
        } else {
            pageUrl
        }
    }.getOrDefault(pageUrl)
}

private fun summarizeLightboxClickPayload(payloadJson: String?): String {
    if (payloadJson.isNullOrBlank()) return "payload=empty"
    return runCatching {
        val root = JSONObject(payloadJson)
        val item = root.optJSONObject("item") ?: root.optJSONObject("data") ?: JSONObject()
        val folderId = item.optString("folderId").takeIf { it.isNotBlank() } ?: "-"
        val contentId = item.optString("contentId").takeIf { it.isNotBlank() } ?: "-"
        val mediaId = item.optString("mediaId").takeIf { it.isNotBlank() } ?: "-"
        val highlightId = item.optString("highlightId").takeIf { it.isNotBlank() } ?: "-"
        val thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.isNotBlank() } ?: "-"
        val highlightCover = item.optString("highlightCover").takeIf { it.isNotBlank() } ?: "-"
        val sourceThumbnail = item.optJSONObject("source")?.optString("thumbnail")?.takeIf { it.isNotBlank() } ?: "-"
        val mediaUrl = item.optJSONObject("mediaDetail")?.optString("mediaUrl")?.takeIf { it.isNotBlank() } ?: "-"
        val topMediaUrl = item.optString("mediaUrl").takeIf { it.isNotBlank() } ?: "-"
        val shortcode = item.optString("shortcode").takeIf { it.isNotBlank() } ?: "-"
        val mediaType = item.optString("mediaType").takeIf { it.isNotBlank() } ?: "-"
        "folderId=$folderId contentId=$contentId mediaId=$mediaId highlightId=$highlightId mediaType=$mediaType shortcode=$shortcode highlightCover=$highlightCover thumbnailUrl=$thumbnailUrl source.thumbnail=$sourceThumbnail mediaDetail.mediaUrl=$mediaUrl mediaUrl=$topMediaUrl"
    }.getOrElse { "payload=parse_error" }
}

private fun summarizePayloadKeys(payloadJson: String?): String {
    if (payloadJson.isNullOrBlank()) return "payload=empty"
    return runCatching {
        val root = JSONObject(payloadJson)
        val topKeys = root.keys().asSequence().toList().sorted().joinToString(",")
        val item = root.optJSONObject("item")
        val itemKeys = item?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "-"
        val data = root.optJSONObject("data")
        val dataKeys = data?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "-"
        val media = root.optJSONObject("media")
        val mediaKeys = media?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "-"
        "top=[$topKeys] item=[$itemKeys] data=[$dataKeys] media=[$mediaKeys]"
    }.getOrElse { "payload=parse_error" }
}

private const val INJECT_EVENT_BRIDGE_JS = """
(function() {
  if (window.__tagnologyNativeBridgeInjected) return;
  window.__tagnologyNativeBridgeInjected = true;
  var __tagnologyLastPayload = '';
  var __tagnologyLastPayloadAt = 0;

  function notifyNative(payload) {
    try {
      var data = payload;
      if (typeof payload === 'string') {
        data = JSON.parse(payload);
      }
      var json = JSON.stringify(data || {});
      var now = Date.now();
      if (__tagnologyLastPayload === json && (now - __tagnologyLastPayloadAt) < 120) {
        return;
      }
      __tagnologyLastPayload = json;
      __tagnologyLastPayloadAt = now;
      if (window.tagnologyEvent && window.tagnologyEvent.postEvent) {
        window.tagnologyEvent.postEvent(json);
      } else if (window.tagnologyEvent && window.tagnologyEvent.postMessage) {
        window.tagnologyEvent.postMessage(json);
      }
    } catch (e) {
      // ignore parse and bridge errors
    }
  }

  window.__tagnologyNotifyEvent = notifyNative;

  // Some pages call tagnologyEvent.postMessage/postEvent with plain objects.
  // Android JavascriptInterface expects a String; force JSON serialization.
  function wrapNativeBridgeMethod(name) {
    try {
      if (!window.tagnologyEvent || typeof window.tagnologyEvent[name] !== 'function') return;
      var original = window.tagnologyEvent[name];
      if (original.__tagnologyWrapped) return;
      var wrapped = function(payload) {
        try {
          var normalized = payload;
          if (typeof payload !== 'string') {
            normalized = JSON.stringify(payload || {});
          }
          return original.call(window.tagnologyEvent, normalized);
        } catch (e) {
          return original.call(window.tagnologyEvent, String(payload));
        }
      };
      wrapped.__tagnologyWrapped = true;
      window.tagnologyEvent[name] = wrapped;
    } catch (e) {
      // ignore
    }
  }

  wrapNativeBridgeMethod('postEvent');
  wrapNativeBridgeMethod('postMessage');

  window.addEventListener('message', function(event) {
    if (event && event.data) {
      notifyNative(event.data);
    }
  });
})();
"""

private const val INJECT_LIGHTBOX_DEBUG_JS = """
(function() {
  if (window.__tagnologyLightboxDebugInjected) return;
  window.__tagnologyLightboxDebugInjected = true;
  var __tagnologyMediaFixLogged = {};

  function safeStringify(value) {
    try { return JSON.stringify(value); } catch (e) { return String(value); }
  }

  function logDebug(message, data) {
    var suffix = data === undefined ? '' : (' data=' + safeStringify(data));
    console.log('[Tagnology][AndroidLB]', message + suffix);
  }

  var originalPushState = history.pushState;
  history.pushState = function(state, title, url) {
    logDebug('history.pushState', { url: url, state: state, lastMessage: window.__tagnologyLastDispatchedMessage });
    return originalPushState.apply(history, arguments);
  };

  var originalReplaceState = history.replaceState;
  history.replaceState = function(state, title, url) {
    logDebug('history.replaceState', { url: url, state: state, lastMessage: window.__tagnologyLastDispatchedMessage });
    return originalReplaceState.apply(history, arguments);
  };

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url);
      if (String(url).indexOf('/undefined') >= 0) {
        logDebug('fetch.undefined', { url: url, init: init, lastMessage: window.__tagnologyLastDispatchedMessage });
      }
      return originalFetch.apply(this, arguments);
    };
  }

  if (window.location && typeof window.location.assign === 'function') {
    var originalAssign = window.location.assign.bind(window.location);
    window.location.assign = function(url) {
      if (String(url).indexOf('/undefined') >= 0) {
        logDebug('location.assign.undefined', { url: url, lastMessage: window.__tagnologyLastDispatchedMessage });
      }
      return originalAssign(url);
    };
  }

  window.addEventListener('error', function(event) {
    logDebug('window.error', {
      message: event && event.message,
      source: event && event.filename,
      line: event && event.lineno,
      col: event && event.colno
    });
  });

  window.addEventListener('unhandledrejection', function(event) {
    logDebug('unhandledrejection', { reason: event && event.reason });
  });

  // Visual debug: confirm fullscreen area and iframe bounds on Android.
  function applyVisualDebug() {
    try {
      if (!document.documentElement || !document.body) {
        return;
      }
      document.documentElement.style.background = '#120022';
      document.body.style.background = '#120022';
      document.body.style.minHeight = '100vh';
    } catch (e) {
      logDebug('visualDebug.error', { message: String(e) });
    }
  }

  applyVisualDebug();
  setTimeout(applyVisualDebug, 300);
  setTimeout(applyVisualDebug, 1000);

  function isUndefinedUrl(value) {
    if (value == null) return false;
    var text = String(value).trim();
    return text === 'undefined' || text === '/undefined' || text.indexOf('/undefined') >= 0;
  }

  function scanUndefinedAttrs(root) {
    if (!root || !root.querySelectorAll) return;
    var nodes = root.querySelectorAll('[src],[href],[poster],[data-src]');
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      var attrs = ['src', 'href', 'poster', 'data-src'];
      for (var j = 0; j < attrs.length; j += 1) {
        var key = attrs[j];
        var val = node.getAttribute && node.getAttribute(key);
        if (isUndefinedUrl(val)) {
          logDebug('dom.undefined_attr', {
            tag: node.tagName,
            attr: key,
            value: val,
            outer: (node.outerHTML || '').slice(0, 300)
          });
        }
      }
    }
  }

  var mutationObserver = new MutationObserver(function(mutations) {
    for (var i = 0; i < mutations.length; i += 1) {
      var m = mutations[i];
      if (m.type === 'attributes') {
        var t = m.target;
        var attr = m.attributeName;
        var val = t && t.getAttribute ? t.getAttribute(attr) : null;
        if ((attr === 'src' || attr === 'href' || attr === 'poster' || attr === 'data-src') && isUndefinedUrl(val)) {
          logDebug('dom.undefined_attr_mutation', {
            tag: t && t.tagName,
            attr: attr,
            value: val,
            outer: (t && t.outerHTML ? t.outerHTML.slice(0, 300) : '')
          });
        }
      } else if (m.type === 'childList') {
        for (var k = 0; k < m.addedNodes.length; k += 1) {
          var added = m.addedNodes[k];
          if (added && added.nodeType === 1) {
            scanUndefinedAttrs(added);
          }
        }
      }
    }
  });

  mutationObserver.observe(document.documentElement || document, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ['src', 'href', 'poster', 'data-src']
  });

  setTimeout(function() { scanUndefinedAttrs(document); }, 200);
  setTimeout(function() { scanUndefinedAttrs(document); }, 1200);

  function ancestorChainInfo(el, depth) {
    var rows = [];
    var cur = el;
    var maxDepth = depth || 8;
    var idx = 0;
    while (cur && idx < maxDepth) {
      var cs = window.getComputedStyle(cur);
      var r = cur.getBoundingClientRect ? cur.getBoundingClientRect() : { width: 0, height: 0 };
      rows.push({
        tag: cur.tagName || '',
        id: cur.id || '',
        className: (cur.className && String(cur.className).slice(0, 80)) || '',
        w: Math.round(r.width || 0),
        h: Math.round(r.height || 0),
        display: cs.display,
        position: cs.position,
        flex: cs.flex,
        flexDirection: cs.flexDirection,
        alignItems: cs.alignItems,
        justifyContent: cs.justifyContent,
        minHeight: cs.minHeight,
        height: cs.height
      });
      cur = cur.parentElement;
      idx += 1;
    }
    return rows;
  }

  function normalizeAncestorChain(el) {
    var cur = el && el.parentElement;
    var depth = 0;
    while (cur && depth < 10) {
      var cs = window.getComputedStyle(cur);
      var r = cur.getBoundingClientRect ? cur.getBoundingClientRect() : { height: 0 };
      var h = Number(r.height || 0);
      // Stop at dialog paper/container level to avoid touching whole page root too much.
      if (String(cur.className || '').indexOf('MuiDialog-paper') >= 0 || String(cur.className || '').indexOf('MuiDialog-container') >= 0) {
        break;
      }
      if (h <= 2 || cs.height === '0px' || cs.minHeight === '0px') {
        // Minimal fix: only prevent zero-height collapse; do not force layout proportions.
        cur.style.height = 'auto';
        cur.style.maxHeight = 'none';
        cur.style.minHeight = '1px';
        if (cs.display === 'inline' || cs.display === 'inline-block') {
          cur.style.display = 'block';
        }
        if (cs.flexBasis === '0px') {
          cur.style.flexBasis = 'auto';
        }
        cur.style.overflow = 'visible';
      }
      cur = cur.parentElement;
      depth += 1;
    }
  }

  function markMediaFixLog(type, el, rect, idx) {
    var src = (el && (el.currentSrc || el.src)) || (type + '-idx-' + idx);
    if (__tagnologyMediaFixLogged[src]) return;
    __tagnologyMediaFixLogged[src] = true;
    logDebug(type + '.fix.applied', {
      src: src,
      rect: { w: Math.round((rect && rect.width) || 0), h: Math.round((rect && rect.height) || 0) },
      chain: ancestorChainInfo(el, 10)
    });
  }

  function forceVisibleMediaLayout() {
    try {
      var videos = document.querySelectorAll('video');
      for (var i = 0; i < videos.length; i += 1) {
        var video = videos[i];
        var rect = video.getBoundingClientRect ? video.getBoundingClientRect() : { width: 0, height: 0 };
        if ((rect.height || 0) > 4) continue;
        normalizeAncestorChain(video);
        video.style.display = 'block';
        video.style.width = '100%';
        video.style.height = 'auto';
        video.style.minHeight = '1px';
        video.style.maxHeight = 'none';
        markMediaFixLog('video', video, rect, i);
      }

      var images = document.querySelectorAll('img');
      for (var j = 0; j < images.length; j += 1) {
        var image = images[j];
        var imgRect = image.getBoundingClientRect ? image.getBoundingClientRect() : { width: 0, height: 0 };
        if ((imgRect.height || 0) > 4) continue;
        normalizeAncestorChain(image);
        image.style.display = 'block';
        image.style.width = '100%';
        image.style.height = 'auto';
        image.style.minHeight = '1px';
        image.style.maxHeight = 'none';
        image.style.margin = '0';
        image.style.transform = 'none';
        markMediaFixLog('image', image, imgRect, j);
      }
    } catch (e) {
      logDebug('media.fix.error', { message: String(e) });
    }
  }

  forceVisibleMediaLayout();
  setTimeout(forceVisibleMediaLayout, 120);
  setTimeout(forceVisibleMediaLayout, 500);
  setTimeout(forceVisibleMediaLayout, 1200);
  var mediaObserver = new MutationObserver(function() { forceVisibleMediaLayout(); });
  mediaObserver.observe(document.documentElement || document, { subtree: true, childList: true, attributes: true });
})();
"""

private const val INJECT_LIGHTBOX_CSS_ONLY_JS = """
(function() {
  var id = '__tagnology_android_lb_css_only';
  if (document.getElementById(id)) return;
  var style = document.createElement('style');
  style.id = id;
  style.textContent = `
    html, body, #__next {
      width: 100% !important;
      height: 100% !important;
      min-height: 100% !important;
      margin: 0 !important;
      padding: 0 !important;
    }
    .MuiDialog-root,
    .MuiDialog-container,
    .MuiDialog-paper {
      width: 100% !important;
      height: 100% !important;
      max-height: 100% !important;
    }
    .MuiDialog-paper {
      margin: 0 !important;
      max-width: 100% !important;
      border-radius: 0 !important;
    }
    .MuiDialog-paper [style*="height:0px"],
    .MuiDialog-paper [style*="height: 0px"] {
      height: auto !important;
      min-height: 1px !important;
    }
    .MuiDialog-paper img,
    .MuiDialog-paper video {
      display: block !important;
      width: 100% !important;
      height: auto !important;
      max-height: none !important;
    }
  `;
  (document.head || document.documentElement).appendChild(style);
})();
"""

private const val INJECT_LIGHTBOX_OBSERVABILITY_JS = """
(function() {
  if (window.__tagnologyAndroidLbObsInjected) return;
  window.__tagnologyAndroidLbObsInjected = true;

  function log(name, payload) {
    try {
      console.log('[Tagnology][LBOBS] ' + name + ' ' + JSON.stringify(payload || {}));
    } catch (e) {
      console.log('[Tagnology][LBOBS] ' + name);
    }
  }

  function attachMedia(media) {
    if (!media || media.__lbObsAttached) return;
    media.__lbObsAttached = true;
    var tag = (media.tagName || '').toLowerCase();
    var src = media.currentSrc || media.src || '';
    log('media_attach', { tag: tag, src: src, muted: media.muted, volume: media.volume });
    media.addEventListener('volumechange', function() {
      log('volume_change', { muted: media.muted, volume: media.volume, src: media.currentSrc || media.src || '' });
    });
    media.addEventListener('ended', function() {
      log('media_ended', { tag: tag, src: media.currentSrc || media.src || '' });
    });
    media.addEventListener('play', function() {
      log('media_play', { tag: tag, src: media.currentSrc || media.src || '' });
    });
  }

  function scanMedia() {
    var medias = document.querySelectorAll('video, img');
    for (var i = 0; i < medias.length; i += 1) {
      attachMedia(medias[i]);
    }
  }

  var lastPrimary = '';
  function observePrimaryMediaChange() {
    var video = document.querySelector('video');
    var img = document.querySelector('img');
    var current = '';
    if (video) {
      current = 'video:' + (video.currentSrc || video.src || '');
    } else if (img) {
      current = 'img:' + (img.currentSrc || img.src || '');
    }
    if (current && current !== lastPrimary) {
      lastPrimary = current;
      log('primary_media_changed', { media: current });
    }
  }

  var mo = new MutationObserver(function() {
    scanMedia();
    observePrimaryMediaChange();
  });
  mo.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true });

  window.addEventListener('message', function(event) {
    var data = event && event.data ? event.data : null;
    if (!data || typeof data !== 'object') return;
    if (!data.eventType) return;
    log('message_event', { eventType: data.eventType, open: data.open, index: data.index, mediaId: data.mediaId, contentId: data.contentId });
  }, true);

  scanMedia();
  observePrimaryMediaChange();
})();
"""

private const val INJECT_LIGHTBOX_CLOSE_GUARD_JS = """
(function() {
  if (window.__tagnologyCloseGuardInjected) return;
  window.__tagnologyCloseGuardInjected = true;

  function sendClose(reason) {
    try {
      if (window.__tagnologyNotifyEvent) {
        window.__tagnologyNotifyEvent({ eventType: 'toggleLB', open: false, reason: reason || 'close_guard' });
      } else if (window.tagnologyEvent && window.tagnologyEvent.postEvent) {
        window.tagnologyEvent.postEvent(JSON.stringify({ eventType: 'toggleLB', open: false, reason: reason || 'close_guard' }));
      }
    } catch (e) {
      // ignore
    }
  }

  function isCloseLikeTarget(target) {
    if (!target || !target.closest) return false;
    var closeNode = target.closest(
      '[aria-label*="close" i], [aria-label*="cancel" i], [title*="close" i], [title*="cancel" i], [class*="close" i], [id*="close" i], [data-testid*="close" i]'
    );
    return !!closeNode;
  }

  document.addEventListener('click', function(event) {
    var t = event.target;
    if (isCloseLikeTarget(t)) {
      sendClose('close_element_click');
      return;
    }
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    var x = event.clientX || 0;
    var y = event.clientY || 0;
    // Fallback for X icon area: top-right corner only.
    if (vw > 0 && vh > 0 && x >= vw * 0.82 && y <= Math.max(120, vh * 0.16)) {
      sendClose('close_zone_click');
    }
  }, true);
})();
"""

private const val REPORT_LIGHTBOX_STATE_JS = """
(function() {
  function rectInfo(el) {
    if (!el || !el.getBoundingClientRect) return null;
    var r = el.getBoundingClientRect();
    var cs = window.getComputedStyle(el);
    return {
      tag: el.tagName,
      id: el.id || '',
      className: (el.className && String(el.className).slice(0, 120)) || '',
      x: Math.round(r.left),
      y: Math.round(r.top),
      w: Math.round(r.width),
      h: Math.round(r.height),
      display: cs.display,
      visibility: cs.visibility,
      opacity: cs.opacity,
      position: cs.position,
      zIndex: cs.zIndex,
      bg: cs.backgroundColor
    };
  }

  function mediaInfo(el) {
    var info = rectInfo(el) || {};
    info.src = el.currentSrc || el.src || '';
    info.poster = el.poster || '';
    return info;
  }

  try {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    var cx = Math.floor(vw / 2);
    var cy = Math.floor(vh / 2);
    var centerStack = (document.elementsFromPoint ? document.elementsFromPoint(cx, cy) : [])
      .slice(0, 8)
      .map(rectInfo)
      .filter(Boolean);

    var overlays = Array.prototype.slice.call(document.querySelectorAll('*'))
      .filter(function(el) {
        var cs = window.getComputedStyle(el);
        if (cs.position !== 'fixed') return false;
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity || '1') <= 0.01) return false;
        var r = el.getBoundingClientRect();
        return r.width >= vw * 0.6 && r.height >= vh * 0.2;
      })
      .slice(0, 8)
      .map(rectInfo)
      .filter(Boolean);

    var images = Array.prototype.slice.call(document.querySelectorAll('img')).slice(0, 8).map(mediaInfo);
    var videos = Array.prototype.slice.call(document.querySelectorAll('video')).slice(0, 8).map(mediaInfo);
    var iframes = Array.prototype.slice.call(document.querySelectorAll('iframe')).slice(0, 8).map(mediaInfo);

    return JSON.stringify({
      href: location.href,
      viewport: { w: vw, h: vh, cx: cx, cy: cy },
      body: rectInfo(document.body),
      centerStack: centerStack,
      overlays: overlays,
      mediaCounts: { images: document.querySelectorAll('img').length, videos: document.querySelectorAll('video').length, iframes: document.querySelectorAll('iframe').length },
      images: images,
      videos: videos,
      iframes: iframes
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
"""

private const val INJECT_RESIZE_JS = """
(function() {
  if (window.__tagnologyResizeInjected) return;
  window.__tagnologyResizeInjected = true;
  var lastHeight = -1;

  function measureHeight() {
    var frame = document.getElementById('embed-frame');
    if (frame) {
      var rect = frame.getBoundingClientRect();
      if (rect && rect.height) {
        return rect.height;
      }
    }
    var body = document.body;
    var doc = document.documentElement;
    if (!body || !doc) return 0;
    return Math.max(
      body.scrollHeight || 0,
      body.offsetHeight || 0,
      doc.clientHeight || 0,
      doc.scrollHeight || 0,
      doc.offsetHeight || 0
    );
  }

  function reportHeight() {
    var h = Number(measureHeight());
    if (!h || Number.isNaN(h)) return;
    h = Math.max(1, Math.round(h));
    if (Math.abs(h - lastHeight) <= 1) return;
    lastHeight = h;
    if (window.tagnologyResize && window.tagnologyResize.postHeight) {
      window.tagnologyResize.postHeight(h);
    }
  }

  var observer = new MutationObserver(reportHeight);
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
  window.addEventListener('load', reportHeight, true);
  window.addEventListener('resize', reportHeight, true);
  setTimeout(reportHeight, 50);
  setTimeout(reportHeight, 300);
  setTimeout(reportHeight, 800);
})();
"""

private const val REPORT_HEIGHT_JS = """
(function() {
  function measureHeight() {
    var frame = document.getElementById('embed-frame');
    if (frame) {
      var rect = frame.getBoundingClientRect();
      if (rect && rect.height) {
        return rect.height;
      }
    }
    var body = document.body;
    var doc = document.documentElement;
    if (!body || !doc) return 0;
    return Math.max(
      body.scrollHeight || 0,
      body.offsetHeight || 0,
      doc.clientHeight || 0,
      doc.scrollHeight || 0,
      doc.offsetHeight || 0
    );
  }

  var h = Number(measureHeight());
  if (!h || Number.isNaN(h)) return;
  h = Math.max(1, Math.round(h));
  if (window.tagnologyResize && window.tagnologyResize.postHeight) {
    window.tagnologyResize.postHeight(h);
  }
})();
"""
