package co.tagnology.embed.demo

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.tagnology.embed.sdk.EmbedAndroidSDK
import co.tagnology.embed.sdk.EmbedPosition
import co.tagnology.embed.sdk.EmbedWidgetClick
import co.tagnology.embed.sdk.EmbedWidgetEvent
import co.tagnology.embed.sdk.EmbedWidgetItem
import co.tagnology.embed.sdk.EmbedWidgetLoadError
import co.tagnology.embed.sdk.EmbedWidgetView

private enum class DemoScreen {
    PRODUCT,
    OTHER,
}

@Composable
fun EcommerceDemoPage() {
    var screen by remember { mutableStateOf(DemoScreen.PRODUCT) }
    when (screen) {
        DemoScreen.PRODUCT -> ProductDemoScreen(
            onGoOtherPage = {
                EmbedAndroidSDK.notifyPageDidLeave()
                screen = DemoScreen.OTHER
            }
        )
        DemoScreen.OTHER -> OtherDemoScreen(
            onBackToProduct = {
                screen = DemoScreen.PRODUCT
            }
        )
    }
}

@Composable
private fun ProductDemoScreen(
    onGoOtherPage: () -> Unit,
) {
    val pageUrl = "https://partnertest4.91app.com/SalePage/Index/8778110"
    val mid = "41458"
    val secret = "P5Sayl2krqbPV8ORsekcSDoWFUEiurKW2WMbm62b5Cs="
    val useMockData = false

    var initialized by remember { mutableStateOf(false) }
    var showBelowBuyButton by remember { mutableStateOf(true) }
    var showBelowMainProductInfo by remember { mutableStateOf(true) }
    var showAboveRecommendation by remember { mutableStateOf(true) }
    var showAboveFilter by remember { mutableStateOf(true) }
    val widgetModifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
    val onWidgetClick: (EmbedWidgetClick) -> Unit = { click ->
        Log.d(
            "EmbedDemo",
            "[onClick] folderId=${click.folderId} folderName=${click.folderName} position=${click.position} mediaId=${click.mediaId} url=${click.url}"
        )
    }
    val onWidgetEvent: (EmbedWidgetEvent) -> Unit = { event ->
        Log.d(
            "EmbedDemo",
            "[onEvent] type=${event.type} payload=${event.payloadJson}"
        )
    }

    LaunchedEffect(Unit) {
        if (useMockData) {
            EmbedAndroidSDK.setMockPageBundle(pageUrl, DemoMockData.widgets(pageUrl))
        } else {
            EmbedAndroidSDK.clearMockPageBundle(pageUrl)
        }

        val error = EmbedAndroidSDK.initialize(
            pageUrl = pageUrl,
            mid = mid,
            secret = secret,
            forceRefresh = true,
        )
        initialized = error == null
    }
    DisposableEffect(Unit) {
        onDispose {
            // Fallback for page leave tracking when composable is removed by navigation.
            EmbedAndroidSDK.notifyPageDidLeave()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F5F7)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderCard()
            SectionCard(
                title = "DWELL_TIME 測試",
                subtitle = "停留超過 5 秒後點下方按鈕，切頁時會呼叫 notifyPageDidLeave()"
            ) {
                Button(onClick = onGoOtherPage) { Text("前往其他頁面（結算停留時間）") }
            }
            HeroSection()

            ProductSection()

            if (initialized && showBelowBuyButton) {
                EmbedWidgetView(
                    pageUrl = pageUrl,
                    position = EmbedAndroidSDK.BELOW_BUY_BUTTON,
                    modifier = widgetModifier,
                    onError = { err ->
                        showBelowBuyButton = handleError(err, EmbedPosition.BELOW_BUY_BUTTON)
                    },
                    onClick = onWidgetClick,
                    onEvent = onWidgetEvent,
                )
            }

            DetailSection()

            if (initialized && showBelowMainProductInfo) {
                EmbedWidgetView(
                    pageUrl = pageUrl,
                    position = EmbedAndroidSDK.BELOW_MAIN_PRODUCT_INFO,
                    modifier = widgetModifier,
                    onError = { err ->
                        showBelowMainProductInfo = handleError(err, EmbedPosition.BELOW_MAIN_PRODUCT_INFO)
                    },
                    onClick = onWidgetClick,
                    onEvent = onWidgetEvent,
                )
            }

            if (initialized && showAboveRecommendation) {
                EmbedWidgetView(
                    pageUrl = pageUrl,
                    position = EmbedAndroidSDK.ABOVE_RECOMMENDATION,
                    modifier = widgetModifier,
                    onError = { err ->
                        showAboveRecommendation = handleError(err, EmbedPosition.ABOVE_RECOMMENDATION)
                    },
                    onClick = onWidgetClick,
                    onEvent = onWidgetEvent,
                )
            }

            RecommendationSection()

            FilterSection()

            if (initialized && showAboveFilter) {
                EmbedWidgetView(
                    pageUrl = pageUrl,
                    position = EmbedAndroidSDK.ABOVE_FILTER,
                    modifier = widgetModifier,
                    onError = { err ->
                        showAboveFilter = handleError(err, EmbedPosition.ABOVE_FILTER)
                    },
                    onClick = onWidgetClick,
                    onEvent = onWidgetEvent,
                )
            }

            CategoryListSection()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OtherDemoScreen(
    onBackToProduct: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F5F7)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "其他頁面",
                subtitle = "你已離開商品頁。請在 Logcat 檢查 [EmbedAnalytics] DWELL_TIME。"
            ) {
                Button(onClick = onBackToProduct) { Text("返回商品頁") }
            }
        }
    }
}

private fun handleError(error: EmbedWidgetLoadError, expectedPosition: EmbedPosition): Boolean {
    if (error.position != expectedPosition) return true
    return error.statusCode == 425 || error.statusCode == 428
}

@Composable
private fun HeaderCard() {
    SectionCard(title = "EC Test Demo", subtitle = "Android SDK 版位展示（不含浮窗影音）")
}

@Composable
private fun HeroSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFFECEFF4), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "主視覺 Banner", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProductSection() {
    SectionCard(title = "Crash Baggage 登機箱", subtitle = "指定色限時折扣") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriceTag("NT$6,280")
            PriceTag("免運", background = Color(0xFFFFF3E0))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {}) { Text("加入購物車") }
    }
}

@Composable
private fun DetailSection() {
    SectionCard(title = "商品詳細資訊", subtitle = "航太級 PC 材質，360 度靜音輪，TSA 海關密碼鎖")
}

@Composable
private fun RecommendationSection() {
    SectionCard(title = "相關推薦", subtitle = "你可能也喜歡")
}

@Composable
private fun FilterSection() {
    SectionCard(title = "分類頁過濾器", subtitle = "品牌 / 顏色 / 價格 / 配送")
}

@Composable
private fun CategoryListSection() {
    SectionCard(title = "分類商品列表", subtitle = "共 12 件商品")
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE8EAF0), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5E6673))
        if (content != null) {
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PriceTag(text: String, background: Color = Color(0xFFEFF6FF)) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

private object DemoMockData {
    fun widgets(pageUrl: String): List<EmbedWidgetItem> {
        val style = """
            <style>
            body { margin:0; font-family:-apple-system, BlinkMacSystemFont, sans-serif; background:#fff8cc; }
            .wall { border:3px solid #ff8a00; border-radius:12px; padding:16px; margin:8px 0; background:#fff3b0; min-height:180px; box-sizing:border-box; }
            .title { font-size:20px; font-weight:800; color:#7a2e00; line-height:1.2; }
            .desc { margin-top:10px; font-size:15px; color:#2b2b2b; line-height:1.4; }
            .chip { display:inline-block; margin-top:12px; padding:6px 10px; background:#7a2e00; color:#fff; border-radius:999px; font-size:12px; font-weight:700; }
            </style>
        """.trimIndent()

        fun html(title: String, desc: String): String = """
            $style
            <div class="wall">
              <div class="title">[EMBED WIDGET] $title</div>
              <div class="desc">$desc</div>
              <div class="chip">Tagnology Mock Widget</div>
              <iframe
                title="demo-iframe"
                style="margin-top:12px;width:100%;height:120px;border:2px solid #7a2e00;border-radius:8px;background:#fff;"
                src="data:text/html;charset=utf-8,%3Chtml%3E%3Cbody%20style%3D%27margin%3A0%3Bfont-family%3A-system-ui%2Csans-serif%3Bdisplay%3Aflex%3Balign-items%3Acenter%3Bjustify-content%3Acenter%3Bheight%3A120px%3B%27%3E%3Cdiv%20style%3D%27text-align%3Acenter%3B%27%3E%3Cdiv%20style%3D%27font-size%3A16px%3Bfont-weight%3A700%3B%27%3EIframe%20Content%20Loaded%3C%2Fdiv%3E%3Cdiv%20style%3D%27font-size%3A12px%3Bmargin-top%3A6px%3B%27%3Ethis%20is%20inside%20iframe%3C%2Fdiv%3E%3C%2Fdiv%3E%3C%2Fbody%3E%3C%2Fhtml%3E">
              </iframe>
            </div>
        """.trimIndent()

        return listOf(
            EmbedWidgetItem(
                folderId = "f-101",
                folderName = "加入購物車下方內容牆",
                position = EmbedPosition.BELOW_BUY_BUTTON,
                html = html("口碑牆：開箱精選", "加入購物車前先看 30 秒實測。"),
                clickUrl = pageUrl,
            ),
            EmbedWidgetItem(
                folderId = "f-102",
                folderName = "詳細資訊上方內容牆",
                position = EmbedPosition.BELOW_MAIN_PRODUCT_INFO,
                html = html("口碑牆：材質解析", "真實用戶分享耐用度與容量體驗。"),
                clickUrl = pageUrl,
            ),
            EmbedWidgetItem(
                folderId = "f-103",
                folderName = "相關推薦上方內容牆",
                position = EmbedPosition.ABOVE_RECOMMENDATION,
                html = html("口碑牆：同系列比較", "幫你快速比較 20 吋與 24 吋差異。"),
                clickUrl = pageUrl,
            ),
            EmbedWidgetItem(
                folderId = "f-104",
                folderName = "分類頁過濾器上方內容牆",
                position = EmbedPosition.ABOVE_FILTER,
                html = html("口碑牆：分類導購", "先看熱門分類回購心得再篩選商品。"),
                clickUrl = pageUrl,
            ),
        )
    }
}
