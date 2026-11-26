package co.tagnology.embed.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Same cases as the iOS SDK's EmbedTrackingURLTests. */
@RunWith(RobolectricTestRunner::class)
class EmbedTrackingUrlTest {
    private val page = "https://shop.91app.com/SalePage/Index/9323753"
    private val encodedPage = "https%3A%2F%2Fshop.91app.com%2FSalePage%2FIndex%2F9323753"
    private val product = "https://shop.91app.com/SalePage/Index/555"

    @After
    fun tearDown() {
        EmbedAndroidSDK.setLinkHandler(null)
    }

    @Test
    fun widgetClickAddsWebUtmToPageUrl() {
        val url = EmbedTrackingUrl.forWidgetClick(page, "folder-1", "夏季 新品", "BELOW_BUY_BUTTON", "media-9")
        assertEquals(
            "$page?utm_source=embed_tagnology&utm_id=folder-1&utm_content=media-9&utm_medium=BELOW_BUY_BUTTON" +
                "&utm_campaign=%E5%A4%8F%E5%AD%A3+%E6%96%B0%E5%93%81&utm_term=$encodedPage",
            url,
        )
    }

    @Test
    fun widgetClickFallsBackToCustomizedAndFolderIdAndLeavesMediaIdEmpty() {
        val url = EmbedTrackingUrl.forWidgetClick(page, "folder-1", null, null, null)
        assertEquals(
            "$page?utm_source=embed_tagnology&utm_id=folder-1&utm_content=&utm_medium=CUSTOMIZED&utm_campaign=folder-1&utm_term=$encodedPage",
            url,
        )
    }

    @Test
    fun widgetClickKeepsOtherQueryAndFragmentAndReplacesPreviousEmbedUtm() {
        val url = EmbedTrackingUrl.forWidgetClick(
            "$page?shopId=1&utm_source=embed_tagnology&utm_content=old#reviews", "f", "wall", "ABOVE_FILTER", null,
        )
        assertEquals(
            "$page?shopId=1&utm_source=embed_tagnology&utm_content=&utm_id=f&utm_medium=ABOVE_FILTER&utm_campaign=wall" +
                "&utm_term=$encodedPage#reviews",
            url,
        )
    }

    @Test
    fun widgetClickKeepsForeignUtmAndAppendsEmbedToUtmContent() {
        assertEquals(
            "$page?utm_source=facebook&utm_medium=cpc&utm_content=ad1%2Bembed_tagnology",
            EmbedTrackingUrl.forWidgetClick("$page?utm_source=facebook&utm_medium=cpc&utm_content=ad1", "f", "w", "BELOW_BUY_BUTTON", "m"),
        )
        assertEquals(
            "$page?utm_source=line&utm_content=embed_tagnology",
            EmbedTrackingUrl.forWidgetClick("$page?utm_source=line", "f", null, null, null),
        )
    }

    @Test
    fun nonHttpUrlsHaveNoTrackingUrl() {
        assertNull(EmbedTrackingUrl.forWidgetClick("", "f", null, null, null))
        assertNull(EmbedTrackingUrl.forWidgetClick("app://product/1", "f", null, null, null))
        assertNull(EmbedTrackingUrl.forLink("tel:0212345678", page, "f", null, null))
    }

    @Test
    fun linkGetsFullEmbedUtmLikeWebAddUTMParams() {
        assertEquals(
            "$product?utm_source=embed_tagnology&utm_content=m&utm_medium=LINK&utm_id=f&utm_campaign=wall" +
                "&utm_term=${encodedPage}%3FshopId%3D1",
            EmbedTrackingUrl.forLink(product, "$page?shopId=1", "f", "wall", "m"),
        )
    }

    @Test
    fun linkLeavesMissingValuesEmpty() {
        assertEquals(
            "$product?utm_source=embed_tagnology&utm_content=&utm_medium=LINK&utm_id=f&utm_campaign=&utm_term=",
            EmbedTrackingUrl.forLink(product, null, "f", null, null),
        )
    }

    @Test
    fun linkKeepsEmbedUtmTheWebPageAlreadyAdded() {
        val link = "$product?utm_source=embed_tagnology&utm_content=shown-post&utm_medium=LINK&utm_id=f&utm_campaign=wall&utm_term=$encodedPage"
        assertEquals(link, EmbedTrackingUrl.forLink(link, page, "f", "wall", "first-post"))
    }

    @Test
    fun linkReplacesOtherUtmSource() {
        assertEquals(
            "$product?utm_source=embed_tagnology&utm_medium=LINK&utm_content=m&utm_id=f&utm_campaign=w&utm_term=$encodedPage",
            EmbedTrackingUrl.forLink("$product?utm_source=facebook&utm_medium=cpc", page, "f", "w", "m"),
        )
    }

    @Test
    fun clickPayloadMediaIdFallsBackToFirstPrefetchedStory() {
        assertEquals("m", EmbedClickPayload.mediaId(JSONObject().put("mediaId", "m").put("mediaList", JSONArray().put(JSONObject().put("mediaId", "first")))))
        assertEquals("123", EmbedClickPayload.mediaId(JSONObject().put("mediaId", 123)))
        assertEquals(
            "story-1",
            EmbedClickPayload.mediaId(
                JSONObject().put("highlightId", "h").put(
                    "mediaList",
                    JSONArray().put(JSONObject().put("mediaId", "story-1")).put(JSONObject().put("mediaId", "story-2")),
                ),
            ),
        )
        assertNull(EmbedClickPayload.mediaId(JSONObject().put("highlightId", "h")))
        assertNull(EmbedClickPayload.mediaId(JSONObject().put("highlightId", "h").put("mediaList", JSONArray())))
    }

    @Test
    fun linkClickHandlerGetsTrackingUrlAndWidgetInfo() {
        val context: Context = RuntimeEnvironment.getApplication()
        val received = mutableListOf<EmbedLinkClick>()
        EmbedAndroidSDK.setLinkClickHandler { click -> received += click; true }
        val linkContext = EmbedLinkContext(page, "f", "wall", EmbedPosition.BELOW_BUY_BUTTON, "m")

        val outcome = EmbedLinkOpener.open(context, product, linkContext) { _, _ -> error("handled by app") }

        assertEquals(LinkOutcome.HANDLED_BY_APP, outcome)
        val click = received.single()
        assertEquals(product, click.url)
        assertEquals("f", click.folderId)
        assertEquals(EmbedPosition.BELOW_BUY_BUTTON, click.position)
        assertEquals("m", click.mediaId)
        assertEquals(page, click.pageUrl)
        assertTrue(click.trackingUrl!!.startsWith("$product?utm_source=embed_tagnology&utm_content=m&utm_medium=LINK"))
    }

    @Test
    fun legacyLinkHandlerStillGetsTheUnchangedUrl() {
        val context: Context = RuntimeEnvironment.getApplication()
        val received = mutableListOf<String>()
        EmbedAndroidSDK.setLinkHandler { url -> received += url; true }

        val outcome = EmbedLinkOpener.open(context, product, EmbedLinkContext(page, "f", mediaId = "m")) { _, _ -> true }

        assertEquals(LinkOutcome.HANDLED_BY_APP, outcome)
        assertEquals(listOf(product), received)
    }
}
