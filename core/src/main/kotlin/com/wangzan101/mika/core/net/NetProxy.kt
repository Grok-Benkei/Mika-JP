package com.wangzan101.mika.core.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.net.Authenticator as JavaAuthenticator
import okhttp3.Authenticator as OkAuthenticator

/** ネットワークエージェント使用時に発生するエラーは、サービスプロバイダー側​​のエラーとは別のものです。 */
enum class ProxyFailure {
    /** プロキシに接続できないか、プロキシがリクエストを転送しません。 */
    Unreachable,
    /** エージェントはアカウントとパスワードを要求しましたが、指定されたものが間違っていました (または指定されませんでした)。 */
    AuthFailed,
}

/**
 * ミカ独自のネットワーク接続 (モデル調整、列モデル、トークン交換のためのアカウント ログイン、更新、内蔵ブラウザのダウンロード) ネットワーク プロキシ。
 * [source] 現在保存されているプロキシ アドレスを指定します (空の文字列は使用されません)。元のテキストに従って解析結果をキャッシュし、設定を変更した後、[changed] を調整します。
 *
 * - [client] によって構築された OkHttp クライアントは、リクエストごとに [selector] に要求します。ローカル マシンと LAN アドレス ([LocalHosts.bypassesProxy]) は直接接続され、その他はプロキシを経由します。
 *   にプロキシがない場合、またはプロキシが適用されない場合 ([ProxyAddress.forApp] が false) の直接接続。ストリーミング応答は、バッファリングせずに引き続き部分的に読み取られます。
 * - HTTP プロキシのアカウントとパスワードは、プロキシから要求されたとき (トンネルを構築する前に) `Proxy-Authorization` で指定する必要があります。同じリクエストが拒否された場合、再試行されません。
 * - SOCKS エージェントのアカウント パスワードは、プロセス全体でインストールされた [java.net.Authenticator] のみです。つまり、[installSocksAuth] によってインストールされたものです。
 *   は、現在の SOCKS プロキシに送信されたクエリにのみ応答し (ホストとポートは両方とも正しい)、その他には応答しません。
 */
class NetProxy(private val source: () -> String) {
    private class Parsed(val raw: String, val address: ProxyAddress?)

    @Volatile private var parsed: Parsed? = null
    private val pools = CopyOnWriteArrayList<WeakReference<okhttp3.ConnectionPool>>()

    /** 現時点でミカ自身の接続が経由するプロキシ。設定されていないか、正しく書かれていない場合、または適用できない場合は null になります。 */
    fun current(): ProxyAddress? {
        val raw = runCatching(source).getOrDefault("")
        parsed?.takeIf { it.raw == raw }?.let { return it.address }
        val address = ProxyAddress.parse(raw)?.takeIf { it.forApp }
        parsed = Parsed(raw, address)
        return address
    }

    /** は、このホストに接続するためのプロキシです。ローカルマシンとLANアドレスは直接接続されています(null)。 */
    fun proxyFor(host: String): ProxyAddress? = current()?.takeUnless { LocalHosts.bypassesProxy(host) }

    val selector: ProxySelector = Selector()

    val authenticator: OkAuthenticator = OkAuthenticator { route, response -> authenticate(route, response) }

    private fun authenticate(route: Route?, response: Response): Request? {
        // アカウントとパスワードを入力した後も拒否されました。これ以上試行することはできず、発信者は「エージェントのアカウントまたはパスワードが間違っています」と報告しました。
        if (response.request.header(PROXY_AUTHORIZATION) != null) return null
        val proxy = current() ?: return null
        if (proxy.socks || !proxy.hasCredentials) return null
        if (route != null && route.proxy.type() != Proxy.Type.HTTP) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, Credentials.basic(proxy.user, proxy.password)).build()
    }

    /** [builder] を押して、ここでプロキシ設定を使用してクライアントを構築します。設定が変更されると、その接続プールはアイドル状態の接続をクリアします。 */
    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder.proxySelector(selector).proxyAuthenticator(authenticator).build().also { pools += WeakReference(it.connectionPool) }

    /** 設定が変更されました: 再度解析し、アイドル状態の接続はすべて切断され、次のリクエストは新しい設定に従って接続されます (進行中のリクエストは引き続き完了します)。 */
    fun changed() {
        parsed = null
        pools.removeAll { it.get() == null }
        pools.forEach { it.get()?.evictAll() }
    }

    /** SOCKS エージェントのアカウント パスワード: 現在の SOCKS エージェントに送信された問い合わせにのみ回答してください。 */
    val socksAuthenticator: JavaAuthenticator = object : JavaAuthenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            socksCredentials(requestingProtocol, requestingHost, requestingPort)
    }

    internal fun socksCredentials(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        val proxy = current() ?: return null
        if (!proxy.socks || !proxy.hasCredentials) return null
        if (protocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (host == null || !host.removeSurrounding("[", "]").equals(proxy.host, ignoreCase = true) || port != proxy.port) return null
        return PasswordAuthentication(proxy.user, proxy.password.toCharArray())
    }

    /** は [socksAuthenticator] にインストールされます (プロセス全体に 1 つ)。アプリの起動時に一度調整されます。 */
    fun installSocksAuth() = JavaAuthenticator.setDefault(socksAuthenticator)

    private inner class Selector : ProxySelector() {
        val owner: NetProxy get() = this@NetProxy

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyFor(host)?.javaProxy() ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    companion object {
        private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

        /** 製品のもの: アプリが起動したら、[sharedSource] を保存された設定に接続します。 */
        @Volatile var sharedSource: () -> String = { "" }
        val shared: NetProxy = NetProxy { sharedSource() }

        /** は、[client] によって構築されたクライアントに対応するコピーです。他のクライアントは null です。 */
        fun of(http: OkHttpClient): NetProxy? = (http.proxySelector as? Selector)?.owner

        /** この失敗はエージェントに問題がありますか? [http] は、[url] に接続するときにエージェントを失いました。エラーは、エージェントへの接続とエージェントの通過の部分にあります。 */
        fun failure(http: OkHttpClient, url: HttpUrl, e: IOException): ProxyFailure? {
            of(http)?.proxyFor(url.host) ?: return null
            return classify(e)
        }

        /**
         * エージェント使用時のネットワーク エラーのカテゴリ: エージェントはアカウントとパスワード [ProxyFailure.AuthFailed] を拒否しました。エージェントに接続できない (接続の拒否、ホストが見つからない、接続タイムアウト)、
         * プロキシ トンネルの確立に失敗し、SOCKS ハンドシェイクが正しく計算されませんでした。 [ProxyFailure.Unreachable];返信を読む際に中断されるなどの問題は代理としてカウントされません（null）。
         */
        fun classify(e: IOException): ProxyFailure? {
            val chain = generateSequence(e as Throwable) { it.cause }.take(6).toList()
            val messages = chain.map { it.message.orEmpty() }
            if (messages.any { it.contains("authenticate with proxy", true) || (it.contains("SOCKS", true) && it.contains("auth", true)) }) {
                return ProxyFailure.AuthFailed
            }
            if (messages.any { it.contains("for CONNECT", true) || it.contains("SOCKS", true) }) return ProxyFailure.Unreachable
            val connecting = chain.any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                    (it is SocketTimeoutException && it.message.orEmpty().contains("connect", true))
            }
            return if (connecting) ProxyFailure.Unreachable else null
        }
    }
}
