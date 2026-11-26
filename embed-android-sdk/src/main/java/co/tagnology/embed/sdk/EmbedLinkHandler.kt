package co.tagnology.embed.sdk

import android.content.Context
import android.util.Log

/**
 * Lets the Host App open links tapped in widgets and the lightbox (for example product
 * cards) itself, such as a native product page instead of the browser.
 * Set it with [EmbedAndroidSDK.setLinkHandler].
 */
fun interface EmbedLinkHandler {
    /**
     * Called on the main thread with the link's full URL (for example
     * `https://{shop}/SalePage/Index/{id}?utm_source=...`).
     *
     * @return true when the App opened the link: the SDK does not open the browser and closes
     *         the lightbox if it is showing. false to let the SDK open it as before (browser
     *         or an app that claims the link).
     */
    fun shouldOverrideUrl(url: String): Boolean
}

/**
 * A link tapped in a widget or the lightbox (for example a product card), passed to
 * [EmbedLinkClickHandler].
 *
 * @property url The link as the page opened it, unchanged (the same URL [EmbedLinkHandler] gets).
 * @property trackingUrl [url] with the full embed UTM composed by the SDK, for example
 *           `https://{shop}/SalePage/Index/{id}?utm_source=embed_tagnology&utm_content=...&utm_medium=LINK&utm_id=...&utm_campaign=...&utm_term=...`.
 *           A value the SDK does not have is left empty. null for links that are not http(s).
 * @property folderId Widget folder the link was tapped in.
 * @property position Position of the widget (same value as [EmbedWidgetClick.position]).
 * @property mediaId Post the link belongs to, when known.
 * @property pageUrl The page URL the widget was shown for.
 */
data class EmbedLinkClick(
    val url: String,
    val trackingUrl: String?,
    val folderId: String? = null,
    val folderName: String? = null,
    val position: EmbedPosition? = null,
    val mediaId: String? = null,
    val pageUrl: String? = null,
)

/**
 * Same as [EmbedLinkHandler], but gets an [EmbedLinkClick] with the link's [EmbedLinkClick.trackingUrl]
 * and the widget it was tapped in. Set it with [EmbedAndroidSDK.setLinkClickHandler].
 */
fun interface EmbedLinkClickHandler {
    /** Same return value as [EmbedLinkHandler.shouldOverrideUrl]. */
    fun shouldOverrideUrl(click: EmbedLinkClick): Boolean
}

/** What the SDK knows about the widget a link was tapped in. */
internal data class EmbedLinkContext(
    val pageUrl: String? = null,
    val folderId: String? = null,
    val folderName: String? = null,
    val position: EmbedPosition? = null,
    val mediaId: String? = null,
) {
    fun makeClick(url: String): EmbedLinkClick {
        // The web page's UTM on the link is the most precise source for the post shown.
        val resolvedMediaId = EmbedTrackingUrl.embedMediaIdOnLink(url) ?: mediaId
        return EmbedLinkClick(
            url = url,
            trackingUrl = EmbedTrackingUrl.forLink(
                linkUrl = url,
                pageUrl = pageUrl,
                folderId = folderId,
                folderName = folderName,
                mediaId = mediaId,
            ),
            folderId = folderId,
            folderName = folderName,
            position = position,
            mediaId = resolvedMediaId,
            pageUrl = pageUrl,
        )
    }
}

/** Where an external link went. */
internal enum class LinkOutcome {
    /** The Host App's [EmbedLinkHandler] opened it. */
    HANDLED_BY_APP,
    /** Handed to the system (browser or an app that claims the link). */
    OPENED_BY_SYSTEM,
    /** Neither the App nor the system could open it. */
    FAILED,
}

internal object EmbedLinkOpener {
    private const val TAG = "EmbedSDK"

    /** Set by setLinkHandler or setLinkClickHandler; they share it, the last call wins. */
    @Volatile
    var appHandler: EmbedLinkClickHandler? = null

    /**
     * Opens a link that leaves the embed: asks the Host App's handler first, then the system.
     * Callers decide beforehand that [url] must leave the WebView.
     */
    fun open(
        context: Context,
        url: String,
        linkContext: EmbedLinkContext = EmbedLinkContext(),
        systemOpen: (Context, String) -> Boolean = ::openExternalUrl,
    ): LinkOutcome {
        val handler = appHandler
        val handled = handler != null && runCatching {
            val click = linkContext.makeClick(url)
            Log.d(TAG, "link click url=$url trackingUrl=${click.trackingUrl} folderId=${click.folderId} mediaId=${click.mediaId}")
            handler.shouldOverrideUrl(click)
        }
            .onFailure { Log.e(TAG, "link handler failed url=$url err=${it.message}") }
            .getOrDefault(false)
        if (handled) {
            Log.d(TAG, "link handled by app url=$url")
            return LinkOutcome.HANDLED_BY_APP
        }
        return if (systemOpen(context, url)) LinkOutcome.OPENED_BY_SYSTEM else LinkOutcome.FAILED
    }
}
