package co.tagnology.embed.sdk

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 91APP asked to open product links from widgets and the lightbox as native pages instead
 * of the browser, where favorites and the cart are not in sync with the App.
 */
@RunWith(RobolectricTestRunner::class)
class EmbedLinkHandlerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val productUrl = "https://partnertest7.91app.com/SalePage/Index/9394400?utm_source=embed_tagnology"

    @After
    fun tearDown() {
        EmbedAndroidSDK.setLinkHandler(null)
    }

    @Test
    fun withoutHandlerLinksOpenWithTheSystem() {
        val opened = mutableListOf<String>()
        val outcome = EmbedLinkOpener.open(context, productUrl) { _, url -> opened += url; true }
        assertEquals(LinkOutcome.OPENED_BY_SYSTEM, outcome)
        assertEquals(listOf(productUrl), opened)
    }

    @Test
    fun handlerReturningTrueKeepsTheLinkOutOfTheBrowser() {
        val handled = mutableListOf<String>()
        val opened = mutableListOf<String>()
        EmbedAndroidSDK.setLinkHandler { url -> handled += url; true }
        val outcome = EmbedLinkOpener.open(context, productUrl) { _, url -> opened += url; true }
        assertEquals(LinkOutcome.HANDLED_BY_APP, outcome)
        assertEquals("the App gets the full URL, query included", listOf(productUrl), handled)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun handlerReturningFalseFallsBackToTheSystem() {
        val opened = mutableListOf<String>()
        EmbedAndroidSDK.setLinkHandler { false }
        val outcome = EmbedLinkOpener.open(context, productUrl) { _, url -> opened += url; true }
        assertEquals(LinkOutcome.OPENED_BY_SYSTEM, outcome)
        assertEquals(listOf(productUrl), opened)
    }

    @Test
    fun handlerThatThrowsFallsBackToTheSystem() {
        val opened = mutableListOf<String>()
        EmbedAndroidSDK.setLinkHandler { error("navigator crashed") }
        val outcome = EmbedLinkOpener.open(context, productUrl) { _, url -> opened += url; true }
        assertEquals(LinkOutcome.OPENED_BY_SYSTEM, outcome)
        assertEquals(listOf(productUrl), opened)
    }

    @Test
    fun systemFailureIsReported() {
        val outcome = EmbedLinkOpener.open(context, productUrl) { _, _ -> false }
        assertEquals(LinkOutcome.FAILED, outcome)
    }

    @Test
    fun settingNullRemovesTheHandler() {
        var opened = 0
        EmbedAndroidSDK.setLinkHandler { true }
        EmbedAndroidSDK.setLinkHandler(null)
        assertEquals(LinkOutcome.OPENED_BY_SYSTEM, EmbedLinkOpener.open(context, productUrl) { _, _ -> opened++; true })
        assertEquals(1, opened)
    }
}
