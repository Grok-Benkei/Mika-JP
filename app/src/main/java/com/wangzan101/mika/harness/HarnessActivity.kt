package com.wangzan101.mika.harness

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Local, authenticated official Harness web interface (including Plugins and MCP). */
class HarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Session cookies, launch token, secrets in model/provider settings.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val runtime = HarnessRuntime.get(this)
        setContent {
            MaterialTheme {
                val current by runtime.state.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { finish() }) { Text("戻る") }
                        Text("DeepSeek Harness", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        Spacer(Modifier.weight(1f))
                        when (current.phase) {
                            HarnessRuntime.Phase.NOT_INSTALLED, HarnessRuntime.Phase.FAILED -> {
                                Button(onClick = {
                                    if (runtime.installed()) runtime.start() else runtime.install()
                                }) { Text(if (runtime.installed()) "再起動" else "導入") }
                            }
                            HarnessRuntime.Phase.INSTALLED ->
                                Button(onClick = { runtime.start() }) { Text("起動") }
                            HarnessRuntime.Phase.RUNNING ->
                                OutlinedButton(onClick = { runtime.stop() }) { Text("停止") }
                            else -> CircularProgressIndicator()
                        }
                    }
                    Text(current.description,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall)
                    if (current.phase == HarnessRuntime.Phase.NOT_INSTALLED) {
                        Text("初回に約153MBをダウンロードします。1.4GB以上の空き容量が必要です。",
                            Modifier.padding(16.dp))
                        Text("スマホ内でHarnessを実行します。プラグインとMCPは起動後の画面で管理できます。",
                            Modifier.padding(horizontal = 16.dp))
                    }
                    val url = current.launchUrl
                    if (current.phase == HarnessRuntime.Phase.RUNNING && url != null) {
                        key(url) {
                            HarnessWeb(url, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessWeb(launchUrl: String, modifier: Modifier) {
    val context = LocalContext.current
    val expectedPort = remember(launchUrl) { Uri.parse(launchUrl).port }
    val web = remember(launchUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val internal = uri.scheme == "http" &&
                        uri.host in listOf("127.0.0.1", "localhost") && uri.port == expectedPort
                    if (internal) return false
                    // External OAuth and documentation links use the user's regular browser.
                    if (uri.scheme == "https") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }
            }
            loadUrl(launchUrl)
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier.fillMaxWidth())
}
