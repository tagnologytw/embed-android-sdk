package co.tagnology.embed.demo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * Stands in for a host app's native product page (91APP's toNavigator()), to try
 * EmbedAndroidSDK.setLinkHandler. Enabled by launching MainActivity with
 * `--ez nativeProductLinks true`.
 */
class NativeProductActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID).orEmpty()
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("原生商品頁", fontSize = 28.sp)
                Text("SalePage $productId", fontSize = 20.sp)
            }
        }
    }

    companion object {
        private const val EXTRA_PRODUCT_ID = "productId"

        /** Opens SalePage/Index/{id} links natively; returns false for anything else. */
        fun open(context: Context, url: String): Boolean {
            val segments = Uri.parse(url).pathSegments
            val i = segments.indexOf("Index")
            if (i < 1 || segments[i - 1] != "SalePage" || i + 1 >= segments.size) {
                Log.d("NativeLinkRouter", "not handled url=$url")
                return false
            }
            Log.d("NativeLinkRouter", "native product page id=${segments[i + 1]} url=$url")
            context.startActivity(
                Intent(context, NativeProductActivity::class.java)
                    .putExtra(EXTRA_PRODUCT_ID, segments[i + 1])
            )
            return true
        }
    }
}
