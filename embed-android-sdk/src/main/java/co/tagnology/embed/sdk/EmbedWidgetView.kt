package co.tagnology.embed.sdk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import org.json.JSONObject

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
    var widgets by remember(pageUrl, position) { mutableStateOf<List<EmbedWidgetItem>>(emptyList()) }
    var showLightbox by remember { mutableStateOf(false) }
    var lightboxMessageJson by remember { mutableStateOf<String?>(null) }
    var lastEventJson by remember { mutableStateOf<String?>(null) }
    var lastEventAtMs by remember { mutableStateOf(0L) }
    var lastUserTouchAtMs by remember { mutableStateOf(0L) }
    var lastTouchedWidgetId by remember { mutableStateOf<String?>(null) }

    fun handleEventFromIframe(eventJson: String, sourceWidgetId: String?) {
        val now = System.currentTimeMillis()
        if (lastEventJson == eventJson && (now - lastEventAtMs) < 300) {
            return
        }
        lastEventJson = eventJson
        lastEventAtMs = now

        val eventType = parseEventType(eventJson)
        onEvent?.invoke(EmbedWidgetEvent(type = eventType, payloadJson = eventJson))
        Log.d(tag, "event type=$eventType payload=$eventJson")

        if (!enableLightbox || DEBUG_DISABLE_LIGHTBOX) return
        if (shouldOpenLightbox(eventJson)) {
            val touchedRecently = (now - lastUserTouchAtMs) <= 800L
            val touchedSameWidget = !sourceWidgetId.isNullOrBlank() && sourceWidgetId == lastTouchedWidgetId
            val validClickPayload = hasValidLightboxClickPayload(eventJson)
            if (!touchedRecently) {
                Log.d(tag, "lightbox open ignored (no recent user touch)")
                return
            }
            if (!touchedSameWidget) {
                Log.d(tag, "lightbox open ignored (event widget mismatch)")
                return
            }
            if (!validClickPayload) {
                Log.d(tag, "lightbox open ignored (invalid click payload)")
                return
            }
            if (!showLightbox) {
                lightboxMessageJson = normalizeLightboxMessage(eventJson)
                showLightbox = true
                lastTouchedWidgetId = null
                Log.d(tag, "lightbox open by event type=$eventType")
            }
        }
        if (shouldCloseLightbox(eventJson)) {
            showLightbox = false
            Log.d(tag, "lightbox close by event type=$eventType")
        }
    }

    LaunchedEffect(pageUrl, position) {
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

    Column(modifier = modifier.fillMaxWidth()) {
        widgets.forEach { widget ->
            val context = LocalContext.current
            val density = LocalDensity.current
            key(widget.folderId) {
                var webViewHeightPx by remember(widget.folderId) { mutableStateOf(1) }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { webViewHeightPx.toDp() }),
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
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            webChromeClient = WebChromeClient()
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                                    lastUserTouchAtMs = System.currentTimeMillis()
                                    lastTouchedWidgetId = widget.folderId
                                }
                                false
                            }

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

    if (!DEBUG_DISABLE_LIGHTBOX && showLightbox) {
        LightboxDialog(
            pageUrl = pageUrl,
            pendingMessageJson = lightboxMessageJson,
            onEvent = { event ->
                onEvent?.invoke(event)
                if (shouldCloseLightbox(event.payloadJson)) {
                    showLightbox = false
                }
            },
            onDismiss = {
                showLightbox = false
                lightboxMessageJson = null
            },
        )
    }
}

// Temporary switch for debugging startup white overlay issue.
private const val DEBUG_DISABLE_LIGHTBOX = true

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun LightboxDialog(
    pageUrl: String,
    pendingMessageJson: String?,
    onEvent: (EmbedWidgetEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val tag = "EmbedSDK"
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val lightboxUrl = remember(pageUrl) {
        "https://embed.tagnology.co/lightBox?page=$pageUrl"
    }

    var hasDispatchedInitialMessage by remember(pendingMessageJson) { mutableStateOf(false) }
    var lightboxWebViewRef by remember { mutableStateOf<WebView?>(null) }

    fun dispatchPendingMessageIfNeeded() {
        val webView = lightboxWebViewRef ?: return
        if (pendingMessageJson.isNullOrBlank() || hasDispatchedInitialMessage) return
        Log.d(tag, "lightbox dispatch payload length=${pendingMessageJson.length}")
        val script = buildDispatchMessageScript(pendingMessageJson)
        webView.evaluateJavascript(script, null)
        hasDispatchedInitialMessage = true
        Log.d(tag, "lightbox dispatch click message")
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Red),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                val eventBridge = object {
                    @JavascriptInterface
                    fun postEvent(payloadJson: String) {
                        mainHandler.post {
                            val type = parseEventType(payloadJson)
                            if (type.equals("readyLB", ignoreCase = true)) {
                                dispatchPendingMessageIfNeeded()
                                Log.d(tag, "lightbox event type=$type payload=$payloadJson")
                                return@post
                            }

                            if (type.equals("click", ignoreCase = true)) {
                                return@post
                            }

                            if (type.equals("toggleLB", ignoreCase = true)) {
                                val open = runCatching {
                                    JSONObject(payloadJson).optBoolean("open", true)
                                }.getOrDefault(true)
                                if (open) return@post
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

                WebView(context).apply {
                    lightboxWebViewRef = this
                    setBackgroundColor(android.graphics.Color.RED)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(eventBridge, "tagnologyEvent")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            Log.d(tag, "lightbox started url=$url")
                            evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(tag, "lightbox finished url=$url")
                            evaluateJavascript(INJECT_EVENT_BRIDGE_JS, null)
                            postDelayed({ dispatchPendingMessageIfNeeded() }, 300)
                            postDelayed({ dispatchPendingMessageIfNeeded() }, 900)
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
            },
                update = {},
            )
        }
    }
}

private fun buildEmbedDisplayUrl(widget: EmbedWidgetItem, pageUrl: String): String {
    return buildString {
        append("https://embed.tagnology.co/display?folderId=${widget.folderId}&page=$pageUrl")
        if (widget.layout.equals("floatingmedia", ignoreCase = true)) {
            append("&fullScreen=true")
        }
    }
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
        JSONObject(payloadJson).optString("eventType", "unknown")
    }.getOrElse { "unknown" }
}

private fun shouldOpenLightbox(payloadJson: String): Boolean {
    return runCatching {
        val json = JSONObject(payloadJson)
        val type = json.optString("eventType").lowercase()
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
        val type = json.optString("eventType").lowercase()
        if (type == "togglelb") {
            return@runCatching !json.optBoolean("open", true)
        }
        type == "close" || type == "close_lightbox" || type == "closelightbox"
    }.getOrDefault(false)
}

private fun normalizeLightboxMessage(payloadJson: String): String {
    return runCatching {
        val json = JSONObject(payloadJson)
        val type = json.optString("eventType").lowercase()
        if (type != "click") return@runCatching payloadJson

        val rawItem = json.optJSONObject("item") ?: json.optJSONObject("data") ?: return@runCatching payloadJson
        val item = JSONObject(rawItem.toString())

        val source = (item.optJSONObject("source") ?: JSONObject()).let { JSONObject(it.toString()) }
        val mediaDetail = (item.optJSONObject("mediaDetail") ?: JSONObject()).let { JSONObject(it.toString()) }
        val settings = (item.optJSONObject("settings") ?: JSONObject()).let { JSONObject(it.toString()) }

        val sourceThumbnail = source.optString("thumbnail").trim().takeIf { it.isNotEmpty() }
        val mediaDetailUrl = mediaDetail.optString("mediaUrl").trim().takeIf { it.isNotEmpty() }
        val thumbnailUrl = item.optString("thumbnailUrl").trim().takeIf { it.isNotEmpty() }
        val highlightCover = item.optString("highlightCover").trim().takeIf { it.isNotEmpty() }
        val fallbackMediaUrl = thumbnailUrl ?: sourceThumbnail ?: mediaDetailUrl ?: highlightCover

        if (thumbnailUrl.isNullOrEmpty() && !fallbackMediaUrl.isNullOrEmpty()) {
            item.put("thumbnailUrl", fallbackMediaUrl)
        }

        if (sourceThumbnail.isNullOrEmpty() && !fallbackMediaUrl.isNullOrEmpty()) {
            source.put("thumbnail", fallbackMediaUrl)
            item.put("source", source)
        }

        if (mediaDetailUrl.isNullOrEmpty() && !fallbackMediaUrl.isNullOrEmpty()) {
            mediaDetail.put("mediaUrl", fallbackMediaUrl)
            item.put("mediaDetail", mediaDetail)
        }

        val mediaType = item.optString("mediaType").trim()
        if (mediaType.isEmpty() && !fallbackMediaUrl.isNullOrEmpty()) {
            val lower = fallbackMediaUrl.lowercase()
            item.put(
                "mediaType",
                if (lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".webm")) "VIDEO" else "IMAGE"
            )
        }

        if (item.optJSONObject("settings") == null && settings.length() > 0) {
            item.put("settings", settings)
        }

        JSONObject()
            .put("eventType", "click")
            .put("item", item)
            .toString()
    }.getOrElse { payloadJson }
}

private fun buildDispatchMessageScript(messageJson: String): String {
    return """
        (function() {
          try {
            var data = $messageJson;
            window.dispatchEvent(new MessageEvent('message', { data: data, origin: 'https://embed.tagnology.co' }));
          } catch (e) {}
        })();
    """.trimIndent()
}

private fun hasValidLightboxClickPayload(payloadJson: String): Boolean {
    return runCatching {
        val root = JSONObject(payloadJson)
        if (!root.optString("eventType").equals("click", ignoreCase = true)) {
            return@runCatching false
        }
        val item = root.optJSONObject("item") ?: root.optJSONObject("data") ?: return@runCatching false
        val hasContentId = !item.optString("contentId").isNullOrBlank()
        val hasMediaId = !item.optString("mediaId").isNullOrBlank()
        val hasHighlightId = !item.optString("highlightId").isNullOrBlank()
        hasContentId || hasMediaId || hasHighlightId
    }.getOrDefault(false)
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

  var originalPostMessage = window.postMessage;
  window.postMessage = function(message, targetOrigin, transfer) {
    notifyNative(message);
    if (typeof originalPostMessage === 'function') {
      return originalPostMessage.call(this, message, targetOrigin, transfer);
    }
  };

  window.addEventListener('message', function(event) {
    if (event && event.data) {
      notifyNative(event.data);
    }
  });
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
