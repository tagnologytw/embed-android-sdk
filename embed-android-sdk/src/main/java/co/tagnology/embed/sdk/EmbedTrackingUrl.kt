package co.tagnology.embed.sdk

import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Builds click tracking URLs with the same UTM rules as the web embed and the iOS SDK.
 *
 * - Widget click ([forWidgetClick]): the page URL with the UTM the web 91app script adds on a
 *   widget click (web/public/js/91app.js).
 * - Link click ([forLink]): the tapped link with the UTM the web page adds to product links
 *   (Helper.AddUTMParams).
 *
 * A UTM value the SDK does not have is left empty (for example `utm_content=`). The query is
 * encoded like the browser's URLSearchParams (space as "+", "+" as "%2B").
 */
internal object EmbedTrackingUrl {
    const val UTM_SOURCE = "embed_tagnology"
    const val DEFAULT_MEDIUM = "CUSTOMIZED"
    const val LINK_MEDIUM = "LINK"

    /**
     * - Page URL has no utm_source, or utm_source is embed_tagnology: sets utm_source,
     *   utm_id=folderId, utm_content=mediaId, utm_medium=embedLocation (CUSTOMIZED if none),
     *   utm_campaign=folderName (folderId if none), utm_term=page URL without query and fragment.
     * - Page URL already has another utm_source: keeps that UTM and only changes utm_content to
     *   "<utm_content>+embed_tagnology" (or "embed_tagnology").
     * Other query parameters and the fragment are kept. null when [pageUrl] is not http(s).
     */
    fun forWidgetClick(
        pageUrl: String,
        folderId: String,
        folderName: String?,
        embedLocation: String?,
        mediaId: String?,
    ): String? {
        val parts = split(pageUrl) ?: return null
        val params = parts.params
        val currentSource = params.value("utm_source")
        if (!currentSource.isNullOrEmpty() && currentSource != UTM_SOURCE) {
            val currentContent = params.value("utm_content").orEmpty()
            val newContent = if (currentContent.isNotEmpty() && !currentContent.contains(UTM_SOURCE)) {
                "$currentContent+$UTM_SOURCE"
            } else {
                UTM_SOURCE
            }
            params.set("utm_content", newContent)
        } else {
            params.set("utm_source", UTM_SOURCE)
            params.set("utm_id", folderId)
            params.set("utm_content", mediaId.orEmpty())
            params.set("utm_medium", embedLocation.nonEmpty() ?: DEFAULT_MEDIUM)
            params.set("utm_campaign", folderName.nonEmpty() ?: folderId)
            params.set("utm_term", parts.base)
        }
        return join(parts)
    }

    /**
     * utm_source=embed_tagnology, utm_content=mediaId, utm_medium=LINK, utm_id=folderId,
     * utm_campaign=folderName, utm_term=page URL. When the web page already added embed UTM to
     * the link, its values win (the lightbox can show another post than the one first tapped);
     * the SDK only fills what is missing. Other UTM on the link is replaced, as on the web.
     * null for links that are not http(s) (tel:, mailto:, intent:).
     */
    fun forLink(
        linkUrl: String,
        pageUrl: String?,
        folderId: String?,
        folderName: String?,
        mediaId: String?,
    ): String? {
        val parts = split(linkUrl) ?: return null
        val params = parts.params
        val linkHasEmbedUtm = params.value("utm_source") == UTM_SOURCE

        fun setKeepingEmbedValue(name: String, value: String?) {
            if (linkHasEmbedUtm && !params.value(name).isNullOrEmpty()) return
            params.set(name, value.orEmpty())
        }

        params.set("utm_source", UTM_SOURCE)
        setKeepingEmbedValue("utm_content", mediaId)
        setKeepingEmbedValue("utm_medium", LINK_MEDIUM)
        setKeepingEmbedValue("utm_id", folderId)
        setKeepingEmbedValue("utm_campaign", folderName)
        setKeepingEmbedValue("utm_term", pageUrl)
        return join(parts)
    }

    /** The embed utm_content already on [url], when the web page added embed UTM to it. */
    fun embedMediaIdOnLink(url: String): String? {
        val params = split(url)?.params ?: return null
        if (params.value("utm_source") != UTM_SOURCE) return null
        return params.value("utm_content").nonEmpty()
    }

    internal class QueryParams {
        val items = mutableListOf<Pair<String, String>>()

        fun value(name: String): String? = items.firstOrNull { it.first == name }?.second

        /** Like URLSearchParams.set: replaces the first match and drops the rest. */
        fun set(name: String, value: String) {
            val index = items.indexOfFirst { it.first == name }
            if (index < 0) {
                items += name to value
                return
            }
            items[index] = name to value
            var seen = false
            items.removeAll { item ->
                if (item.first != name) return@removeAll false
                val drop = seen
                seen = true
                drop
            }
        }
    }

    internal class UrlParts(val base: String, val params: QueryParams, val fragment: String?)

    /** Splits an http(s) URL into the part before "?", its query and its fragment. */
    internal fun split(url: String): UrlParts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isEmpty()) return null

        var rest = url
        var fragment: String? = null
        rest.indexOf('#').takeIf { it >= 0 }?.let { index ->
            fragment = rest.substring(index + 1)
            rest = rest.substring(0, index)
        }
        var query = ""
        rest.indexOf('?').takeIf { it >= 0 }?.let { index ->
            query = rest.substring(index + 1)
            rest = rest.substring(0, index)
        }
        val params = QueryParams()
        query.split('&').filter { it.isNotEmpty() }.forEach { segment ->
            val equals = segment.indexOf('=')
            val name = if (equals < 0) segment else segment.substring(0, equals)
            val value = if (equals < 0) "" else segment.substring(equals + 1)
            params.items += formDecode(name) to formDecode(value)
        }
        return UrlParts(rest, params, fragment)
    }

    internal fun join(parts: UrlParts): String = buildString {
        append(parts.base)
        val query = parts.params.items.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
        if (query.isNotEmpty()) append('?').append(query)
        parts.fragment?.let { append('#').append(it) }
    }

    // URLEncoder already encodes like application/x-www-form-urlencoded (URLSearchParams).
    private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun formDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }
}

/** Reads values from the widget page's click payload. */
internal object EmbedClickPayload {
    /**
     * The tapped post's mediaId. Highlight clicks have none (they open a story group); the
     * lightbox starts at the first story, so the first media of the prefetched `mediaList` is
     * used when the page sent it. null when neither is present.
     */
    fun mediaId(item: JSONObject): String? {
        listOf("mediaId", "mediaID", "media_id").forEach { key ->
            string(item.opt(key))?.let { return it }
        }
        val first = item.optJSONArray("mediaList")?.optJSONObject(0) ?: return null
        return string(first.opt("mediaId"))
    }

    private fun string(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        else -> null
    }
}
