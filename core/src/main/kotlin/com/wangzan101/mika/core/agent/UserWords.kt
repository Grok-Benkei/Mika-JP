package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.ToolDispatcher

/**
 * ユーザー自身の 1 文の言葉 (開始、中断、フォローアップの質問への回答)。言語プロンプトと回答の言語チェックでは、今回入力した単語 ([typed]) のみが調べられます。
 * 記憶とスキルに関する引用には、彼の発言 ([said]) からの引用も含まれますが、ミカの回答からの引用はカウントされません。
 */
internal object UserWords {
    /**
     * [text] は、画面の説明を削除した後の文です ([UserScreenTurns] を参照)。次に、ブラウザ ページ ([ToolDispatcher.userWords]) のページ説明を削除します。
     * 最初に引用された段落 ([QuoteMessage]) と最後の添付行 ([AttachmentLines])。何もない場合は、元の単語を意味します。段落のみが引用され、単語のみが選択され、添付ファイルのみが含まれる場合は、空になります。
     */
    fun typed(text: String, tools: ToolDispatcher): String = AttachmentLines.words(QuoteMessage.words(tools.userWords(text)))

    /** [typed] に加えて、ユーザー自身が発言した内容を引用する場合、引用された段落は一緒にカウントされ (対話ページの引用はユーザーがその時点で入力したものです)、前に配置されます。ミカの回答を引用する場合、[typed] のみが引用されます。 */
    fun said(text: String, tools: ToolDispatcher): String {
        val own = QuoteMessage.parse(tools.userWords(text)).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text, tools)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
