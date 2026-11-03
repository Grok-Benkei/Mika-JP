package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Test

class HantTest {
    // 2 つの文字列は単語ごとに照合されます。長さが等しくない場合は静かに切り捨てられ、テーブル全体の位置がずれます。 1 つの繁体字中国語文字は 1 つの簡体字文字のみと一致します
    @Test fun tableLinesUp() {
        assertEquals(Hant.TRADITIONAL.length, Hant.SIMPLIFIED.length)
        assertEquals(Hant.TRADITIONAL.length, Hant.TRADITIONAL.toSet().size)
        assertEquals("", Hant.TRADITIONAL.filter { it in Hant.SIMPLIFIED })
    }

    @Test fun foldsWordsToSimplified() {
        assertEquals("注销账户", Hant.fold("註銷帳户"))
        assertEquals("回复", Hant.fold("回覆"))
        assertEquals("发布", Hant.fold("發佈"))
        assertEquals("联系对方", Hant.fold("聯繫對方"))
        assertEquals("已经简体", Hant.fold("已经简体"))
    }
}
