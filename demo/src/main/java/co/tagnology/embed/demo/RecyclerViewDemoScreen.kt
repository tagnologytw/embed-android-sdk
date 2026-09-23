package co.tagnology.embed.demo

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.tagnology.embed.sdk.EmbedAndroidSDK
import co.tagnology.embed.sdk.EmbedPosition
import co.tagnology.embed.sdk.EmbedWidgetView

/**
 * 模擬 91APP 的宿主結構：商品頁是 RecyclerView，內容牆 widget 放在
 * RecyclerView cell 內的 ComposeView（DisposeOnViewTreeLifecycleDestroyed）。
 *
 * 重現步驟：
 * 1. 往下捲動，讓 widget cell 完全離開畫面（進入 RecycledViewPool，view 已 detach）。
 * 2. 按「在回收池中重組 widget」：cell 的 composition 仍存活，widget 會在 detached
 *    狀態下重新進入 composition（等同客戶端 cell 被回收後重綁）。
 * 3. 捲回 widget，點擊任一貼文。修正前 lightbox 會被塞進 cell 內；修正後應全螢幕。
 */
@Composable
fun RecyclerViewDemoScreen(
    onBackToProduct: () -> Unit,
) {
    val pageUrl = "https://partnertest4.91app.com/SalePage/Index/8778110"
    val mid = "41458"
    val secret = "P5Sayl2krqbPV8ORsekcSDoWFUEiurKW2WMbm62b5Cs="

    val initialized = remember { mutableStateOf(false) }
    val recomposeKey = remember { mutableStateOf(0) }
    var initMessage by remember { mutableStateOf("初始化中…") }

    LaunchedEffect(Unit) {
        val error = EmbedAndroidSDK.initialize(
            pageUrl = pageUrl,
            mid = mid,
            secret = secret,
            forceRefresh = true,
        )
        if (error == null) {
            initialized.value = true
            initMessage = "初始化成功"
        } else {
            initMessage = "初始化失敗 statusCode=${error.statusCode} message=${error.message}"
        }
    }
    DisposableEffect(Unit) {
        onDispose { EmbedAndroidSDK.notifyPageDidLeave() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F5F7)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SectionCard(
                title = "RecyclerView 宿主測試",
                subtitle = "$initMessage\n捲到 widget 看不到後按「在回收池中重組 widget」，再捲回來點貼文。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBackToProduct) { Text("返回") }
                    Button(onClick = {
                        recomposeKey.value += 1
                        Log.d("EmbedDemo", "[recycler] recomposeKey=${recomposeKey.value}")
                    }) { Text("在回收池中重組 widget (${recomposeKey.value})") }
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    RecyclerView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        layoutManager = LinearLayoutManager(context)
                        adapter = DemoListAdapter(
                            pageUrl = pageUrl,
                            initialized = initialized,
                            recomposeKey = recomposeKey,
                        )
                    }
                },
            )
        }
    }
}

private sealed class DemoListItem {
    data class Card(val title: String, val subtitle: String) : DemoListItem()
    data class Widget(val position: EmbedPosition) : DemoListItem()
}

private class DemoListAdapter(
    private val pageUrl: String,
    private val initialized: MutableState<Boolean>,
    private val recomposeKey: MutableState<Int>,
) : RecyclerView.Adapter<DemoListAdapter.ComposeHolder>() {

    // 與一般 App 相同：itemView 是原生容器（FrameLayout），ComposeView 是它的子 view。
    class ComposeHolder(itemView: FrameLayout, val composeView: ComposeView) : RecyclerView.ViewHolder(itemView)

    private val items: List<DemoListItem> = buildList {
        add(DemoListItem.Card("主視覺 Banner", "RecyclerView item 0"))
        add(DemoListItem.Card("Crash Baggage 登機箱", "指定色限時折扣 NT\$6,280"))
        add(DemoListItem.Widget(EmbedAndroidSDK.BELOW_BUY_BUTTON))
        repeat(8) { add(DemoListItem.Card("商品詳細資訊 #$it", "填充內容，讓 widget cell 能被捲出畫面")) }
        add(DemoListItem.Widget(EmbedAndroidSDK.ABOVE_RECOMMENDATION))
        repeat(20) { add(DemoListItem.Card("相關推薦 #$it", "填充內容")) }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DemoListItem.Card -> 0
        is DemoListItem.Widget -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComposeHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 91APP 原本採用的策略：cell 進入回收池後 composition 仍存活。
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        val itemView = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(composeView)
        }
        return ComposeHolder(itemView, composeView)
    }

    override fun onBindViewHolder(holder: ComposeHolder, position: Int) {
        when (val item = items[position]) {
            is DemoListItem.Card -> holder.composeView.setContent {
                SectionCard(title = item.title, subtitle = item.subtitle)
            }
            is DemoListItem.Widget -> holder.composeView.setContent {
                val generation = recomposeKey.value
                // key 改變 -> EmbedWidgetView 離開並重新進入 composition，
                // 若此時 cell 在回收池內，DisposableEffect 會在 detached 狀態下執行。
                key(generation) {
                    if (initialized.value) {
                        EmbedWidgetView(
                            pageUrl = pageUrl,
                            position = item.position,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            onError = { err ->
                                Log.d("EmbedDemo", "[recycler onError] position=${item.position} statusCode=${err.statusCode} message=${err.message}")
                            },
                            onClick = { click ->
                                Log.d("EmbedDemo", "[recycler onClick] folderId=${click.folderId} position=${click.position} url=${click.url}")
                            },
                            onEvent = { event ->
                                Log.d("EmbedDemo", "[recycler onEvent] type=${event.type}")
                            },
                        )
                    }
                }
            }
        }
    }
}
