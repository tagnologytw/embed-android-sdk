package co.tagnology.embed.demo

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import co.tagnology.embed.sdk.EmbedAndroidSDK

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        // adb shell am start -n co.tagnology.embed.demo/.MainActivity --ez nativeProductLinks true
        if (intent.getBooleanExtra("nativeProductLinks", false)) {
            EmbedAndroidSDK.setLinkClickHandler { click ->
                android.util.Log.d("EmbedDemo", "link trackingUrl=${click.trackingUrl}")
                NativeProductActivity.open(this, click.url)
            }
        }
        setContent { EcommerceDemoPage() }
    }
}
