package co.tagnology.embed.sdk

enum class EmbedPosition {
    BELOW_BUY_BUTTON,
    BELOW_MAIN_PRODUCT_INFO,
    ABOVE_RECOMMENDATION,
    ABOVE_FILTER,

    // 浮窗影音固定版位（FloatingMedia），與 iOS SDK 對齊
    FIXED_BOTTOM_LEFT,
    FIXED_BOTTOM_RIGHT,
    FIXED_TOP_LEFT,
    FIXED_TOP_RIGHT,
    FIXED_CENTER_LEFT,
    FIXED_CENTER_RIGHT,
}

/**
 * Maps a FIXED_* position to the pageBundle "floatingMediaPosition" value
 * (e.g. FIXED_BOTTOM_RIGHT -> "BottomRight"), mirroring the iOS SDK's
 * getFloatingMediaPositionForEmbedPosition. Returns null for regular positions.
 */
internal fun EmbedPosition.floatingMediaPositionValue(): String? = when (this) {
    EmbedPosition.FIXED_BOTTOM_LEFT -> "BottomLeft"
    EmbedPosition.FIXED_BOTTOM_RIGHT -> "BottomRight"
    EmbedPosition.FIXED_TOP_LEFT -> "TopLeft"
    EmbedPosition.FIXED_TOP_RIGHT -> "TopRight"
    EmbedPosition.FIXED_CENTER_LEFT -> "CenterLeft"
    EmbedPosition.FIXED_CENTER_RIGHT -> "CenterRight"
    else -> null
}

internal fun embedPositionForFloatingMediaPosition(value: String?): EmbedPosition? = when (value) {
    "BottomLeft" -> EmbedPosition.FIXED_BOTTOM_LEFT
    "BottomRight" -> EmbedPosition.FIXED_BOTTOM_RIGHT
    "TopLeft" -> EmbedPosition.FIXED_TOP_LEFT
    "TopRight" -> EmbedPosition.FIXED_TOP_RIGHT
    "CenterLeft" -> EmbedPosition.FIXED_CENTER_LEFT
    "CenterRight" -> EmbedPosition.FIXED_CENTER_RIGHT
    else -> null
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
    val floatingMediaPosition: String? = null,
)
