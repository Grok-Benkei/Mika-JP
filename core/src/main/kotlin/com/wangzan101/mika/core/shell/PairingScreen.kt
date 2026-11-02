package com.wangzan101.mika.core.shell

/** システム ペアリング ウィンドウから読み取られたペアリング コードとポート。読み取られない場合、またはあいまいな場合は null。 */
data class PairingScreenReading(val code: String?, val port: Int?)

/** は読み取りコードを使用してペアリングします。 [port] が空の場合、見つかったペアリング ポートが使用されます。 */
data class PairingAttempt(val code: String, val port: Int?)

/**
 * ウィンドウ内のすべてのテキストからペアリング コードと「IP:ポート」を認識します。コントロール ID、インターフェース言語、またはレイアウトではなく、テキストの外観に注目してください。
 * ペアリング コードは、テキスト全体でちょうど 6 桁です (間にスペースが入る場合があります)。ポートは、「IPv4:port」または「[IPv6]:port」のコロンの後の番号です。
 * 2 つの異なるコードまたはポートが同じウィンドウに表示された場合は、それを未読として扱い、ユーザーに手動で入力するように返します。
 */
object PairingScreen {
    private val CODE = Regex("""\d{6}""")
    private val IPV4_PORT = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val IPV6_PORT = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { t -> t.filterNot { it.isWhitespace() }.takeIf { CODE.matches(it) } }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    private fun ports(text: String): List<Int> {
        val v4 = IPV4_PORT.findAll(text)
            .filter { m -> (1..4).all { m.groupValues[it].toInt() <= 255 } }
            .map { it.groupValues[5] }
        val v6 = IPV6_PORT.findAll(text).map { it.groupValues[1] }
        return (v4 + v6).mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }.toList()
    }

    /**
     * ペアリングには読み取りコードを使用する必要がありますか?コードがある、ポートがある (画面上で読み取られた、またはペアリング ウィンドウが見つかった)、このコードは試されていない、
     * そして、アシスタントはペアリングを待っています（ペアリングされていない、準備ができていません）。コードが間違っている場合、同じコードは再度試行されず、ペアリング ウィンドウで新しいコードに置き換えられます。
     */
    fun attempt(reading: PairingScreenReading, portFound: Boolean, status: BuiltinShellStatus, tried: Set<String>): PairingAttempt? {
        val code = reading.code ?: return null
        if (code in tried) return null
        if (reading.port == null && !portFound) return null
        val waiting = when (status) {
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> true
            is BuiltinShellStatus.Failed -> status.reason.duringPairing || status.fix == BuiltinShellFix.RePair
            BuiltinShellStatus.Connecting, BuiltinShellStatus.Running -> false
        }
        return if (waiting) PairingAttempt(code, reading.port) else null
    }
}
