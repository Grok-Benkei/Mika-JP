package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun sixDistinctNonBlankExamples() {
        assertEquals(6, ChatCopy.examples.size)
        assertEquals(6, ChatCopy.examples.map { it.text }.filter { it.isNotBlank() }.toSet().size)
    }

    /** ブランチ タイトル: ソース タイトルの後に接尾辞を追加します。合計の長さは 24 ワードを超えてはなりません。ソースのタイトルは切り取られ、サフィックスは保持され、絵文字は半分に切り取られません。 */
    @Test fun branchTitleKeepsTheSuffixWhenTruncating() {
        val suffix = " · 分支"
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五"
        val title = ChatCopy.branchTitle(long, suffix)
        assertTrue(title.endsWith(suffix))
        assertEquals(24, title.length)
        assertEquals("短标题$suffix", ChatCopy.branchTitle("短标题", suffix))
        val emoji = ChatCopy.branchTitle("😀".repeat(21), suffix)
        assertTrue(emoji.endsWith(suffix))
        assertFalse(emoji.dropLast(suffix.length).lastOrNull()?.isHighSurrogate() ?: false)
    }

    @Test fun titleIsTheFirstLineOfTheFirstMessage() {
        val first = Exchange("查车次\n附件:/workspace/inbox/a.pdf(1 KB)", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("查车次", ChatCopy.title(listOf(first, first.copy(goal = "再查"))))
        assertEquals("", ChatCopy.title(emptyList()))
        // は添付ファイルのみを送信しました: タイトルとしてパスがありません
        assertEquals("写真", ChatCopy.title(listOf(first.copy(goal = "附件:/workspace/inbox/a.jpg(1 KB)"))))
        assertEquals("a.pdf", ChatCopy.plainTitle("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals(ScreenCopy.TAG, ChatCopy.plainTitle("[[screen:s1]]"))
        // ブラウザ ページ上のページについての質問: ページの説明に不適切なタイトルが付いています。
        val paged = com.wangzan101.mika.core.cap.web.AttachedPage.compose(com.wangzan101.mika.core.cap.web.AttachedPage.Page("https://a.example/", "页"), "挑一个\n第二行")
        assertEquals("挑一个", ChatCopy.plainTitle(paged))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun drawerListsRunningThenRecent() {
        val now = at(2026, 10, 27, 15)
        val all = listOf(
            ConversationSummary("a", "附近的川菜", at(2026, 10, 27, 14)),
            ConversationSummary("b", "明天的高铁票", at(2026, 10, 26)),
            ConversationSummary("c", "PDF 要点整理", at(2026, 10, 26)),
        )
        val current = CurrentConversation("a", running = true, title = "附近的川菜", subtitle = "正在操作外卖")
        val s = drawerSections(
            all, current, others = listOf("c", "d"), otherTitle = { if (it == "d") "第二个任务" else null },
            canSwitch = true, now = now, zone = zone,
        )
        assertEquals(listOf("a", "c", "d"), s.running.map { it.id })
        assertEquals(listOf("附近的川菜", "PDF 要点整理", "第二个任务"), s.running.map { it.title })
        assertEquals("正在操作外卖", s.running.first().subtitle)
        assertTrue(s.running.first().current)
        assertEquals(listOf("b"), s.recent.map { it.id })
        assertEquals("", s.recent.single().subtitle)
    }

    /** 固定された会話は「トップ」セクションにあり、最近固定された会話が最初に表示され、「最近」には繰り返されません。タスクが実行中のものは、進行中としてのみリストされます (プッシュピン付き)。 */
    @Test fun drawerListsPinnedAboveRecent() {
        val all = listOf(
            ConversationSummary("a", "一", 40L),
            ConversationSummary("b", "二", 30L, pinnedAt = 100L),
            ConversationSummary("c", "三", 20L),
            ConversationSummary("d", "四", 10L, pinnedAt = 200L),
            ConversationSummary("e", "五", 5L, pinnedAt = 300L),
        )
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), others = listOf("e"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("e"), s.running.map { it.id })
        assertTrue(s.running.single().pinned)
        assertEquals(listOf("d", "b"), s.pinned.map { it.id })
        assertTrue(s.pinned.all { it.pinned })
        assertEquals(listOf("a", "c"), s.recent.map { it.id })
        assertTrue(s.recent.none { it.pinned })
        // 固定解除：変更時刻ごとに並べられた「最近」の位置に戻ります
        val unpinned = drawerSections(all.map { if (it.id == "b") it.copy(pinnedAt = null) else it }, CurrentConversation("a", false, "一", ""), emptyList(), { null }, true, 0L, zone)
        assertEquals(listOf("e", "d"), unpinned.pinned.map { it.id })
        assertEquals(listOf("a", "b", "c"), unpinned.recent.map { it.id })
    }

    /** 会話は、1 つのページに面したブラウザ ページで始まりました。サイドバーのページには、進行中の段落と最新の段落の両方の Web サイト (「Web ページ」という小さなラベルと Web サイトの名前として描画されます) が表示されます。 */
    @Test fun drawerMarksConversationsStartedFromAPage() {
        val page = com.wangzan101.mika.chat.PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")
        val all = listOf(ConversationSummary("a", "一", 0L, page = page), ConversationSummary("b", "二", 0L), ConversationSummary("c", "三", 0L, page = page))
        val s = drawerSections(all, CurrentConversation("x", false, "", ""), others = listOf("c"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("shop.example.com", null), s.recent.map { it.site })
        assertEquals("shop.example.com", s.running.single().site)
    }

    /** 分岐した会話にはサイドバーに分岐マークが表示されます。通常の会話ではそうではありません。 */
    @Test fun drawerMarksBranchedConversations() {
        val origin = BranchOrigin("parent", 1, afterTurn = true, at = 5L)
        val all = listOf(ConversationSummary("a", "一", 0L, branchOf = origin), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("x", false, "", ""), emptyList(), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.branch })
    }

    /** タスクは進行中ですが、並列処理が有効になっていません。クリックできるのは現在のダイアログのみです。 */
    @Test fun drawerLocksOtherConversationsWhileBusy() {
        val all = listOf(ConversationSummary("a", "一", 0L), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), emptyList(), { null }, canSwitch = false, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.enabled })
        assertEquals(listOf(true, false), s.recent.map { it.current })
        // 現在の会話がリストに保存されていない場合 (最初の文が送信されたばかりの場合)、進行中としてもリストされます。
        val fresh = drawerSections(emptyList(), CurrentConversation(null, true, "", "正在处理"), emptyList(), { null }, false, 0L, zone)
        assertEquals("新しいチャット", fresh.running.single().title)
        assertTrue(fresh.running.single().enabled)
    }

    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("おはようございます", ChatCopy.greeting(7))
        assertEquals("こんにちは", ChatCopy.greeting(12))
        assertEquals("こんにちは", ChatCopy.greeting(15))
        assertEquals("こんばんは", ChatCopy.greeting(20))
        assertEquals("夜更かしですね", ChatCopy.greeting(2))
        assertEquals("夜更かしですね", ChatCopy.greeting(23))
    }

    @Test fun clockLabel() {
        assertEquals("0:00", ChatCopy.clock(0))
        assertEquals("0:24", ChatCopy.clock(24_900))
        assertEquals("12:05", ChatCopy.clock(725_000))
        assertEquals("1:02:03", ChatCopy.clock(3_723_000))
        assertEquals("0:00", ChatCopy.clock(-5))
    }

    @Test fun subtitleSaysWhatIsGoingOn() {
        val working = Exchange("x", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("作業中" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, null))
        assertEquals("设置 で作業中" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, "设置"))
        assertEquals("あなたの対応を待っています" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.NeedsInput("?", null)), null))
        assertEquals("あなたの対応を待っています" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.Paused("why")), null))
        assertEquals("ロック解除を待っています" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.WaitingUnlock), null))
        assertNull(ChatCopy.subtitle(working.copy(status = ExchangeStatus.Done("好", null)), "设置"))
        assertNull(ChatCopy.subtitle(null, null))
    }

    @Test fun progressLineSaysPausedWhileWaitingForYou() {
        val ex = Exchange("x", emptyList(), ExchangeStatus.Working, live = true, line = "按评分排序")
        assertEquals("按评分排序", ChatCopy.progressLine(ex))
        assertEquals("一時停止中", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsTakeover("login", "登录"))))
        assertEquals("一時停止中", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsConfirmation("t", "b", 1))))
        assertEquals("一時停止中", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.WaitingUnlock)))
        assertEquals("作業中", ChatCopy.progressLine(ex.copy(line = "", waitingForModel = true)))
    }

    @Test fun placeholderFollowsTheComposer() {
        assertEquals(ChatCopy.EMPTY_PROMPT, ChatCopy.placeholder(ComposerMode.Idle, hasHistory = false))
        assertEquals("さらにひとこと…", ChatCopy.placeholder(ComposerMode.Idle, hasHistory = true))
        assertEquals("Mika の質問に答える", ChatCopy.placeholder(ComposerMode.Answer, hasHistory = true))
        assertEquals("補足や変更を入力…", ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true))
    }
}
