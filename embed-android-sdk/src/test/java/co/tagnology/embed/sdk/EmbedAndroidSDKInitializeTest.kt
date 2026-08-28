package co.tagnology.embed.sdk

import android.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Integration tests for EmbedAndroidSDK.initialize covering URL parsing through
 * the public API: case-insensitive segment matching, the "category_" prefix on
 * the encrypted pageBundle request, and 422 validation failures.
 */
@RunWith(RobolectricTestRunner::class)
class EmbedAndroidSDKInitializeTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private val mid = "41458"
    private val secret = Base64.encodeToString(ByteArray(32) { it.toByte() }, Base64.NO_WRAP)

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

    private fun enqueuePageBundle(embedLocation: String) {
        val body = JSONObject()
            .put(
                "pageBundle",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("folderId", "folder-1")
                        .put("folderName", "內容牆")
                        .put("embedLocation", embedLocation)
                        .put("layout", "grid")
                ),
            )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
        // analytics PAGE_VIEW log fired synchronously by the test executor
        server.enqueue(MockResponse().setResponseCode(200))
    }

    private fun takePageBundleRequest(): RecordedRequest {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("expected a pageBundle request", request)
        assertEquals("/api/widget/pageBundle", request!!.path)
        return request
    }

    private fun decryptPayload(request: RecordedRequest): JSONObject {
        val body = JSONObject(request.body.readUtf8())
        val iv = Base64.decode(body.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(body.getString("payload"), Base64.NO_WRAP)
        val tag = Base64.decode(body.getString("tag"), Base64.NO_WRAP)

        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest((mid + secret).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext + tag)
        return JSONObject(String(plaintext, Charsets.UTF_8))
    }

    // Category pages

    @Test
    fun categoryUrlShouldInitializeAndSendPrefixedPageId() = runBlocking {
        val pageUrl = "https://partnertest4.91app.com/v2/official/SalePageCategory/532417"
        enqueuePageBundle("ABOVE_FILTER")

        val error = EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl)

        assertNull(error)
        val payload = decryptPayload(takePageBundleRequest())
        assertEquals("category_532417", payload.getString("id"))
        assertEquals(pageUrl, payload.getString("url"))
        assertEquals(mid.toInt(), payload.getInt("mid"))

        val widgets = EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.ABOVE_FILTER)
        assertEquals(1, widgets.getOrThrow().size)
    }

    @Test
    fun mixedCaseCategoryUrlShouldInitializeAndSendPrefixedPageId() = runBlocking {
        // Regression: the category segment match must be case-insensitive
        // (previously returned 422 before any request was sent).
        val pageUrl = "https://partnertest4.91app.com/v2/official/SALEPAGECATEGORY/532417"
        enqueuePageBundle("ABOVE_FILTER")

        val error = EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl)

        assertNull(error)
        val payload = decryptPayload(takePageBundleRequest())
        assertEquals("category_532417", payload.getString("id"))
    }

    @Test
    fun lowercaseCategoryUrlShouldInitializeAndSendPrefixedPageId() = runBlocking {
        val pageUrl = "https://partnertest4.91app.com/v2/official/salepagecategory/492435"
        enqueuePageBundle("ABOVE_FILTER")

        val error = EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl)

        assertNull(error)
        val payload = decryptPayload(takePageBundleRequest())
        assertEquals("category_492435", payload.getString("id"))
    }

    // Product pages

    @Test
    fun productUrlShouldInitializeAndSendBarePageId() = runBlocking {
        val pageUrl = "https://partnertest4.91app.com/SalePage/Index/9394402"
        enqueuePageBundle("BELOW_BUY_BUTTON")

        val error = EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl)

        assertNull(error)
        val payload = decryptPayload(takePageBundleRequest())
        assertEquals("9394402", payload.getString("id"))

        val widgets = EmbedAndroidSDK.getWidgets(pageUrl, EmbedPosition.BELOW_BUY_BUTTON)
        assertEquals(1, widgets.getOrThrow().size)
    }

    @Test
    fun mixedCaseProductUrlShouldInitializeAndSendBarePageId() = runBlocking {
        val pageUrl = "https://partnertest4.91app.com/salepage/index/9394402"
        enqueuePageBundle("BELOW_BUY_BUTTON")

        val error = EmbedAndroidSDK.initialize(pageUrl, mid, secret, baseUrl)

        assertNull(error)
        val payload = decryptPayload(takePageBundleRequest())
        assertEquals("9394402", payload.getString("id"))
    }

    // Validation failures

    @Test
    fun unparseableUrlShouldReturn422WithoutSendingRequest() = runBlocking {
        val error = EmbedAndroidSDK.initialize(
            "https://shop.example.com/AboutUs",
            mid,
            secret,
            baseUrl,
        )

        assertNotNull(error)
        assertEquals(422, error!!.statusCode)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidSecretShouldReturn422WithoutSendingRequest() = runBlocking {
        val error = EmbedAndroidSDK.initialize(
            "https://partnertest4.91app.com/v2/official/SalePageCategory/532417",
            mid,
            "not-a-valid-secret",
            baseUrl,
        )

        assertNotNull(error)
        assertEquals(422, error!!.statusCode)
        assertEquals(0, server.requestCount)
    }
}
