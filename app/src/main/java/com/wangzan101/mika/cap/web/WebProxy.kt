package com.wangzan101.mika.cap.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress

/**
 * 内蔵ブラウザのネットワーク プロキシ (このプロセスのすべての WebView) は、[設定] › [ネットワーク プロキシ] と同じアドレスを取得します。
 * Android の WebView は、「プロキシ オーバーライド」(androidx.webkit の [ProxyController]) をサポートしている場合にのみ、アプリに別のプロキシを設定できます。
 * がサポートされていない場合、またはプロキシがアカウントとパスワードを備えた SOCKS である場合 (ブラウザー カーネルは SOCKS 認証をサポートしていません)、ブラウザーは直接接続し、そのことが設定ページに記載されます。
 * ローカル エリア ネットワーク アドレスはまだ直接接続されています。 HTTP プロキシがアカウントとパスワードを要求すると、[credentialsFor] が応答します (`WebTab` を参照)。
 */
object WebProxy {
    /** ブラウザ側の現在の状況。 */
    enum class Mode {
        /** にはプロキシがありません (またはプロキシはコンテナによってのみ使用されます): 直接接続。 */
        Off,
        /** ブラウザはプロキシを使用します。 */
        Applied,
        /** この携帯電話の WebView は、個別のプロキシ: 直接接続をサポートしていません。 */
        Unsupported,
        /** アカウントとパスワードを持つ SOCKS プロキシ ブラウザは使用できません: 直接接続。 */
        SocksWithCredentials,
    }

    private const val TAG = "MikaWebProxy"
    @Volatile private var overridden = false

    /** は、現在の設定に従ってブラウザがどのように接続するかを決定します (純粋な機能、テストが簡単)。 */
    fun mode(address: ProxyAddress?, supported: Boolean): Mode = when {
        address == null -> Mode.Off
        !supported -> Mode.Unsupported
        address.socks && address.hasCredentials -> Mode.SocksWithCredentials
        else -> Mode.Applied
    }

    /** ブラウザ カーネルによって認識されるプロキシ ルールの記述方法。一部の SOCKS カーネルは `socks://` のみを認識します。最初に `socks5://` を試してください。 */
    fun rules(address: ProxyAddress): List<String> {
        val host = if (address.host.contains(':')) "[${address.host}]" else address.host
        return if (address.socks) listOf("socks5://$host:${address.port}", "socks://$host:${address.port}")
        else listOf("http://$host:${address.port}")
    }

    /** WebView は、別個のプロキシの設定をサポートしていません。クエリ自体が WebView をロードし、バックグラウンド スレッドに置きます。 */
    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** 現時点でのブラウザ側の状況（調整のためWebViewをロードしてバックグラウンドスレッドに配置します）。 */
    fun mode(): Mode = NetProxy.shared.current().let { mode(it, it != null && supported()) }

    /**
     * 現在の設定に従って、このプロセスの WebView のプロキシをセットアップ (または削除) します。プロキシもプロキシも設定されていない場合は、何も実行しません (このために WebView はロードされません)。
     * はメインスレッドで実行されます。どのスレッドからでも呼び出すことができます。
     */
    fun apply(context: Context) {
        val address = NetProxy.shared.current()
        if (address == null && !overridden) return
        Handler(Looper.getMainLooper()).post {
            runCatching { applyNow(address) }.onFailure { Log.w(TAG, "proxy override failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun applyNow(address: ProxyAddress?) {
        if (!supported()) return
        val controller = ProxyController.getInstance()
        if (mode(address, true) != Mode.Applied || address == null) {
            if (overridden) controller.clearProxyOverride({ it.run() }, {})
            overridden = false
            return
        }
        for (rule in rules(address)) {
            val config = ProxyConfig.Builder().addProxyRule(rule).bypassSimpleHostnames().apply {
                LocalHosts.BYPASS_RULES.forEach { addBypassRule(it) }
            }.build()
            val ok = runCatching { controller.setProxyOverride(config, { it.run() }, {}) }.isSuccess
            if (ok) {
                overridden = true
                return
            }
        }
    }

    /** ブラウザーがアカウントとパスワードを要求すると、現在の HTTP プロキシが質問に答えますが、他のホスト (Web サイト自体のログイン ボックス) は答えません。 */
    fun credentialsFor(host: String?): Pair<String, String>? {
        val p = NetProxy.shared.current() ?: return null
        if (p.socks || !p.hasCredentials || host == null) return null
        if (!host.removeSurrounding("[", "]").equals(p.host, ignoreCase = true)) return null
        return p.user to p.password
    }
}

/** 主文書のプロキシ認証には最大3回応答し、次のナビゲーションで回数を戻します。メインスレッド専用です。 */
internal class WebProxyAuth {
    private var answers = 0

    fun onPageStarted() { answers = 0 }

    fun credentialsFor(host: String?): Pair<String, String>? {
        val credentials = WebProxy.credentialsFor(host) ?: return null
        if (answers >= 3) return null
        answers++
        return credentials
    }
}
