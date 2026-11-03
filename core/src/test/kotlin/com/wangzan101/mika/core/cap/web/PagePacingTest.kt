package com.wangzan101.mika.core.cap.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PageLimitReached
import com.wangzan101.mika.core.uistate.PlacePace

/** ページごとの読書のリズム: 最初のページから最後のページまで、十分に停止したときから、Web サイトを変更するときまで、さまざまです。滞在時間には上限と下限があり、読んだページ数に応じて長くなります。 */
class PagePacingTest {
    private val limits = PagePacing.Limits()

    @Test
    fun `first page on a site has no dwell`() {
        val p = PagePacing.human(Random(1))
        assertEquals(0, p.waitBefore("shop.example.com", 0))
        assertEquals(0, p.noteLoad("https://shop.example.com/", 0))
        assertEquals(0, p.waitBefore("shop.example.com", 10))
    }

    @Test
    fun `second page dwell is within the first range and counts elapsed time`() {
        repeat(50) { seed ->
            val p = PagePacing.human(Random(seed))
            p.noteLoad("https://shop.example.com/", 0)
            val dwell = p.noteLoad("https://shop.example.com/list?page=2", 1_000)
            assertTrue("$dwell", dwell in limits.firstDwellMs)
            assertEquals(dwell, p.waitBefore("shop.example.com", 1_000))
            // モデルを考えたり読んだりした時間は滞在に含まれます
            assertEquals(dwell - 1_500, p.waitBefore("shop.example.com", 2_500))
            assertEquals(0, p.waitBefore("shop.example.com", 1_000 + dwell))
            assertEquals(0, p.waitBefore("shop.example.com", 100_000))
        }
    }

    @Test
    fun `dwell grows with pages seen and never passes the cap`() {
        val p = PagePacing.human(Random(7))
        var last = 0L
        var t = 0L
        for (n in 1..30) {
            val d = p.noteLoad("https://shop.example.com/p$n", t)
            if (n >= 2) {
                val extra = (n - 2).toLong()
                val lo = (limits.firstDwellMs.first + extra * limits.perPageMs.first).coerceAtMost(limits.maxDwellMs)
                assertTrue("page $n: $d", d in lo..limits.maxDwellMs)
            }
            last = d
            t += 15_000
        }
        assertEquals(limits.maxDwellMs, last)
        assertTrue(p.waitBefore("shop.example.com", t - 15_000) <= limits.maxDwellMs)
    }

    @Test
    fun `another site does not wait and subdomains are the same site`() {
        val p = PagePacing.human(Random(3))
        p.noteLoad("https://www.shop.example.com/", 0)
        p.noteLoad("https://m.shop.example.com/item/1", 100)
        // 同一登録可能ドメイン名：2ページ目
        assertTrue(p.waitBefore("example.com", 200) > 0)
        // 他の Web サイト: 待つ必要はありません。他の Web サイトに到達した後、元の Web サイトに戻る動作は、元の Web サイトのページに従って待機しません。
        assertEquals(0, p.waitBefore("news.example.org", 200))
        assertEquals(0, p.noteLoad("https://news.example.org/", 300))
        assertEquals(0, p.waitBefore("shop.example.com", 400))
    }

    @Test
    fun `pages outside the window no longer count`() {
        val p = PagePacing.human(Random(5))
        p.noteLoad("https://shop.example.com/", 0)
        // 時間枠が過ぎると、再びこの Web サイトの最初のページになります。
        assertEquals(0, p.noteLoad("https://shop.example.com/again", limits.windowMs + 1))
    }

    @Test
    fun `off never waits`() {
        val p = PagePacing.Off
        p.noteLoad("https://shop.example.com/", 0)
        p.noteLoad("https://shop.example.com/2", 1)
        assertEquals(0, p.waitBefore("shop.example.com", 2))
    }

    @Test
    fun `dwell waits only for the rest and only on url changes`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val waited = mutableListOf<Long>()
        val d = PageDwell(PagePacing.human(Random(11)), clock = { now }, sleep = { slept += it; now += it })
        d.onWait = { waited += it }
        d.seen("https://shop.example.com/")
        assertEquals(0, d.before())
        // 同じページ (# だけが異なる) は新しいページとみなされません
        d.seen("https://shop.example.com/#top")
        assertEquals(0, d.before())
        d.seen("https://shop.example.com/list?page=2")
        now += 1_000
        val w = d.before()
        assertTrue("$w", w in (limits.firstDwellMs.first - 1_000)..(limits.firstDwellMs.last - 1_000))
        assertEquals(listOf(w), slept)
        assertEquals(listOf(w), waited)
        // 十分に停止しました: 次のアクションを待つ必要はもうありません
        assertEquals(0, d.before())
        // がさらに読み込まれました: 別のページ
        d.loadedMore()
        assertTrue(d.before() > 0)
        // は他の Web サイトを開くのを待ちません
        d.seen("https://shop.example.com/list?page=3")
        assertEquals(0, d.before("https://news.example.org/"))
    }

    @Test
    fun `careful sites dwell longer within their own cap`() {
        val c = PagePacing.Limits.CAREFUL
        repeat(30) { seed ->
            val p = PagePacing.human(Random(seed))
            var t = 0L
            for (n in 1..20) {
                val d = p.noteLoad("https://jobs.example.com/p$n", t, PlacePace.CAREFUL)
                if (n == 2) assertTrue("$d", d in c.firstDwellMs)
                if (n >= 2) assertTrue("page $n: $d", d in c.firstDwellMs.first..c.maxDwellMs)
                t += 40_000
            }
        }
        // 通常のウェブサイトの範囲は変わりません
        val p = PagePacing.human(Random(2))
        p.noteLoad("https://shop.example.com/", 0)
        assertTrue(p.noteLoad("https://shop.example.com/2", 1) in limits.firstDwellMs)
        assertTrue(c.firstDwellMs.first > limits.firstDwellMs.last && c.maxDwellMs > limits.maxDwellMs && c.pageCap < limits.pageCap)
    }

    @Test
    fun `page cap counts pages per site in the task and resets when the user answers`() {
        val p = PagePacing.human(Random(4))
        repeat(limits.pageCap - 1) { p.noteLoad("https://shop.example.com/p$it", it * 60_000L) }
        assertFalse(p.capReached("shop.example.com"))
        p.noteLoad("https://www.shop.example.com/last", 3_000_000)
        // 時間枠を経過したページもカウントされます。上限はタスク全体の数に基づきます
        assertTrue(p.capReached("m.shop.example.com"))
        assertEquals(limits.pageCap, p.pagesOn("shop.example.com"))
        // その他のWebサイトは別途料金がかかります。
        assertFalse(p.capReached("news.example.org"))
        p.userAnswered()
        assertFalse(p.capReached("shop.example.com"))
        // 閲覧速度を制限する Web サイトには下限値があります
        repeat(PagePacing.Limits.CAREFUL.pageCap) { p.noteLoad("https://jobs.example.com/p$it", it.toLong(), PlacePace.CAREFUL) }
        assertTrue(p.capReached("jobs.example.com", PlacePace.CAREFUL))
        // リズムなしの場合は上限なし
        repeat(50) { PagePacing.Off.noteLoad("https://shop.example.com/p$it", 0) }
        assertFalse(PagePacing.Off.capReached("shop.example.com"))
    }

    @Test
    fun `page cap is lifted for a site once an outward action was done there`() {
        val p = PagePacing.human(Random(5))
        repeat(limits.pageCap) { p.noteLoad("https://jobs.example.com/p$it", it * 1_000L) }
        repeat(limits.pageCap) { p.noteLoad("https://shop.example.org/p$it", it * 1_000L) }
        assertTrue(p.capReached("jobs.example.com"))
        p.outwardDone("www.jobs.example.com")
        // は外部アクションを実行しました: この Web サイト (同じ登録可能なドメイン名) には上限がなくなり、滞在期間は変わりません。
        assertFalse(p.capReached("m.jobs.example.com"))
        val d = p.noteLoad("https://jobs.example.com/more", 100_000)
        assertTrue("$d", d > 0)
        assertEquals(d, p.waitBefore("jobs.example.com", 100_000))
        // ユーザーが応答した後も設定されていません。他のウェブサイトは影響を受けません
        p.userAnswered()
        repeat(limits.pageCap * 2) { p.noteLoad("https://jobs.example.com/x$it", 200_000L + it) }
        assertFalse(p.capReached("jobs.example.com"))
        repeat(limits.pageCap) { p.noteLoad("https://shop.example.org/y$it", 300_000L + it) }
        assertTrue(p.capReached("shop.example.org"))
    }

    @Test
    fun `the outward action itself passes the cap and only a done one lifts it`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 2)), clock = { now }, sleep = { now += it })
        for (i in 1..2) { d.seen("https://jobs.example.com/p$i"); now += 60_000 }
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        // 実行される外部アクションはブロックされません。未完了のアクションについては上限は解放されません。
        d.outwardStart("jobs.example.com")
        d.before()
        d.outwardEnd("jobs.example.com", done = false)
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        d.outwardStart("jobs.example.com")
        d.outwardEnd("jobs.example.com", done = true)
        d.seen("https://jobs.example.com/p3")
        // が宿泊料金を補填します。
        assertTrue(d.before() > 0)
    }

    @Test
    fun `dwell refuses page changes past the cap but still scrolls`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 3)), clock = { now }, sleep = { now += it })
        for (i in 1..3) { d.seen("https://shop.example.com/p$i"); now += 60_000; d.before(gate = false) }
        val e = runCatching { d.before() }.exceptionOrNull()
        assertTrue("$e", e is PageLimitReached && e.pages == 3 && e.cap == 3 && e.site == "shop.example.com")
        assertTrue(runCatching { d.before("https://shop.example.com/p4") }.exceptionOrNull() is PageLimitReached)
        // 下にスクロール (上限は見ずに) し、他の Web サイトも通常どおりにスクロールします
        assertEquals(0, d.before(gate = false))
        assertEquals(0, d.before("https://news.example.org/"))
        // ユーザーが返信しました: 続きを読んでください
        d.userAnswered()
        assertEquals(0, d.before())
    }

    @Test
    fun `site pace comes from the lookup by host`() = runBlocking {
        var now = 0L
        val asked = mutableListOf<String>()
        val d = PageDwell(PagePacing.human(Random(6)), pace = { asked += it; if (it.endsWith("jobs.example.com")) PlacePace.CAREFUL else PlacePace.NORMAL },
            clock = { now }, sleep = { now += it })
        d.seen("https://www.jobs.example.com/a")
        d.seen("https://www.jobs.example.com/b")
        val w = d.before()
        assertTrue("$w", w in PagePacing.Limits.CAREFUL.firstDwellMs)
        // 同じホストに対して 1 回だけ要求します
        assertEquals(listOf("jobs.example.com"), asked)
    }

    @Test
    fun `idle scrolls happen inside a long dwell only when a port is given`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val port = FakeWebPort()
        var idled = 0
        val d = PageDwell(PagePacing.human(Random(12)), idle = IdleScroll(Random(3)), clock = { now }, sleep = { slept += it; now += it })
        d.onIdleScroll = { idled += it }
        d.seen("https://shop.example.com/")
        d.seen("https://shop.example.com/2")
        val w = d.before(port = port)
        // 停止時間の合計は変わらず、ロールは 1 ～ 3 回です。
        assertEquals(w, slept.sum())
        val scrolls = port.calls.filter { it.startsWith("idle ") }
        assertTrue("$scrolls", scrolls.size in 1..3)
        assertEquals(scrolls.size, idled)
        // 座標に基づくアクション (ポートが指定されていない) がロールされません
        d.seen("https://shop.example.com/3")
        port.calls.clear()
        d.before()
        assertTrue(port.calls.isEmpty())
        // ポートは、現時点ではロールするのが不便であることを示しています。動作しない場合は、再試行しないでください。停止するだけで十分です。
        d.seen("https://shop.example.com/4")
        port.calls.clear(); slept.clear(); idled = 0
        port.idleOk = false
        val w2 = d.before(port = port)
        assertEquals(w2, slept.sum())
        assertTrue(port.calls.size <= 1)
        assertEquals(0, idled)
    }
}
