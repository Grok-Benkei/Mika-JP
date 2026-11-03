package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import java.net.InetAddress
import java.net.InetSocketAddress

/** 設定ページに少し動きが表示されます。最近接続されたクライアントの数と、最後のリクエストがいつだったかが表示されます。 */
data class RemoteActivity(val recentClients: Int = 0, val lastRequestAtMs: Long? = null)

/**
 * はコンピュータ接続の複合入口です。HTTP 監視と認証を共有し、共通タスク API と MCP が同じ [RemoteTaskService] に並列接続されます。
 * は [port] のみをリッスンします。 [allowAllInterfaces] が false の場合、ローカル ループバック 127.0.0.1 (USB コンピュータ経由で接続およびテスト済み) のみをバインドし、true の場合、すべてのネットワーク カードをバインドします。
 * ただし、両端（LAN、プライベートネットワーク）のプライベートアドレスでの接続のみ受け付け、パブリックネットワークからの接続はその場で切断します。
 * [token] は、どこからの接続でも認識します。 [loopbackToken] は、このマシンからのループバックのみを認識します (コンピューターは `adb forward` 経由で受信し、ループバックします)。
 */
class RemoteHost(
    tasks: RemoteTasks,
    private val token: () -> String?,
    private val port: Int,
    private val allowAllInterfaces: Boolean,
    serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
    device: DeviceAccess? = null,
    private val endpoints: RemoteEndpoints = RemoteEndpoints(tasks, serverVersion, clock, device),
) {
    private val access = RemoteAccess(token, clock, loopbackToken = loopbackToken)
    val activity get() = endpoints.activity
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse = access.handle(req, endpoints::handle)

    /** がリスニングを開始します。ポートが占有されてバインディングが失敗すると、例外がスローされます。 */
    fun start() {
        val s = MiniHttpServer(bindAddress(allowAllInterfaces, port), ::handle, accept = { local, remote ->
            RemoteAccess.privateAddress(local) && RemoteAccess.privateAddress(remote)
        }, onError = onError)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L

        /**
         * リスニング アドレス: すべてのネットワーク カード、または IPv4 ループバック 127.0.0.1 のみ。 IPv4 にハードコーディングされたループバック: Android 上の `InetAddress.getLoopbackAddress()` は ::1 を与える可能性があります。
         * と `adb forward` は 127.0.0.1 に接続されています。
         */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            if (allowAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }
}
