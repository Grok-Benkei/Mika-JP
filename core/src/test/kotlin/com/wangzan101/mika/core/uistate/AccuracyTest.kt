package com.wangzan101.mika.core.uistate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AccuracyTest {
    private fun loc(
        name: String, x: Int = 100, y: Int = 100, ctx: String? = "上一项", role: String = "TextView", rid: String? = null,
        hint: Boolean = false, inList: Boolean = false,
    ) = ElementLocator(role, name, rid, x, y, ctx, hint, inList)

    @Test fun findsSameElementAfterSmallShift() {
        val prev = loc("蓝牙")
        val after = listOf(loc("WLAN", ctx = null), loc("蓝牙", y = 150))
        assertEquals(Relocation.Found(1), relocate(prev, listOf(prev), after, 720))
    }

    @Test fun goneWhenNotInNewList() {
        assertEquals(Relocation.Gone, relocate(loc("蓝牙"), listOf(loc("蓝牙")), listOf(loc("WLAN")), 720))
    }

    @Test fun ambiguousWhenDuplicatedOrNameless() {
        val prev = loc("删除")
        assertEquals(Relocation.Ambiguous, relocate(prev, listOf(prev), listOf(prev, prev.copy(centerY = 300)), 720))
        assertEquals(Relocation.Ambiguous, relocate(prev, listOf(prev, prev.copy(centerY = 300)), listOf(prev), 720))
        assertEquals(Relocation.Ambiguous, relocate(loc(""), listOf(loc("")), listOf(loc("")), 720))
    }

    @Test fun namelessWithResourceIdCanBeFound() {
        val prev = loc("", rid = "com.x:id/cart")
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev), listOf(prev.copy(centerX = 120)), 720))
    }

    @Test fun movedWhenContextChangesOrShiftTooFar() {
        val prev = loc("加入购物车", ctx = "商品 A")
        assertEquals(Relocation.Moved, relocate(prev, listOf(prev), listOf(prev.copy(context = "商品 B")), 720))
        // 0.15 × 720 = 108px
        assertEquals(Relocation.Moved, relocate(prev, listOf(prev), listOf(prev.copy(centerY = 100 + 109)), 720))
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev), listOf(prev.copy(centerY = 100 + 108)), 720))
    }

    // ページの残りの部分 (下の列の 3 つの項目) は変更されず、ターゲット自体のみが変更されます: カウントダウン、カルーセルのプリセット単語、カルーセルによるコンテキストの変更
    private val tabs = listOf(loc("首页", 100, 1500, null), loc("消息", 360, 1500, "首页"), loc("我的", 620, 1500, "消息"))

    @Test fun sameSpotFoundWhenOnlyItsTextChangedOnAStillPage() {
        val prev = loc("跳过 3", 600, 50, ctx = "广告", role = "Button", rid = "com.x:id/skip")
        val after = tabs + prev.copy(name = "跳过 2")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun countdownOnASparseSplashPageIsFound() {
        // 冒頭の広告ページには有名な要素がいくつかあるだけです
        val prev = loc("跳过 3", 600, 50, ctx = null, role = "TextView", rid = "com.x:id/skip")
        val rest = listOf(loc("广告", 600, 1500, "跳过 3"), loc("浏览器", 360, 1550, "广告"))
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev) + rest, listOf(prev.copy(name = "跳过 2")) + rest, 720))
    }

    @Test fun rotatingPlaceholderInSearchBarIsFound() {
        val prev = loc("宝安租房", 360, 200, ctx = "定位", role = "EditText", rid = "com.x:id/search_bar", hint = true)
        val after = tabs + prev.copy(name = "平板电脑")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun differentControlInTheSameSpotIsNeverTaken() {
        // 名前が変更され、数字と空白スペースを削除しても一致しません。これは別のコントロール (または同じコントロールの意味が変更された) であり、モデルで置き換えることはできません。
        val follow = loc("关注", 600, 300, role = "Button", rid = "com.x:id/follow")
        assertEquals(Relocation.Gone, relocate(follow, tabs + follow, tabs + follow.copy(name = "已关注"), 720))
        val next = loc("下一步", 360, 1300, role = "Button", rid = "com.x:id/primary")
        assertEquals(Relocation.Gone, relocate(next, tabs + next, tabs + next.copy(name = "确认支付"), 720))
        // は、リソース ID がない場合でもカウントダウンを緩和しません。
        val skip = loc("跳过 3", 600, 50, role = "Button")
        assertEquals(Relocation.Gone, relocate(skip, tabs + skip, tabs + skip.copy(name = "跳过 2"), 720))
        // 新しいパーティも古いパーティもリソース ID を持たないため、緩和されません。
        val skipRid = skip.copy(resourceId = "com.x:id/skip")
        assertEquals(Relocation.Gone, relocate(skipRid, tabs + skipRid, tabs + skip.copy(name = "跳过 2"), 720))
        // プロンプトではない通常のテキストは、変更された場合にはカウントされません。
        val plain = loc("宝安租房", 360, 200, role = "TextView", rid = "com.x:id/title")
        assertEquals(Relocation.Gone, relocate(plain, tabs + plain, tabs + plain.copy(name = "平板电脑"), 720))
    }

    @Test fun contextChangeInsideAListIsNotRelaxed() {
        val prev = loc("购买", 360, 200, ctx = "商品 A", role = "Button", inList = true)
        assertEquals(Relocation.Moved, relocate(prev, tabs + prev, tabs + prev.copy(context = "商品 B"), 720))
    }

    @Test fun contextChangedByCarouselOnAStillPageIsFound() {
        val prev = loc("搜索", 360, 200, ctx = "轮播图 A", role = "Button")
        val after = tabs + prev.copy(context = "轮播图 B")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun sameSpotNotTrustedWhenThePageScrolledOrChanged() {
        val prev = loc("加入购物车", 360, 400, ctx = "商品 A", role = "Button")
        val rows = listOf(loc("商品 A", 100, 300, null), loc("商品 B", 100, 500, "商品 A"), loc("商品 C", 100, 700, "商品 B"))
        // リストを 1 行上に移動します。他の製品のボタンは元の位置に移動するだけです。
        val scrolled = rows.map { it.copy(centerY = it.centerY - 200) } + prev.copy(context = "商品 B")
        assertEquals(Relocation.Moved, relocate(prev, rows + prev, scrolled, 720))
        // はページを変更しました。同じ位置にあるボタンの名前も変更されました。
        val otherPage = listOf(loc("设置", 100, 300, null), loc("关于", 100, 500, "设置"), prev.copy(name = "退出登录"))
        assertEquals(Relocation.Gone, relocate(prev, rows + prev, otherPage, 720))
    }

    /** は入力ボックスをシミュレートします。[accepts] が false の場合、書き込みは無視されます。 [clearable] が false の場合、クリアは無視されます。 [format] は入力ボックスをシミュレートしてテキストを独自に書き換えます。 */
    private class FakeField(
        var text: String?,
        val accepts: Boolean = true,
        val clearable: Boolean = true,
        val writeResult: Boolean = true,
        val format: (String) -> String = { it },
    ) : TextField {
        var writes = 0
        override fun read() = text
        override fun write(text: String): Boolean {
            writes++
            if (text.isEmpty()) { if (clearable && this.text != null) this.text = "" }
            else if (accepts && this.text != null) this.text = format(text)
            return writeResult
        }
    }

    private fun type(f: FakeField, t: String) = runBlocking { typeAndVerify(f, t) }

    @Test fun matchWhenTextLands() {
        assertEquals(Verification.MATCH, type(FakeField(""), "WLAN"))
        assertEquals(Verification.MATCH, type(FakeField("旧内容"), "WLAN"))
        // 番号のグループ化に追加されたスペースは影響しません。
        assertEquals(Verification.MATCH, type(FakeField("", format = { "138 0013 8000" }), "13800138000"))
    }

    @Test fun retriesOnlyOnceAndOnlyWhenTheTextDidNotLand() {
        val landed = FakeField("")
        assertEquals(Verification.MATCH, type(landed, "WLAN"))
        assertEquals(1, landed.writes)
        val first = object : TextField {
            var writes = 0; var text = ""
            override fun read() = text
            override fun write(text: String): Boolean { writes++; if (writes == 2) this.text = text; return true }
        }
        assertEquals(Verification.MATCH, runBlocking { typeAndVerify(first, "WLAN") })
        assertEquals(2, first.writes)
    }

    @Test fun passwordFieldIsWrittenOnceAndNeverRetried() {
        // パスワードボックス内の何か（ドット）を読み取ることができ、一致しない場合でも、それを書き込むのは 1 回だけです
        val f = FakeField("", accepts = false)
        assertEquals(Verification.UNKNOWN, runBlocking { typeAndVerify(f, "pw123456", secret = true) })
        assertEquals(1, f.writes)
    }

    @Test fun ignoredWriteIsNotMatch() {
        val f = FakeField("", accepts = false)
        assertEquals(Verification.MISMATCH, type(f, "WLAN"))
        assertEquals(2, f.writes) // が再試行しました
    }

    @Test fun writeReportedFailedIsAFailedAttempt() {
        // ボックス内のテキストは変更されましたが、アクションは失敗しました。成功とは見なされず、再試行後も失敗として扱われます。
        assertEquals(Verification.MISMATCH, type(FakeField("", accepts = false, writeResult = false), "WLAN"))
    }

    @Test fun containsIsNotEqual() {
        assertEquals(Verification.MISMATCH, type(FakeField("", format = { "${it}设置" }), "WLAN"))
    }

    @Test fun baselineAlreadyTargetAndWriteIgnoredIsNotMatch() {
        // はクリアまたは書き込みできません: 前後同様、判断の方法がありません
        assertEquals(Verification.UNKNOWN, type(FakeField("WLAN", accepts = false, clearable = false), "WLAN"))
        // はクリアできますが、書き込みはできません。クリア後は空として読み戻され、書き込み後も空のままです。
        assertEquals(Verification.MISMATCH, type(FakeField("WLAN", accepts = false), "WLAN"))
        // はクリアして書き込むことができます。実際には書き込まれます。
        assertEquals(Verification.MATCH, type(FakeField("WLAN"), "WLAN"))
    }

    @Test fun fieldStuckOnSameTextButAppReactedIsUnknown() {
        // アクセシビリティ ボックスは同じテキスト (プロンプトなど) を報告し続けますが、関連する単語がインターフェイスに表示されます。アプリは単語を受信しましたが、それを読み戻すことができません。
        fun run(f: FakeField, reacted: Boolean) = runBlocking { typeAndVerify(f, "天气", reacted = { reacted }) }
        assertEquals(Verification.UNKNOWN, run(FakeField("请输入关键词", accepts = false), reacted = true))
        assertEquals(Verification.MISMATCH, run(FakeField("请输入关键词", accepts = false), reacted = false))
        // 毎回書き込みに失敗します: インターフェイスがどのように変更されても、それは私たちによって入力されません
        assertEquals(Verification.MISMATCH, run(FakeField("请输入关键词", accepts = false, writeResult = false), reacted = true))
        // ボックス内の単語は変更されていますが、間違っています。「読み返せない」のではなく、まだ正しくありません。
        assertEquals(Verification.MISMATCH, run(FakeField("", format = { "${it}设置" }), reacted = true))
    }

    @Test fun unreadableFieldIsUnknown() {
        val f = FakeField(null)
        assertEquals(Verification.UNKNOWN, type(f, "secret"))
        assertEquals(1, f.writes)
    }

    @Test fun emptyTextMeansClear() {
        assertEquals(Verification.MATCH, type(FakeField("旧内容"), ""))
        assertEquals(Verification.MISMATCH, type(FakeField("旧内容", clearable = false), ""))
    }
}
