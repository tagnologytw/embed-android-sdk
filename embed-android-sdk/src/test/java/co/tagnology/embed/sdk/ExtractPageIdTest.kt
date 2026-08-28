package co.tagnology.embed.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for EmbedAndroidSDK.extractPageId, mirroring the iOS SDK's
 * ExtractPageIdTests to keep both platforms' URL parsing aligned:
 * case-insensitive segment matching and the "category_" prefix required
 * by the backend for SalePageCategory pages.
 */
class ExtractPageIdTest {

    // Category pages (must carry the "category_" prefix)

    @Test
    fun categoryPageShouldReturnPrefixedId() {
        assertEquals(
            "category_492435",
            EmbedAndroidSDK.extractPageId("https://partnertest4.91app.com/v2/official/SalePageCategory/492435"),
        )
    }

    @Test
    fun categoryPageWithQueryStringShouldReturnPrefixedId() {
        assertEquals(
            "category_481477",
            EmbedAndroidSDK.extractPageId("https://partnertest4.91app.com/v2/official/SalePageCategory/481477?sortMode=Newest"),
        )
    }

    @Test
    fun categoryPageWithTrailingSlashShouldReturnPrefixedId() {
        assertEquals(
            "category_532417",
            EmbedAndroidSDK.extractPageId("https://partnertest4.91app.com/v2/official/SalePageCategory/532417/"),
        )
    }

    @Test
    fun categorySegmentMatchingShouldBeCaseInsensitive() {
        listOf(
            "https://shop.example.com/SalePageCategory/492435",
            "https://shop.example.com/salepagecategory/492435",
            "https://shop.example.com/SALEPAGECATEGORY/492435",
            "https://shop.example.com/SalePageCategoRY/492435",
        ).forEach { pageUrl ->
            assertEquals(pageUrl, "category_492435", EmbedAndroidSDK.extractPageId(pageUrl))
        }
    }

    @Test
    fun categoryPageWithoutOfficialPrefixShouldReturnPrefixedId() {
        assertEquals(
            "category_492435",
            EmbedAndroidSDK.extractPageId("https://shop.example.com/SalePageCategory/492435"),
        )
    }

    // Product pages (must stay unprefixed)

    @Test
    fun productPageShouldReturnBareId() {
        assertEquals(
            "9394402",
            EmbedAndroidSDK.extractPageId("https://partnertest4.91app.com/SalePage/Index/9394402"),
        )
    }

    @Test
    fun productPageWithQueryStringShouldReturnBareId() {
        assertEquals(
            "9323753",
            EmbedAndroidSDK.extractPageId("https://partnertest4.91app.com/SalePage/Index/9323753?utm_source=test"),
        )
    }

    @Test
    fun productSegmentMatchingShouldBeCaseInsensitive() {
        listOf(
            "https://shop.example.com/SalePage/Index/9394402",
            "https://shop.example.com/salepage/index/9394402",
            "https://shop.example.com/SalePage/INDEX/9394402",
        ).forEach { pageUrl ->
            assertEquals(pageUrl, "9394402", EmbedAndroidSDK.extractPageId(pageUrl))
        }
    }

    // Unextractable URLs

    @Test
    fun unextractableUrlShouldReturnNull() {
        listOf(
            "",
            "https://shop.example.com/",
            "https://shop.example.com/AboutUs",
            "https://shop.example.com/SalePageCategory",
            "https://shop.example.com/SalePageCategory/",
            "https://shop.example.com/SalePage/Index",
        ).forEach { pageUrl ->
            assertNull(pageUrl, EmbedAndroidSDK.extractPageId(pageUrl))
        }
    }
}
