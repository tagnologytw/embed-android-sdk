package co.tagnology.embed.sdk

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.net.URL

object EmbedAndroidSDK {
    private const val DEFAULT_BASE_URL = "https://embed.tagnology.co/api"
    private const val TAG = "EmbedSDK"

    /**
     * Default limit for initialize to get the page data. Past it, initialize returns
     * a 408 error and the App should not show the widgets.
     */
    const val DEFAULT_INITIALIZE_TIMEOUT_MS = 3_000L

    // pageBundle requests run here so initialize can stop waiting at the deadline:
    // a blocking HttpURLConnection does not respond to coroutine cancellation.
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val BELOW_BUY_BUTTON = EmbedPosition.BELOW_BUY_BUTTON
    val BELOW_MAIN_PRODUCT_INFO = EmbedPosition.BELOW_MAIN_PRODUCT_INFO
    val ABOVE_RECOMMENDATION = EmbedPosition.ABOVE_RECOMMENDATION
    val ABOVE_FILTER = EmbedPosition.ABOVE_FILTER

    // 浮窗影音固定版位
    val FIXED_BOTTOM_LEFT = EmbedPosition.FIXED_BOTTOM_LEFT
    val FIXED_BOTTOM_RIGHT = EmbedPosition.FIXED_BOTTOM_RIGHT
    val FIXED_TOP_LEFT = EmbedPosition.FIXED_TOP_LEFT
    val FIXED_TOP_RIGHT = EmbedPosition.FIXED_TOP_RIGHT
    val FIXED_CENTER_LEFT = EmbedPosition.FIXED_CENTER_LEFT
    val FIXED_CENTER_RIGHT = EmbedPosition.FIXED_CENTER_RIGHT

    private val lock = Mutex()
    private val pageBundleCache = mutableMapOf<String, List<EmbedWidgetItem>>()
    private val mockPageBundle = mutableMapOf<String, List<EmbedWidgetItem>>()

    /**
     * Initializes embed data for the current page. Call it before showing EmbedWidgetView.
     *
     * @param timeoutMs Milliseconds allowed to get the page data (DEFAULT_INITIALIZE_TIMEOUT_MS, 3000).
     *        Past it, initialize returns a 408 error. 0 or less means no limit.
     * @return null on success; the error when initialization fails.
     */
    suspend fun initialize(
        pageUrl: String,
        mid: String,
        secret: String,
        baseUrl: String = DEFAULT_BASE_URL,
        forceRefresh: Boolean = false,
        timeoutMs: Long = DEFAULT_INITIALIZE_TIMEOUT_MS,
    ): EmbedWidgetLoadError? = withContext(Dispatchers.IO) {
        Log.d(TAG, "initialize start pageUrl=$pageUrl mid=$mid forceRefresh=$forceRefresh timeoutMs=$timeoutMs")
        val pageId = extractPageId(pageUrl) ?: return@withContext EmbedWidgetLoadError(
            statusCode = 422,
            message = "pageUrl 無法解析頁面 ID",
            pageUrl = pageUrl,
            position = EmbedPosition.BELOW_BUY_BUTTON,
        ).also { Log.e(TAG, "initialize fail status=422 reason=invalid pageUrl") }

        if (!isSecretValid(secret)) {
            return@withContext EmbedWidgetLoadError(
                statusCode = 422,
                message = "secret 格式錯誤，必須為 Base64 且 decode 後 32 bytes",
                pageUrl = pageUrl,
                position = EmbedPosition.BELOW_BUY_BUTTON,
            ).also { Log.e(TAG, "initialize fail status=422 reason=invalid secret") }
        }

        lock.withLock {
            if (!forceRefresh && pageBundleCache.containsKey(pageUrl)) {
                Log.d(TAG, "initialize skip hit cache pageUrl=$pageUrl")
                val cachedWidgets = pageBundleCache[pageUrl].orEmpty()
                EmbedAnalyticsTracker.beginPageIfNeeded(
                    pageUrl = pageUrl,
                    widgets = cachedWidgets,
                    baseUrl = baseUrl,
                    forceNewSession = forceRefresh,
                )
                return@withContext null
            }
        }

        mockPageBundle[pageUrl]?.let { mocked ->
            lock.withLock { pageBundleCache[pageUrl] = mocked }
            Log.d(TAG, "initialize use mock pageBundle size=${mocked.size} pageUrl=$pageUrl")
            EmbedAnalyticsTracker.beginPageIfNeeded(
                pageUrl = pageUrl,
                widgets = mocked,
                baseUrl = baseUrl,
                forceNewSession = forceRefresh,
            )
            return@withContext null
        }

        val result = withDeadline(timeoutMs) {
            EmbedApi.fetchPageBundle(
                baseUrl = baseUrl,
                pageUrl = pageUrl,
                pageId = pageId,
                mid = mid,
                payloadSecret = secret,
                timeoutMs = timeoutMs,
            )
        } ?: return@withContext EmbedWidgetLoadError(
            statusCode = 408,
            message = "pageBundle 超過 ${timeoutMs}ms 未回應",
            pageUrl = pageUrl,
            position = EmbedPosition.BELOW_BUY_BUTTON,
        ).also { Log.e(TAG, "initialize fail status=408 timeoutMs=$timeoutMs pageUrl=$pageUrl") }

        return@withContext if (result.isSuccess) {
            val widgets = result.getOrDefault(emptyList())
            lock.withLock { pageBundleCache[pageUrl] = widgets }
            Log.d(TAG, "initialize success api pageBundle size=${widgets.size} pageUrl=$pageUrl")
            EmbedAnalyticsTracker.beginPageIfNeeded(
                pageUrl = pageUrl,
                widgets = widgets,
                baseUrl = baseUrl,
                forceNewSession = forceRefresh,
            )
            null
        } else {
            val exception = result.exceptionOrNull()
            // The socket timeouts match the deadline, so a stalled request may end here first.
            val statusCode = if (exception is SocketTimeoutException) 408 else 500
            EmbedWidgetLoadError(
                statusCode = statusCode,
                message = exception?.message ?: "初始化失敗",
                pageUrl = pageUrl,
                position = EmbedPosition.BELOW_BUY_BUTTON,
            ).also { Log.e(TAG, "initialize fail status=$statusCode error=${it.message}") }
        }
    }

    suspend fun getWidgets(
        pageUrl: String,
        position: EmbedPosition,
    ): Result<List<EmbedWidgetItem>> = withContext(Dispatchers.IO) {
        val widgets = lock.withLock { pageBundleCache[pageUrl] } ?: run {
            Log.e(TAG, "getWidgets fail status=428 pageUrl=$pageUrl position=$position")
            return@withContext Result.failure(IllegalStateException("尚未 initialize，statusCode=428"))
        }

        // Mirrors the iOS SDK's filterWidgetsByPosition: FIXED_* positions return
        // only FloatingMedia widgets pinned there; regular positions exclude
        // FloatingMedia (its embedLocation field is unrelated to where it floats).
        val isFixedPosition = position.floatingMediaPositionValue() != null
        val filtered = if (isFixedPosition) {
            widgets.filter {
                it.layout.equals("floatingmedia", ignoreCase = true) && it.position == position
            }
        } else {
            widgets.filter { it.position == position }
                .filterNot { it.layout.equals("floatingmedia", ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
            Log.w(TAG, "getWidgets empty status=204 pageUrl=$pageUrl position=$position")
            return@withContext Result.failure(NoSuchElementException("該版位無資料，statusCode=204"))
        }

        Log.d(TAG, "getWidgets success count=${filtered.size} pageUrl=$pageUrl position=$position")
        Result.success(filtered)
    }

    suspend fun clearCache(pageUrl: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (pageUrl == null) pageBundleCache.clear() else pageBundleCache.remove(pageUrl)
        }
    }

    fun notifyPageDidLeave(baseUrl: String = DEFAULT_BASE_URL) {
        EmbedAnalyticsTracker.endPageIfNeeded(baseUrl = baseUrl)
    }

    fun setMockPageBundle(pageUrl: String, widgets: List<EmbedWidgetItem>) {
        mockPageBundle[pageUrl] = widgets
    }

    fun clearMockPageBundle(pageUrl: String? = null) {
        if (pageUrl == null) mockPageBundle.clear() else mockPageBundle.remove(pageUrl)
    }

    /**
     * Runs a blocking request and returns its result, or null once timeoutMs pass first.
     * The request keeps running in the background until its own socket timeouts end it;
     * its late result is dropped. timeoutMs of 0 or less waits without a limit.
     */
    private suspend fun <T : Any> withDeadline(timeoutMs: Long, request: () -> T): T? {
        if (timeoutMs <= 0) return request()
        val pending = requestScope.async { request() }
        return withTimeoutOrNull(timeoutMs) { pending.await() } ?: run {
            pending.cancel()
            null
        }
    }

    private fun isSecretValid(secret: String): Boolean {
        return try {
            Base64.decode(secret, Base64.DEFAULT).size == 32
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Extracts page ID from page URL using 91APP rules, mirroring the iOS SDK's
     * extractPageIdFromPageUrl:
     *  - /SalePage/Index/{id}   -> "{id}"
     *  - /SalePageCategory/{id} -> "category_{id}"
     * Path segment matching is case-insensitive.
     */
    internal fun extractPageId(pageUrl: String): String? {
        return runCatching {
            val components = URL(pageUrl).path.split("/").filter { it.isNotEmpty() }
            val lowercased = components.map { it.lowercase() }

            val indexPosition = lowercased.indexOf("index")
            if (indexPosition != -1 && indexPosition + 1 < components.size) {
                return@runCatching components[indexPosition + 1]
            }

            val categoryPosition = lowercased.indexOf("salepagecategory")
            if (categoryPosition != -1 && categoryPosition + 1 < components.size) {
                // Category pages must carry the "category_" prefix to match the web
                // side's getPageInfo lookup key; a bare id returns an empty pageBundle.
                return@runCatching "category_" + components[categoryPosition + 1]
            }

            null
        }.getOrNull()
    }
}
