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

    @Volatile
    var appHandler: EmbedLinkHandler? = null

    /**
     * Opens a link that leaves the embed: asks the Host App's handler first, then the system.
     * Callers decide beforehand that [url] must leave the WebView.
     */
    fun open(
        context: Context,
        url: String,
        systemOpen: (Context, String) -> Boolean = ::openExternalUrl,
    ): LinkOutcome {
        val handler = appHandler
        val handled = handler != null && runCatching { handler.shouldOverrideUrl(url) }
            .onFailure { Log.e(TAG, "link handler failed url=$url err=${it.message}") }
            .getOrDefault(false)
        if (handled) {
            Log.d(TAG, "link handled by app url=$url")
            return LinkOutcome.HANDLED_BY_APP
        }
        return if (systemOpen(context, url)) LinkOutcome.OPENED_BY_SYSTEM else LinkOutcome.FAILED
    }
}
