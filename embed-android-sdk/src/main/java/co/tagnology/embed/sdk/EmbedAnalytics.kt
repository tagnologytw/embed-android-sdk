package co.tagnology.embed.sdk

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal enum class EmbedAction(val value: String) {
    pageView("PAGE_VIEW"),
    embedView("EMBED_VIEW"),
    dwellTime("DWELL_TIME"),
}

internal object EmbedAnalyticsTracker {
    private const val TAG = "EmbedSDK"
    private const val MIN_DWELL_TIME_MS = 5000L

    private var currentPageUrl: String? = null
    private var currentHost: String? = null
    private var currentBaseUrl: String? = null
    private var folderById: Map<String, EmbedWidgetItem> = emptyMap()
    private var folderIds: List<String> = emptyList()
    private var loggedEmbedFolderIds: MutableSet<String> = mutableSetOf()
    private var hasLoggedPageView = false
    private var startTimeMs: Long? = null
    private var startWidgetTimeMs: Long? = null
    private var dwellTimeSent = false
    private var nowProvider: () -> Long = { System.currentTimeMillis() }
    private var asyncExecutor: ((() -> Unit) -> Unit) = { job -> Thread(job).start() }

    @Synchronized
    fun beginPageIfNeeded(
        pageUrl: String,
        widgets: List<EmbedWidgetItem>,
        baseUrl: String,
        forceNewSession: Boolean = false,
    ) {
        val isNewPage = currentPageUrl != pageUrl
        if (isNewPage || forceNewSession) {
            currentPageUrl = pageUrl
            currentHost = Uri.parse(pageUrl).host
            currentBaseUrl = baseUrl
            folderById = emptyMap()
            folderIds = emptyList()
            loggedEmbedFolderIds = mutableSetOf()
            hasLoggedPageView = false
            startTimeMs = nowMs()
            startWidgetTimeMs = null
            dwellTimeSent = false
        }

        if (widgets.isNotEmpty()) {
            val byId = LinkedHashMap<String, EmbedWidgetItem>()
            widgets.forEach { widget ->
                if (widget.folderId.isNotBlank()) {
                    byId[widget.folderId] = widget
                }
            }
            folderById = byId
            folderIds = byId.keys.toList()
        }

        if (!hasLoggedPageView && folderIds.isNotEmpty()) {
            hasLoggedPageView = true
            sendLog(
                action = EmbedAction.pageView,
                info = JSONObject().put("folderIds", JSONArray(folderIds)),
                baseUrl = baseUrl,
            )
        }
    }

    @Synchronized
    fun markWidgetVisible(
        widget: EmbedWidgetItem,
        baseUrl: String = "https://embed.tagnology.co/api",
    ): Boolean {
        val folderId = widget.folderId
        if (folderId.isBlank()) return false
        if (!folderById.containsKey(folderId)) return false

        if (startWidgetTimeMs == null) {
            startWidgetTimeMs = nowMs()
        }
        if (loggedEmbedFolderIds.contains(folderId)) {
            return false
        }
        loggedEmbedFolderIds.add(folderId)

        if (widget.layout.equals("floatingmedia", ignoreCase = true)) {
            return true
        }

        val activeBaseUrl = currentBaseUrl ?: baseUrl
        sendLog(
            action = EmbedAction.embedView,
            info = JSONObject()
                .put("folderId", folderId)
                .put("embedLocation", widget.position.name),
            baseUrl = activeBaseUrl,
        )
        return true
    }

    @Synchronized
    fun endPageIfNeeded(baseUrl: String) {
        val start = startTimeMs ?: return
        if (dwellTimeSent) return

        val currentTimeMs = nowMs()
        val dwellTime = currentTimeMs - start
        if (dwellTime <= MIN_DWELL_TIME_MS) {
            return
        }
        if (folderIds.isEmpty()) {
            return
        }

        val activeBaseUrl = currentBaseUrl ?: baseUrl
        val widgetDwellTime = startWidgetTimeMs?.let { (currentTimeMs - it).coerceAtLeast(0L) } ?: 0L

        folderIds.forEach { folderId ->
            sendLog(
                action = EmbedAction.dwellTime,
                info = JSONObject()
                    .put("folderId", folderId)
                    .put("dwellTime", dwellTime)
                    .put("widgetDwellTime", widgetDwellTime),
                baseUrl = activeBaseUrl,
            )
        }

        dwellTimeSent = true
    }

    private fun sendLog(action: EmbedAction, info: JSONObject, baseUrl: String) {
        val host = currentHost
        val page = currentPageUrl
        if (host.isNullOrBlank() || page.isNullOrBlank()) return

        val endpoint = "${baseUrl.trimEnd('/')}/widget/log"
        val infoWithCommon = JSONObject(info.toString())
            .put("page", page)
            .put("isMobile", true)

        val payload = JSONObject()
            .put("host", host)
            .put("action", action.value)
            .put("info", infoWithCommon)

        // Keep only concise analytics logs for verification in Logcat.
        Log.d(TAG, "[EmbedAnalytics] ${action.value} page=$page info=$infoWithCommon")

        asyncExecutor {
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.write(payload.toString().toByteArray())
                    output.flush()
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    Log.e(TAG, "[EmbedAnalytics] ${action.value} failed status=$status")
                } else {
                    Log.d(TAG, "[EmbedAnalytics] ${action.value} success status=$status")
                }
                connection.disconnect()
            }.onFailure {
                Log.e(TAG, "[EmbedAnalytics] ${action.value} error=${it.message}")
            }
        }
    }

    private fun nowMs(): Long = nowProvider()

    @Synchronized
    internal fun resetForTests() {
        currentPageUrl = null
        currentHost = null
        currentBaseUrl = null
        folderById = emptyMap()
        folderIds = emptyList()
        loggedEmbedFolderIds = mutableSetOf()
        hasLoggedPageView = false
        startTimeMs = null
        startWidgetTimeMs = null
        dwellTimeSent = false
        nowProvider = { System.currentTimeMillis() }
        asyncExecutor = { job -> Thread(job).start() }
    }

    @Synchronized
    internal fun setNowProviderForTests(provider: () -> Long) {
        nowProvider = provider
    }

    @Synchronized
    internal fun setAsyncExecutorForTests(executor: ((() -> Unit) -> Unit)) {
        asyncExecutor = executor
    }
}
