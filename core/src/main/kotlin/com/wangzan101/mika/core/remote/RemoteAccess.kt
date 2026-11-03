package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.McpRpc
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * コンピューター接続ドア: 各リクエストは最初にここを通過し、次にプロトコル層に渡されます。固定順序:
 * ブラウザ (Origin を使用) は常に 403 を取得します。同じアドレス 429 に対して認証が何度も失敗しました。アクセス トークンが正しくありません、401。ルーティングとエラーのシリアル化はイングレス アダプターに任せられます。
 * トークンは一定時間に従って比較されます。失敗の数はリクエストのソース ([HttpRequest.source]) に従って記録され、[clock] は交換可能で、時刻はテストでダイヤルされます。
 * 冷却期間中は正しいトークンは解放されません。トークンを解放することは、推測者に「今回は正しく推測しました」と伝えることと同じです。クールダウンしてもトークンの推測を止めることはできません。
 * ゲートウェイによって転送されたリクエストは、ゲートウェイが認識する発信者に従って個別に記録されます。匿名の発信者が間違ったことを推測しても、冷めるばかりです。
 * [token] はどこからの接続も認識します。 [loopbackToken] (USB 経由のコンピュータ接続に使用されるもの) は、ローカル マシンからループバックされた接続のみを認識します。
 */
class RemoteAccess(
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 60_000,
    private val loopbackToken: () -> String? = { null },
) {
    sealed interface Verdict {
        data object Pass : Verdict
        data class Reject(val status: Int, val message: String, val headers: Map<String, String> = emptyMap()) : Verdict
    }

    private class Failures(var count: Int, var since: Long, var lockedUntil: Long)
    private val failures = HashMap<String, Failures>()

    fun check(req: HttpRequest): Verdict {
        // ブラウザのリクエストには常に Origin が含まれます。コンピュータ上の MCP クライアントはサポートしません。 Web ページはこのポートにアクセスできないようにする必要があります (DNS 再バインド防止)
        if (req.header("origin") != null) return Verdict.Reject(403, "Browser requests are not allowed")
        val who = req.source
        val now = clock()
        synchronized(failures) {
            val f = failures[who]
            if (f != null && f.lockedUntil > now) {
                val wait = ((f.lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                return Verdict.Reject(429, "Too many failed attempts", mapOf("Retry-After" to wait.toString()))
            }
        }
        val given = req.header("authorization")?.let { h ->
            if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else null
        }
        val loopback = if (req.remote.isLoopbackAddress) loopbackToken() else null
        // 両方を比較してください。最初の試合とマッチしたからといって、対戦回数が 1 回減るわけではありません。
        if (given == null || !(matches(given, token()) or matches(given, loopback))) {
            fail(who, now)
            return Verdict.Reject(401, "Missing or wrong access token", mapOf("WWW-Authenticate" to "Bearer"))
        }
        synchronized(failures) { failures.remove(who) }

        return Verdict.Pass
    }

    /** ローカル監視とリモート転送は認証とプロトコル エラーを共有します。通過して初めてタスク層に入ります。 */
    fun handle(req: HttpRequest, next: (HttpRequest) -> HttpResponse): HttpResponse = when (val verdict = check(req)) {
        is Verdict.Reject -> if (req.path == McpRpc.PATH)
            McpRpc.transportError(verdict.status, verdict.message, verdict.headers)
        else RemoteTaskApi.error(verdict.status, "access_denied", verdict.message, verdict.headers)
        Verdict.Pass -> next(req)
    }

    private fun matches(given: String, expected: String?) = !expected.isNullOrEmpty() && same(given, expected)

    private fun fail(who: String, now: Long) = synchronized(failures) {
        val f = failures.getOrPut(who) { Failures(0, now, 0) }
        if (now - f.since > windowMs) { f.count = 0; f.since = now }
        f.count++
        if (f.count >= maxFailures) { f.lockedUntil = now + lockMs; f.count = 0; f.since = now }
        // アドレスが多すぎる場合は、最新のアドレスのみが保持され、テーブルは無限に長くなるわけではありません。
        if (failures.size > 256) failures.entries.minByOrNull { it.value.since }?.let { failures.remove(it.key) }
    }

    companion object {
        /** 定数時間比較: 最初にそれらを等しい長さにダイジェストし、次にバイトごとに比較します。前に直面したキャラクターの数によって消費時間は変わりません。 */
        fun same(a: String, b: String): Boolean {
            val d = MessageDigest.getInstance("SHA-256")
            val x = d.digest(a.toByteArray(Charsets.UTF_8))
            val y = d.digest(b.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(x, y)
        }

        /**
         * このアドレスからの接続を受け入れるかどうか: ローカル ループバック、LAN プライベート セグメント (10/8、172.16/12、192.168/16)、
         * プライベート ネットワーク用の一般的なキャリア グレード NAT セグメント (100.64/10)、IPv4 リンクローカル、IPv6 のみのローカル (fc00::/7) およびリンクローカル (fe80::/10)。
         * パブリック ネットワーク アドレス (モバイル データを使用する場合など) は受け入れられません。
         */
        fun privateAddress(a: InetAddress): Boolean {
            val v4 = when (a) {
                is Inet4Address -> a.address
                is Inet6Address -> a.address.let { b ->
                    // IPv4 は IPv6 (::ffff:a.b.c.d) にマッピングされます。IPv4 を参照してください。
                    if ((0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) b.copyOfRange(12, 16) else null
                }
                else -> null
            }
            if (v4 != null) {
                val o0 = v4[0].toInt() and 0xff
                val o1 = v4[1].toInt() and 0xff
                return o0 == 127 || o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) ||
                    (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
            }
            if (a.isLoopbackAddress) return true
            val b0 = a.address[0].toInt() and 0xff
            val b1 = a.address[1].toInt() and 0xff
            return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
        }
    }
}
