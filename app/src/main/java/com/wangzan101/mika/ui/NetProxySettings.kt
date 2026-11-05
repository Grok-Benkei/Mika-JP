package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.ProxyAddress
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 設定 › ネットワーク プロキシのコピーライティング (純粋な機能、テストが簡単)。アドレスにはプロトコル、ホスト、ポートのみが含まれ、アカウントとパスワードは含まれません (この行はいつでも表示されます)。
 * は設定のホームページのみを変更しました。ファイルとコンピューティング環境のページの行は引用されているだけです。
 */
object NetProxyCopy {
    val TITLE: String get() = tr(R.string.net_proxy)
    val HINT: String get() = tr(R.string.net_proxy_hint)
    val INVALID: String get() = tr(R.string.net_proxy_invalid)

    /** ホームページ行の右側を設定します。「未設定」は設定されていませんが、アドレスは設定されています。 */
    fun value(proxy: String): String = NetProxyStore.display(proxy).ifEmpty { tr(R.string.settings_not_set) }

    /**
     * 行の下の説明: 通常、どの接続を制御するかを示します。コンテナ専用に書かれている場合、ブラウザがそれを使用できない場合は、正直に言ってください。
     * [browser] 空の場合はブラウザ側の状況がまだ見つかっていないことを意味します。
     */
    fun subtitle(proxy: String, browser: WebProxy.Mode?): String {
        val address = ProxyAddress.parse(proxy)
        return when {
            address != null && !address.forApp -> tr(R.string.net_proxy_container_only)
            browser == WebProxy.Mode.Unsupported -> tr(R.string.net_proxy_browser_unsupported)
            browser == WebProxy.Mode.SocksWithCredentials -> tr(R.string.net_proxy_browser_socks_auth)
            else -> tr(R.string.net_proxy_row_sub)
        }
    }
}

/** ネットワーク プロキシを変更します: 入力ボックス。空のままにすることは、それを使用しないことを意味します。何かが間違って書かれている場合は、その書き方を教えてくれますが、それは保存されません。 */
@Composable
fun NetProxyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val ok = NetProxyStore.valid(text)
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(NetProxyCopy.TITLE) },
        text = {
            Column {
                Text(NetProxyCopy.HINT, style = MikaType.Caption, color = MikaTheme.colors.ink3)
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("net-proxy-input"),
                    singleLine = true, isError = !ok, placeholder = { Text("http://127.0.0.1:7890") },
                )
                if (!ok) Text(NetProxyCopy.INVALID, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton({ onSave(text) }, enabled = ok) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
