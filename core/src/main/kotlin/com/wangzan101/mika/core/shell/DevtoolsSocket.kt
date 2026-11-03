package com.wangzan101.mika.core.shell

/**
 * WebView デバッグ インターフェイスのローカル抽象ソケット: デバッグが有効になっているプロセスごとに 1 つ、`webview_devtools_remote_<pid>` という名前。
 * の組み込みアシスタントは、接続時にこのような名前のみを認識し、pid はミカの uid に属している必要があります。
 */
object DevtoolsSocket {
    private const val PREFIX = "webview_devtools_remote_"
    private val NAME = Regex("""webview_devtools_remote_([1-9][0-9]{0,9})""")

    fun name(pid: Int): String = "$PREFIX$pid"

    /** 名の pid はこのようなものではなく (追加のサフィックス、先頭の 0、範囲外)、null を返します。 */
    fun pidOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** `/proc/<pid>/status` の実際の uid (Uid 行の最初の列)。そのような行がない場合は、null が返されます。 */
    fun procUid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
}
