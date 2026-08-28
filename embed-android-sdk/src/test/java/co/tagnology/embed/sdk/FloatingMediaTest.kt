package co.tagnology.embed.sdk

import android.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for FloatingMedia (浮窗影音) FIXED_* positions, mirroring the iOS SDK's
 * filterWidgetsByPosition behavior: a FIXED_* position returns only FloatingMedia
 * widgets pinned there via "floatingMediaPosition", and regular positions exclude
 * FloatingMedia even though the backend also sends an embedLocation for it.
 */
@RunWith(RobolectricTestRunner::class)
class FloatingMediaTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private val mid = "41458"
    private val secret = Base64.encodeToString(ByteArray(32) { it.toByte() }, Base64.NO_WRAP)
    private val pageUrl = "https://partnertest4.91app.com/SalePage/Index/9323727"

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api").toString()

        EmbedAnalyticsTracker.resetForTests()
        EmbedAnalyticsTracker.setAsyncExecutorForTests { job -> job() }
        runBlocking { EmbedAndroidSDK.clearCache() }
    }

    @After
    fun teardown() {
        EmbedAnalyticsTracker.resetForTests()
        runBlocking { EmbedAndroidSDK.clearCache() }
        server.shutdown()
    }

    // Mirrors the real backend payload for the floating-media example page:
    // a FloatingMedia item still carries an (unrelated) embedLocation field.
    private fun floatingMediaItem(
        folderId: String = "folder-floating",
        floatingMediaPosition: String? = "BottomRight",
        positionInSettingOnly: Boolean = false,
    ): JSONObject {
        val item = JSONObject()
            .put("folderId", folderId)
            .put("folderName", "浮窗影音 右下角")
            .put("embedLocation", "BELOW_BUY_BUTTON")
            .put("layout", "FloatingMedia")
        val setting = JSONObject().put("layout", "FloatingMedia")
        if (floatingMediaPosition != null) {
            if (!positionInSettingOnly) {
                item.put("floatingMediaPosition", floatingMediaPosition)
            }
            setting.put("floatingMediaPosition", floatingMediaPosition)
        }
        return item.put("setting", setting)
    }

    private fun regularItem(
        folderId: String = "folder-regular",
        embedLocation: String = "BELOW_BUY_BUTTON",
    ): JSONObject = JSONObject()
        .put("folderId", folderId)
        .put("folderName", "內容牆")
        .put("embedLocation", embedLocation)
        .put("layout", "grid")

    private fun enqueuePageBundle(vararg items: JSONObject) {
        val body = JSONObject().put("pageBundle", JSONArray(items.toList()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
        // analytics PAGE_VIEW log fired synchronously by the test executor
        server.enqueue(MockResponse().setResponseCode(200))
    }

    private fun initialize() = runBlocking {
        assertNull(EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl))
    }

    // Position mapping

    @Test
    fun fixedPositionsShouldRoundTripThroughFloatingMediaPositionValues() {
        val expected = mapOf(
            EmbedPosition.FIXED_BOTTOM_LEFT to "BottomLeft",
            EmbedPosition.FIXED_BOTTOM_RIGHT to "BottomRight",
            EmbedPosition.FIXED_TOP_LEFT to "TopLeft",
            EmbedPosition.FIXED_TOP_RIGHT to "TopRight",
            EmbedPosition.FIXED_CENTER_LEFT to "CenterLeft",
            EmbedPosition.FIXED_CENTER_RIGHT to "CenterRight",
        )
        expected.forEach { (position, value) ->
            assertEquals(value, position.floatingMediaPositionValue())
            assertEquals(position, embedPositionForFloatingMediaPosition(value))
        }
    }

    @Test
    fun regularPositionsShouldHaveNoFloatingMediaPositionValue() {
        listOf(
            EmbedPosition.BELOW_BUY_BUTTON,
            EmbedPosition.BELOW_MAIN_PRODUCT_INFO,
            EmbedPosition.ABOVE_RECOMMENDATION,
            EmbedPosition.ABOVE_FILTER,
        ).forEach { position ->
            assertNull(position.name, position.floatingMediaPositionValue())
        }
        assertNull(embedPositionForFloatingMediaPosition("Unknown"))
        assertNull(embedPositionForFloatingMediaPosition(null))
    }

    // getWidgets filtering

    @Test
    fun fixedPositionShouldReturnFloatingMediaWidget() {
        enqueuePageBundle(floatingMediaItem(), regularItem())
        initialize()

        val widgets = runBlocking {
            EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_BOTTOM_RIGHT).getOrThrow()
        }
        assertEquals(1, widgets.size)
        assertEquals("folder-floating", widgets[0].folderId)
        assertEquals(EmbedPosition.FIXED_BOTTOM_RIGHT, widgets[0].position)
        assertEquals("BottomRight", widgets[0].floatingMediaPosition)
        assertEquals("FloatingMedia", widgets[0].layout)
    }

    @Test
    fun regularPositionShouldExcludeFloatingMediaDespiteItsEmbedLocation() {
        enqueuePageBundle(floatingMediaItem(), regularItem())
        initialize()

        val widgets = runBlocking {
            EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.BELOW_BUY_BUTTON).getOrThrow()
        }
        assertEquals(1, widgets.size)
        assertEquals("folder-regular", widgets[0].folderId)
    }

    @Test
    fun otherFixedPositionShouldReturnNoData() {
        enqueuePageBundle(floatingMediaItem(floatingMediaPosition = "BottomRight"))
        initialize()

        val result = runBlocking {
            EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_TOP_LEFT)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("204"))
    }

    @Test
    fun floatingMediaPositionInSettingOnlyShouldStillResolve() {
        enqueuePageBundle(floatingMediaItem(positionInSettingOnly = true))
        initialize()

        val widgets = runBlocking {
            EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_BOTTOM_RIGHT).getOrThrow()
        }
        assertEquals(1, widgets.size)
        assertEquals("BottomRight", widgets[0].floatingMediaPosition)
    }

    @Test
    fun unknownFloatingMediaPositionShouldSkipTheWidget() {
        enqueuePageBundle(floatingMediaItem(floatingMediaPosition = "MiddleEverywhere"), regularItem())
        initialize()

        EmbedPosition.entries
            .filter { it.floatingMediaPositionValue() != null }
            .forEach { fixedPosition ->
                val result = runBlocking { EmbedAndroidSDK.getWidgets(pageUrl, fixedPosition) }
                assertTrue("expected no data at $fixedPosition", result.isFailure)
            }
    }

    @Test
    fun missingFloatingMediaPositionShouldSkipTheWidget() {
        enqueuePageBundle(floatingMediaItem(floatingMediaPosition = null))
        initialize()

        val result = runBlocking {
            EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_BOTTOM_RIGHT)
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun eachFixedPositionShouldOnlyMatchItsOwnWidget() {
        enqueuePageBundle(
            floatingMediaItem(folderId = "f-br", floatingMediaPosition = "BottomRight"),
            floatingMediaItem(folderId = "f-tl", floatingMediaPosition = "TopLeft"),
        )
        initialize()

        runBlocking {
            val bottomRight = EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_BOTTOM_RIGHT).getOrThrow()
            assertEquals(listOf("f-br"), bottomRight.map { it.folderId })

            val topLeft = EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_TOP_LEFT).getOrThrow()
            assertEquals(listOf("f-tl"), topLeft.map { it.folderId })

            assertTrue(EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.FIXED_BOTTOM_LEFT).isFailure)
        }
    }
}
