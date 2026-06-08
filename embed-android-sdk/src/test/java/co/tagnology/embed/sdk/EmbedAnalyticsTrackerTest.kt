package co.tagnology.embed.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EmbedAnalyticsTrackerTest {
    private lateinit var server: MockWebServer
    private var nowMs: Long = 1_000L

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        EmbedAnalyticsTracker.resetForTests()
        EmbedAnalyticsTracker.setNowProviderForTests { nowMs }
        EmbedAnalyticsTracker.setAsyncExecutorForTests { job -> job() }
    }

    @After
    fun teardown() {
        EmbedAnalyticsTracker.resetForTests()
        server.shutdown()
    }

    @Test
    fun pageView_embedView_dwellTime_should_call_widget_log_api() {
        val pageUrl = "https://partnertest4.91app.com/SalePage/Index/8778110"
        val baseUrl = server.url("/api").toString()
        val widget = EmbedWidgetItem(
            folderId = "folder-a",
            folderName = "Folder A",
            position = EmbedPosition.BELOW_BUY_BUTTON,
            clickUrl = pageUrl,
        )

        EmbedAnalyticsTracker.beginPageIfNeeded(
            pageUrl = pageUrl,
            widgets = listOf(widget),
            baseUrl = baseUrl,
            forceNewSession = true,
        )
        val pageViewRequest = server.takeRequest(2, TimeUnit.SECONDS)
        requireNotNull(pageViewRequest)
        val pageViewBody = pageViewRequest.body.readUtf8()
        assertEquals("/api/widget/log", pageViewRequest.path)
        assertAction(pageViewBody, "PAGE_VIEW")
        assertFolderIds(pageViewBody, listOf("folder-a"))

        EmbedAnalyticsTracker.markWidgetVisible(widget = widget, baseUrl = baseUrl)
        val embedViewRequest = server.takeRequest(2, TimeUnit.SECONDS)
        requireNotNull(embedViewRequest)
        val embedViewBody = embedViewRequest.body.readUtf8()
        assertEquals("/api/widget/log", embedViewRequest.path)
        assertAction(embedViewBody, "EMBED_VIEW")

        nowMs = 7_500L
        EmbedAnalyticsTracker.endPageIfNeeded(baseUrl = baseUrl)
        val dwellRequest = server.takeRequest(2, TimeUnit.SECONDS)
        requireNotNull(dwellRequest)
        val dwellBody = dwellRequest.body.readUtf8()
        assertEquals("/api/widget/log", dwellRequest.path)
        assertAction(dwellBody, "DWELL_TIME")
        assertDwellPayload(dwellBody)
    }

    private fun assertAction(rawBody: String, expected: String) {
        val root = JSONObject(rawBody)
        assertEquals(expected, root.optString("action"))
        assertEquals("partnertest4.91app.com", root.optString("host"))
    }

    private fun assertFolderIds(rawBody: String, expectedIds: List<String>) {
        val info = JSONObject(rawBody).getJSONObject("info")
        val ids = info.optJSONArray("folderIds") ?: JSONArray()
        val actual = buildList {
            for (i in 0 until ids.length()) {
                add(ids.optString(i))
            }
        }
        assertEquals(expectedIds, actual)
    }

    private fun assertDwellPayload(rawBody: String) {
        val info = JSONObject(rawBody).getJSONObject("info")
        assertTrue(info.optLong("dwellTime") > 5000L)
        assertTrue(info.has("widgetDwellTime"))
    }
}
