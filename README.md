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
- `../example-project/`：以 JitPack 依賴方式整合的最小範例（`useLocalSdk=true` 時改吃本機 SDK 原始碼）

## 變更記錄

### v1.0.4（2026-09-23）

客戶（91APP）回報三個問題的修正，皆已在模擬器以 RecyclerView 宿主重現並驗證：

1. **Lightbox 被塞進商品列表的 cell 內**（點貼文後 IG 內容變成一小塊被裁切的 WebView、跟著列表捲動）
   - 原因：覆蓋層在 composition 當下用 `hostView.rootView` 找 DecorView；widget 位於 RecyclerView cell 的
     ComposeView 內、cell 進回收池後重新 compose 時 view 未掛在 window 上，`rootView` 變成 cell 本身。
   - 修正：`DeferredOverlayAttachment` 改為掛載當下才解析 root，host 未 attach 就等 `onViewAttachedToWindow`。
   - 測試：`DeferredOverlayAttachmentTest`（detachedHost_* 兩個回歸測試）。demo 新增「RecyclerView 宿主測試」頁可重現。
2. **篩選 / 排序按鈕點不到**
   - 原因：浮窗影音按 X 關閉後 embed 送 `resize {display:none}`，iframe 隱藏但原生 WebView 仍維持
     126x224dp 透明區塊，攔截底下所有觸控。
   - 修正：FloatingMedia 收到高度 ≤ 1 時原生 WebView 收成 1px，再顯示時恢復 224dp。
   - 測試：`FloatingMediaHeightTest`。
3. **影片載入時出現灰色播放鍵**（浮窗與 lightbox）
   - 原因：Chromium 對沒有 poster、尚無第一幀的 `<video>` 畫的預設圖，CSS 無法隱藏；原本的處理只注入
     lightbox，且未進入 iframe，浮窗完全沒被處理。
   - 修正：第一幀可用前把影片透明化並在容器上蓋黑底 + 轉圈 loading；腳本會掃描同源 iframe，注入
     內容牆 / 浮窗 / lightbox 三種 WebView；安全上限 30 秒。
   - 測試：`MediaPlaceholderGuardTest`。
   - 建議：embed 網頁端為影片補上 `poster` 縮圖，這是根本解。

其他：

- `example-project` 依賴升至 v1.0.4，並補上六個角落的浮窗掛載；可用 `useLocalSdk` 切換本機 SDK / JitPack。
- README 補充浮窗用法、收合行為、RecyclerView 宿主注意事項。

### v1.0.3（2026-09-01）

- 新增浮窗影音 FIXED_* 固定版位（對齊 iOS SDK）。
- Lightbox 覆蓋層的 DecorView 操作延後至 layout traversal 之外（修正 91APP 回報的 FrameLayout NPE）。

## JitPack 發版

本專案已加入 `jitpack.yml`，使用 JDK 17 建置 SDK module。

### 發版步驟（v1.0.4）

```bash
git checkout main
git pull --ff-only origin main
git tag v1.0.4
git push origin v1.0.4
```

到 JitPack 確認建置：

- `https://jitpack.io/#tagnologytw/embed-android-sdk/v1.0.4`

### 使用方式

```kotlin
// settings.gradle(.kts) / project repositories
maven { url = uri("https://jitpack.io") }
```

```kotlin
// app module dependency
implementation("com.github.tagnologytw:embed-android-sdk:v1.0.4")
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

## 浮窗影音（FloatingMedia）

浮窗影音不屬於 `embedLocation` 版位，pageBundle 以 `layout=FloatingMedia` + `floatingMediaPosition`
（BottomRight / TopLeft …）描述。App 端必須自行在全頁 `Box` 上以 `FIXED_*` 版位掛 `EmbedWidgetView`，
沒有對應角落的浮窗時 `onError` 會收到 204，請自動隱藏。範例見 `demo` 的 `FloatingMediaOverlays`
與 `example-project` 的 `MainActivity`。

- 對應版位：`FIXED_TOP_LEFT` / `FIXED_TOP_RIGHT` / `FIXED_CENTER_LEFT` / `FIXED_CENTER_RIGHT` / `FIXED_BOTTOM_LEFT` / `FIXED_BOTTOM_RIGHT`
- 建議尺寸：126 x 224 dp
- 需 SDK v1.0.3 以上（收合行為需 v1.0.4）
- 使用者按浮窗的 X 關閉後，embed 會送 `resize {display:none}`，SDK 會把原生 WebView 收成 1px，
  避免透明的 126x224dp 區塊繼續攔截底下 App 元件（例如篩選列）的點擊；embed 再顯示時會自動恢復 224dp。

## Lightbox 與 RecyclerView / ComposeView 宿主

Lightbox 覆蓋層會掛在 Activity 的 DecorView 上。若 `EmbedWidgetView` 放在 RecyclerView cell 內的 ComposeView，
cell 進入回收池後仍可能重新 compose；SDK 會等 view 重新 attach 到 window 後才掛覆蓋層，避免覆蓋層落在 cell 內。
`demo` 的「RecyclerView 宿主測試」頁可重現與驗證此情境。

## 備註

- Demo 商品頁預設連真實後端（`useMockData = false`），可切回 mock pageBundle 驗證版位畫面。
