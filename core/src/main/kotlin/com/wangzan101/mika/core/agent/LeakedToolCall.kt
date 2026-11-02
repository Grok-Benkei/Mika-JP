package com.wangzan101.mika.core.agent

/**
 * ツール呼び出しが応答テキストに漏洩しました: 一部の推論サービスはモデルのツール呼び出しの解析に失敗することがあり、呼び出しはテキストとして返されます。
 * このような返信は回答ではないため、ユーザーに引き渡すことはできません。
 *
 * は一般的なツール呼び出しタグのみを認識し、Mika の特定のツール名 (単語全体) がタグの後に表示される必要があります。これらの形式は応答にはカウントされません。
 * は判定のみを行い、テキスト内の呼び出しを解析または実行しません。
 */
internal object LeakedToolCall {
    /** は、モデル作成ツールを呼び出すときによく使用されるマークです。 */
    private val MARKERS = listOf(
        "<tool_call>", "<function=",
        "<|python_tag|>",
        "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
    )

    /**
     * [text] [toolNames] のツールが呼び出されると、ツール名が返されます。そうでない場合は null です。
     * 最初のコール マークの後に表示されるツール名のみを調べ、最初のものを取得します。マークの前に表示され、ツールと同じ名前を持つ wait や find などの単語はカウントされません。
     */
    fun find(text: String, toolNames: Collection<String>): String? {
        val start = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: return null
        return toolNames.filter { it.isNotEmpty() }
            .mapNotNull { name -> wholeWord(name).find(text, start)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    private fun wholeWord(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
}
