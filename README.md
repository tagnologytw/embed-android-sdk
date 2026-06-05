# embed-android-sdk

Tagnology Android 內容牆 SDK（電商頁面嵌入）

## 目前完成

- `EmbedAndroidSDK.initialize(...)` 初始化流程
- `EmbedWidgetView(...)` 內容牆 composable
- 4 個標準版位（不含浮窗影音）
  - `BELOW_BUY_BUTTON`：加入購物車下方
  - `BELOW_MAIN_PRODUCT_INFO`：詳細資訊上方（商品主資訊後）
  - `ABOVE_RECOMMENDATION`：相關推薦上方
  - `ABOVE_FILTER`：分類頁過濾器上方
- `demo` app（對齊 iOS `ectest` 的展示用途）

## 專案結構

- `embed-android-sdk/`：SDK module
- `demo/`：示範 App

## 使用方式（SDK）

- initialize 會呼叫：`POST {baseUrl}/widget/pageBundle`

```kotlin
val error = EmbedAndroidSDK.initialize(
    pageUrl = "https://partnertest3.91app.com/SalePage/Index/8555569",
    mid = "41458",
    secret = "YOUR_PAYLOAD_SECRET_BASE64",
    forceRefresh = true,
)

if (error == null) {
    EmbedWidgetView(
        pageUrl = "https://partnertest3.91app.com/SalePage/Index/8555569",
        position = EmbedAndroidSDK.BELOW_BUY_BUTTON,
        onError = { /* status!=200 時可隱藏版位 */ },
        onClick = { click ->
            // click.folderId / click.folderName / click.position / click.mediaId / click.url
        },
        onEvent = { event ->
            // event.type / event.payloadJson
            // 支援接收 iframe postMessage 事件
        }
    )
}
```

### Lightbox（Fullscreen）

- `EmbedWidgetView` 預設 `enableLightbox = true`
- 當 iframe 事件符合 lightbox 展開條件（例如 `eventType=click` / `position=fixed`）時，SDK 會開啟 `https://embed.tagnology.co/lightBox?page=...`
- 事件會透過 `onEvent` 回傳，並在主 widget 與 lightbox 之間橋接 message

## Demo 版位位置

`demo` 內 `EcommerceDemoPage.kt` 已按需求嵌入：

1. `加入購物車下方` -> `BELOW_BUY_BUTTON`
2. `詳細資訊上方` -> `BELOW_MAIN_PRODUCT_INFO`
3. `相關推薦上方` -> `ABOVE_RECOMMENDATION`
4. `分類頁過濾器上方` -> `ABOVE_FILTER`

## 備註

- 浮窗影音（fixed/floating media）尚未實作。
- SDK 目前先支援標準內容牆版位。
- Demo 預設使用 mock pageBundle 讓四個版位可直接驗證畫面。
