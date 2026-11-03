package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** ダイアログ ページのラウンドの下に残された行: まだオン、オフ (理由の有無にかかわらず)、および残り時間。 */
class KeptPageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val page = KeptPage(1, "c1", "r1", "播放课程", "course.example.com", null, null, 0, 7_200_000)
    private fun ex(runId: String?, kept: String? = null, closed: String? = null) =
        Exchange("播放课程", emptyList(), ExchangeStatus.Done("好了", null), live = false, runId = runId, keptPage = kept, keptPageClosed = closed)

    @Test fun rowFollowsTheLivePageAndTheTurnRecord() {
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1", kept = "course.example.com"), page))
        // ラウンド終了直後で残りページの記録を読み込んでいない場合でも、その時点で残っているページに応じて表示されます。
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1"), page))
        assertEquals(KeptRow.Closed("expired"), KeptRow.of(ex("r1", kept = "course.example.com", closed = "expired"), null))
        // 他のラウンドで残ったページは、このラウンドにリンクされません。
        assertEquals(KeptRow.Closed(null), KeptRow.of(ex("r0", kept = "a.example.com"), page))
        assertNull(KeptRow.of(ex("r2"), page))
        assertNull(KeptRow.of(ex(null), null))
    }
}
