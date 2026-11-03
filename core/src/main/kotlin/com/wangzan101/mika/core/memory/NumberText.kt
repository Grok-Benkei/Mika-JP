package com.wangzan101.mika.core.memory

import java.text.Normalizer

/**
 * 数字の表記方法を統一、記憶検証とスキル緩和と共通：全角から半角、3つ以上連続する漢数字を一語一語算用数字に変換
 * (百、百、千を削除します。両側で同じ変換を使用すると、比較するときに一貫性が得られます)、数値間のスペース、ハイフン、ドット、カンマを削除します。
 * (「138 0013 8000」、「1,234」、および「13800138000」はすべて一連の数字になります)。
 */
internal object NumberText {
    private val CN_DIGIT = mapOf('零' to '0', '〇' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
        '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
    val CN_RUN = Regex("[零〇一二三四五六七八九十百千]{3,}")
    private val SEPARATED = Regex("\\d(?:[\\s\\-.,]*\\d)+")
    private const val MIN_SEPARATED = 3

    fun collapse(s: String): String {
        val t = CN_RUN.replace(Normalizer.normalize(s, Normalizer.Form.NFKC)) { m ->
            m.value.mapNotNull { CN_DIGIT[it] }.joinToString("").ifEmpty { m.value }
        }
        return SEPARATED.replace(t) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length >= MIN_SEPARATED) digits else m.value
        }
    }
}
