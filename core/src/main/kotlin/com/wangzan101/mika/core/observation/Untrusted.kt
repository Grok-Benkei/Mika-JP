package com.wangzan101.mika.core.observation

/**
 * は、信頼できないコンテンツ (Web ページのテキストとスクリプトの結果、ネットワーク応答、その他の AI 応答、プラットフォーム内の単語) がモデルに渡された場合の固定マークです。
 * は、この観測を作成してツール結果を書き込むことができる場合に 1 回だけ適用され、その後のトリミングや圧縮には適用されません。マークの意味はシステム プロンプト ワードで 1 回説明されます。
 * テキスト内の終了タグがエスケープされているため、事前にテキストをこのレイヤーに閉じることはできません。台帳に記録されるのは、刻印のない原文です。
 */
object Untrusted {
    const val OPEN = "<untrusted>"
    const val CLOSE = "</untrusted>"
    const val OVERHEAD = 2 + OPEN.length + CLOSE.length

    /** とマークされています。空のものはそのまま返却されます。 */
    fun wrap(text: String): String = if (text.isEmpty()) text else "$OPEN\n" + text.replace(CLOSE, "<\\/untrusted>") + "\n$CLOSE"

    /** ラップ後のフィールド全体は、ツールによって宣言された最大長を満たしている必要があります。 */
    fun wrapWithin(text: String, maxChars: Int): String {
        require(maxChars >= OVERHEAD)
        val escaped = text.replace(CLOSE, "<\\/untrusted>")
        return if (escaped.isEmpty()) escaped else "$OPEN\n${escaped.take(maxChars - OVERHEAD)}\n$CLOSE"
    }
}
