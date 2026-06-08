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

## Analytics 事件（對齊 iOS SDK）

SDK 會送出 `POST {baseUrl}/widget/log`，事件如下：

- `PAGE_VIEW`：`initialize` 成功且 `pageBundle` 非空時，單次 page session 送一次。
- `EMBED_VIEW`：widget 在 viewport 內可見比例達門檻後送出（同一 folderId 去重）。
- `DWELL_TIME`：離開頁面時計算並送出，包含 `dwellTime` 與 `widgetDwellTime`。

注意事項：

- `pageBundle` 為空時，會跳過 `PAGE_VIEW` / `EMBED_VIEW` / `DWELL_TIME`。
- `DWELL_TIME` 只會在停留時間大於 5000ms 時送出。
- 切換頁面時請額外呼叫 `notifyPageDidLeave`，確保停留時間正確結算。

```kotlin
override fun onPause() {
    super.onPause()
    EmbedAndroidSDK.notifyPageDidLeave()
}
```

若你使用 Compose Navigation，建議在頁面離開時呼叫：

```kotlin
DisposableEffect(Unit) {
    onDispose {
        EmbedAndroidSDK.notifyPageDidLeave()
    }
}
```

### Widget 點擊 Callback（給 App 端自訂事件追蹤）

`EmbedWidgetView` 支援 `onClick`，可讓 app 自行記錄 GA / Firebase / 自家追蹤：

```kotlin
EmbedWidgetView(
    pageUrl = pageUrl,
    position = EmbedAndroidSDK.BELOW_BUY_BUTTON,
    onClick = { click ->
        // click.folderId / click.folderName / click.position / click.mediaId / click.url
    }
)
```

欄位說明：

- `folderId`：內容牆 folder id。
- `folderName`：內容牆名稱。
- `position`：版位（`EmbedPosition`）。
- `mediaId`：被點擊素材 id（若 payload 無提供則為 `null`）。
- `url`：點擊對應網址（無值時回退為目前 pageUrl）。

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
