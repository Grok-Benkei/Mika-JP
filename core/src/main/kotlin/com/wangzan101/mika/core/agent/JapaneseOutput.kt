package com.wangzan101.mika.core.agent

import java.lang.Character.UnicodeScript

/** 進捗・生成タイトルなど、引用やコード以外に日本語の説明が必要な表示の共通判定。 */
object JapaneseOutput {
    /** 固有名詞・識別子・引用・コードだけの文字列は、説明文としては採用しない。最終回答のデータは別途そのまま保持する。 */
    fun isJapaneseProse(text: String): Boolean {
        val body = ReplyLanguage.stripQuoted(text)
        return body.codePoints().anyMatch { point ->
            UnicodeScript.of(point).let { it == UnicodeScript.HIRAGANA || it == UnicodeScript.KATAKANA }
        } && ReplyLanguage.mismatch(text) == null
    }
}
