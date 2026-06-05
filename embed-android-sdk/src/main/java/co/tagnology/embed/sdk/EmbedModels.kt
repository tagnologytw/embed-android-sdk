package co.tagnology.embed.sdk

enum class EmbedPosition {
    BELOW_BUY_BUTTON,
    BELOW_MAIN_PRODUCT_INFO,
    ABOVE_RECOMMENDATION,
    ABOVE_FILTER,
}

data class EmbedWidgetLoadError(
    val statusCode: Int,
    val message: String,
    val pageUrl: String,
    val position: EmbedPosition,
)

data class EmbedWidgetClick(
    val folderId: String,
    val folderName: String,
    val position: EmbedPosition,
    val mediaId: String?,
    val url: String,
)

data class EmbedWidgetEvent(
    val type: String,
    val payloadJson: String,
)

data class EmbedWidgetItem(
    val folderId: String,
    val folderName: String,
    val position: EmbedPosition,
    val html: String? = null,
    val layout: String? = null,
    val clickUrl: String,
    val mediaId: String? = null,
)
