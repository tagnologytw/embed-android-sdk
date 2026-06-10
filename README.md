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

## JitPack 發版

本專案已加入 `jitpack.yml`，使用 JDK 17 建置 SDK module。

### 發版步驟（v1.0.2）

```bash
git checkout main
git pull --ff-only origin main
git tag v1.0.2
git push origin v1.0.2
```

到 JitPack 確認建置：

- `https://jitpack.io/#tagnologytw/embed-android-sdk/v1.0.2`

### 使用方式

```kotlin
// settings.gradle(.kts) / project repositories
maven { url = uri("https://jitpack.io") }
```

```kotlin
// app module dependency
implementation("com.github.tagnologytw.embed-android-sdk:embed-android-sdk:v1.0.2")
```

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        onError = { err ->
            // err.statusCode / err.message / err.position
            // 建議：204（該版位無資料）時隱藏區塊
        },
        onClick = { click ->
            // 官方建議：在此串接 GA / Firebase / 自家事件
            // click.folderId / click.folderName / click.position / click.mediaId / click.url
        },
        onEvent = { event ->
            // event.type / event.payloadJson
            // 支援接收 iframe / lightbox postMessage 事件
        }
    )
}
```

若使用上述 `modifier` 寫法，請確認已引入：
`import androidx.compose.ui.Modifier`、`import androidx.compose.foundation.layout.fillMaxWidth`、`import androidx.compose.foundation.layout.padding`、`import androidx.compose.ui.unit.dp`。

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

## WebView 安全設定與行為說明

以下為 SDK 目前在 `EmbedWidgetView` / Lightbox WebView 的實際設定：

### 1) JavaScript 與 Bridge

- `javaScriptEnabled = true`
- 透過 `addJavascriptInterface` 註冊兩個 bridge：
  - `tagnologyResize`：回報高度（`postHeight`）
  - `tagnologyEvent`：回報事件（`postEvent` / `postMessage`）
- Bridge method 均有 `@JavascriptInterface` 註記，且僅開放 SDK 需要的最小方法集合。

### 2) Mixed Content

- `mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE`
- 目的：提升第三方嵌入內容在不同來源下的相容性。
- 建議：正式環境仍以 HTTPS 資源為主，避免載入不必要的 HTTP 內容。

### 3) Cookie 與 User-Agent（UA）

- SDK 目前「不主動覆寫」WebView User-Agent。
- SDK 目前「不主動寫入/清除」Cookie，沿用系統 WebView 與 App 全域 `CookieManager` 行為。
- 若 App 有全域修改 UA 或 Cookie policy，SDK WebView 會受同一套全域設定影響。

### 4) 其他相關設定

- `domStorageEnabled = true`
- `loadsImagesAutomatically = true`
- Widget WebView：`mediaPlaybackRequiresUserGesture = true`
- Lightbox WebView：`mediaPlaybackRequiresUserGesture = false`（支援全螢幕互動場景）

### 5) App 端整合建議

1. 若有資安規範，請將 `embed.tagnology.co` 納入允許清單並優先使用 HTTPS。
2. 避免在 App 全域注入過度寬鬆的 WebView 設定（例如任意放寬 Cookie/UA 政策）。
3. 發生 WebView 相容性問題時，優先檢查 App 是否覆寫全域 WebView/Cookie/UA 設定。

### 6) 建議安全基線（App 端可再強化）

以下保留最重要 4 項，且目前專案已實作：

1. 關閉檔案/內容存取：
   - `allowFileAccess = false`
   - `allowContentAccess = false`
   - `allowFileAccessFromFileURLs = false`
   - `allowUniversalAccessFromFileURLs = false`
2. 啟用 Safe Browsing（API 26+）：
   - `safeBrowsingEnabled = true`
3. WebView 偵錯採 build type 控制：
   - `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`（demo 已實作）
4. 禁用明文流量：
   - `android:usesCleartextTraffic="false"`（demo 已實作）

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
