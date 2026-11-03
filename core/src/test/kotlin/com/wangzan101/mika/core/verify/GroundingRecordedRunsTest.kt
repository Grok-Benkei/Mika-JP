package com.wangzan101.mika.core.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * は、3 つのカテゴリの正解 (実際のマシンの記録内の元の単語と読み取られた Web ページ上のテキスト) およびそれぞれの反例を誤って拒否します。証拠は証明されたことを証明するだけであり、間違った答えはすべてブロックされます。
 * - 実行中の時計: 質問または証拠に「明日」と「火曜日」が書かれており、日付は時計に従って計算されます。
 * - 簡単な操作: 記載された計算式 (a 演算子 b = c) に答えます。a と b は証拠内ですべて一致します。約数の四捨五入 (近似、≈) を明確に説明します。
 * - 切り捨て: 次のように答えてください... 引用を省略すると、画面上の行は完全になり、切り捨てとしてカウントされません。
 */
class GroundingRecordedRunsTest {
    /** 記録上の検証日：2026-10-24、土曜日。 */
    private val today = LocalDate.of(2026, 10, 24)

    private val beijingPage = listOf(
        "18:00更新 | 数据来源 中央气象台",
        "周末两天（10月24日至25日），我国华东沿海多地仍有降雨，其中今天雨势较强；同时，西北地区东部和华北等地降水增多。",
        "25日（明天） 小雨 12℃/9℃ <3级",
        "26日（后天） 阴转晴 16℃/6℃ <3级",
    ).joinToString("\n")
    private val beijingGoal = "用网页查一下明天北京的天气,告诉我最高和最低气温"
    private val beijingAnswer = "明天(10月25日,周日)北京天气已查到。\n\n- 天气:小雨\n- 最高气温:12℃\n- 最低气温:9℃\n\n" +
        "依据:中国天气网北京7天预报页面,「25日(明天)」一栏显示「小雨 12℃/9℃ <3级」,更新时间 18:00,数据来源中央气象台。"

    // 実際のテスト web-weather-beijing: ページには「25日（明日）」としか書かれておらず、答えは「10月25日」に変換され、見つからない日付とみなされます。
    @Test fun dateResolvedFromRunClockIsKnown() {
        val without = Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage))
        assertEquals(false, without.found)
        assertEquals(listOf("10月25日"), without.unmatched.map { it.text })
        assertEquals(true, Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage), today).found)
        // web-weather-three-cities: 「2026 年 10 月 25 日」の年も同様です
        val cities = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n\n" +
            "依据:中国天气网 7 天预报页面,北京显示\"25日(明天) 小雨 12℃/9℃\"。"
        assertEquals(true, Grounding.check("用网页查北京明天的最高气温", cities, listOf(beijingPage), today).found)
        // ページには「火曜日」と書かれています: 火曜日は 10 月 27 日です
        val week = "27日（周二） 晴 16℃/2℃ <3级"
        assertEquals(true, Grounding.check("北京周二天气", "周二(10月27日)北京晴,最高气温:16℃\n依据:「晴 16℃/2℃」", listOf(week), today).found)
    }

    @Test fun clockOnlyVouchesForRelativeDaysTheEvidenceMentions() {
        fun unmatched(answer: String, page: String = beijingPage, goal: String = beijingGoal) =
            Grounding.check(goal, answer, listOf(page), today).unmatched.map { it.text }
        // 「火曜日」は回答にのみ表示されます。質問にもページにも火曜日とは書かれておらず、時計はそれを証明しません。
        assertEquals(listOf("10月27日"), unmatched("周二(10月27日)北京晴,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // タイトルとページに明日（25日）と明後日（26日）とありますが、27日は間違いです。
        assertEquals(listOf("10月27日"), unmatched(beijingAnswer.replace("10月25日", "10月27日"), beijingPage + "\n29日 小雨 10月29日"))
        // タイトルまたはページに相対的な記述はありません。1 週間以内の日付は時計によって証明されません。
        val plainPage = "25日 小雨 12℃/9℃ <3级"
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」", plainPage, "北京天气"))
        // は、それはページから読み取られた日付（引用文、根拠）であり、時計はそれを証明しないと答えました。
        assertTrue("10月25日" in unmatched("明天北京「10月25日 小雨」,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:页面写着 10月25日 小雨 12℃/9℃"))
        // 時間は時計によって目撃されることはありません
        assertEquals(listOf("10:27"), unmatched("今天 10:27 更新,明天小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // 遠い日
        assertEquals(listOf("11月5日"), unmatched(beijingAnswer.replace("10月25日", "11月5日")))
        // 時計は日付のみを証明し、数字は証明しません: プログラムされた温度はまだブロックされています
        assertEquals(listOf("31"), unmatched(beijingAnswer.replace("最高气温:12℃", "最高气温:31℃")))
    }

    private val riverPages = listOf(
        "长江，中国境内太平洋水系河流，最后注入东海。全长6363千米 [16]（一说有6236千米，一说6380千米，一说6397千米，一说 6403千米，一说6300余千米 [66]）。是中国、亚洲第一大河。",
        "黄河，是位于中国北方地区的大河，最后在东营市垦利区流入渤海。黄河全长约5464千米 [51]，流域面积约79.47万平方千米。",
        "web_navigate:网址 https://baike.example.com/cj,网页标题「长江（中国境内太平洋水系河流）_百科」",
        "web_navigate:网址 https://baike.example.com/hh,网页标题「黄河（中国北方独流入渤海水系河流）_百科」",
    )
    private val riverGoal = "用网页分别查一下长江和黄河的全长,告诉我哪条更长、大约长多少公里"
    private fun riverAnswer(diff: String) = "长江和黄河的全长都已在百度百科词条正文中查到:\n\n" +
        "- 长江:全长 6363 千米(词条还注明另有 6236、6380、6397、6403、6300 余千米等说法)。\n- 黄河:全长约 5464 千米。\n\n" +
        "对比结果:长江更长,$diff。\n\n" +
        "依据:百度百科「长江(中国境内太平洋水系河流)」词条正文「全长6363千米」;「黄河(中国北方独流入渤海水系河流)」词条正文「全长约5464千米」。"

    // 実際の測定値 web-rivers-length-compare: 差 899 = 6363 − 5464、当然画面には表示されません。計算は明確に記述し、両方のオペランドを証拠に含める必要があります。
    @Test fun explicitComputationOfGroundedNumbersIsGrounded() {
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=899 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363 千米 - 5464 千米 = 899 千米"), riverPages).found)
        // 約数（≒、＝およそ）を末尾の0に応じて四捨五入することが明記されている場合
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464≈900 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=约900 公里"), riverPages).found)
        // 割合: ÷ 結果は小数点以下を四捨五入します。
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363÷5464=116.5%"), riverPages).found)
        // の価格差
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(true, Grounding.check("比较价格", "手机 ¥1999,耳机 ¥299,¥1999 − ¥299 = ¥1700\n依据:「手机 ¥1999」", prices).found)
        // 除数を明確に示す単一の数値（書き出された精度に四捨五入されたもの）は、証拠内の数値と等しい
        assertEquals(emptyList<String>(), Grounding.check("多少钱", "售价约 2000元\n依据:「售价 1999元」", listOf("售价 1999元")).unmatched.map { it.text })
    }

    @Test fun inventedNumberIsStillRejected() {
        fun unmatched(goal: String, answer: String, screens: List<String>) = Grounding.check(goal, answer, screens).unmatched.map { it.text }
        // 計算式は書かれておらず、違いは「画面上にない」だけです
        assertEquals(listOf("899"), unmatched(riverGoal, riverAnswer("比黄河长约 899 公里"), riverPages))
        assertEquals(listOf("1234"), unmatched(riverGoal, riverAnswer("比黄河长 1234 公里"), riverPages))
        // 計算式が間違っています
        assertEquals(listOf("999"), unmatched(riverGoal, riverAnswer("6363−5464=999 公里"), riverPages))
        // の約数は、多数の数値と一致するほど大まかにすることはできません。1000 は 899 から 10% 以上離れています。
        assertEquals(listOf("1000"), unmatched(riverGoal, riverAnswer("6363−5464≈1000 公里"), riverPages))
        // オペランドは証拠にありません
        assertEquals(listOf("5063", "1300"), unmatched(riverGoal, riverAnswer("6363−5063=1300 公里"), riverPages))
        // は 2 つの価格の差に正確に等しいですが、計算式はありません。「合計」や「差」などの言葉は証拠とみなされません。
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,共 ¥1700\n依据:「手机 ¥1999」", prices))
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,平板 ¥1700\n依据:「手机 ¥1999」", prices))
        // によってコンパイルされたパーセンテージ: 96% (100% でも 40% でもありません)
        assertTrue("100" in unmatched("电量多少", "电量:100%\n依据:「电量 96%」", listOf("电量 96%")))
        assertTrue("40" in unmatched("电量多少", "电量 96%,已用约 40%\n依据:「电量 96%」", listOf("电量 96%")))
        // は概算値を述べなかった：2000元ではなく1999元
        assertEquals(listOf("2000"), unmatched("多少钱", "售价 2000元\n依据:「售价 1999元」", listOf("售价 1999元")))
        // 質問に示された数値の計算結果を画面上で読み取る必要があります (電卓の質問: 計算式のみが画面上に表示されますが、結果は表示されません)
        assertEquals(listOf("3950.6"), unmatched("用计算器算 98765 除以 25", "98765÷25=3950.6\n依据:计算器屏上「98765÷25」", listOf("98765÷25")))
    }

    // 実測値 web-rivers-length-compare: ページ上の画像の URL ハッシュには 899 が含まれており、これが画面上の数値とみなされます。
    @Test fun numbersOnScreenMustBeWholeTokens() {
        val hash = "https://bkimg.cdn.bcebos.com/smart/d50735fae6cd7b899e51bc8a177c55a7d933c895a038-bkimg-process,v_1"
        val answer = riverAnswer("比黄河长约 899 公里")
        assertEquals(listOf("899"), Grounding.check(riverGoal, answer, riverPages + hash).unmatched.map { it.text })
        assertEquals(emptyList<String>(), Grounding.check(riverGoal, answer, riverPages + "长江比黄河长约 899 公里").unmatched.map { it.text })
        // ID の数字と文字を含むバージョン番号はカウントされません。単位は (128GB) と書かれていますが、小数点と 1000 分の 1 は引き続きカウントされます。
        assertEquals(false, Grounding.check("编号", "编号是 899 号码\n依据:「订单」", listOf("订单 a3f899c")).found)
        assertEquals(true, Grounding.check("存储多少", "存储:128GB\n依据:「存储 128GB」", listOf("存储 128GB")).found)
        assertEquals(true, Grounding.check("价格", "价格:1,299.50元\n依据:「价格」", listOf("价格 ¥1,299.50")).found)
        // 文字に挟まれた数字が 1 つの単語で比較されます。G7541 は一致し、コンパイルされた G9999 はそれをブロックします。
        assertEquals(emptyList<String>(), Grounding.check("车次", "车次:G7541\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        assertEquals(listOf("G9999"), Grounding.check("车次", "车次 G9999 05:52 出发\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        // 日付は同じです: URL の 1025 は 10 月 25 日ではありません
        assertEquals(listOf("10月25日"), Grounding.check("哪天", "日期是 10月25日\n依据:「图片」", listOf("图片 https://x.cn/p/img10x25b.jpg")).unmatched.map { it.text })
    }

    // 質問内の数字は値によって認識されます。質問には 1000.0、回答には 1000 と書き込みます。画面上に表示される値ではありません。
    @Test fun goalNumbersAreKnownByValue() {
        assertEquals(true, Grounding.check("用计算器算 1000.0 除以 8", "1000 除以 8 等于 125.0\n依据:计算器屏上「125」", listOf("125")).found)
    }

    private val trainRows = listOf(
        "预订G7541次车05:52从上海虹桥出发，06:51到杭州东,当日到达历时00:59的车票",
        "预订G1321次车06:07从上海虹桥出发，06:56到杭州东,当日到达历时00:49的车票",
        "预订G7331次车06:13从上海虹桥出发，07:07到杭州东,当日到达历时00:54的车票",
    ).joinToString("\n")
    private val trainAnswer = "明天(2026-10-25)上海到杭州的高铁车次:\n\n| G7541 | 上海虹桥 05:52 | 杭州东 06:51 |\n| G1321 | 上海虹桥 06:07 | 杭州东 06:56 |\n\n" +
        "依据:12306 官网余票页查询结果列表,页面元素逐条显示\"预订G7541次车05:52从上海虹桥出发,06:51到杭州东…\"\"预订G1321次车06:07从上海虹桥出发,06:56到杭州东…\"等车次信息。"

    // 実際のテスト web-12306-trains: 自分で答えてください... 引用文の後半が省略され、画面上の行が完成し、切り捨てと判断されます。
    @Test fun agentEllipsisOnCompleteLineIsNotTruncation() {
        assertFalse(Grounding.builtOnTruncated(trainAnswer, listOf(trainRows)))
        val pass = Verification(Verdict.Pass, listOf(CriterionCheck("列出至少三个车次号", "met", "预订G7541次车05:52从上海虹桥出发")), "屏上有")
        assertEquals(Verdict.Pass, Verifier.backstop(pass, "在 12306 上查明天上海到杭州的高铁", trainAnswer, listOf(trainRows), imageSent = false, today).verdict)
    }

    @Test fun truncatedScreenLineIsStillTruncation() {
        // 画面上の行自体が切り詰められ (... で終わる)、回答の引用が切り詰めまで続きます。その一部のみが表示されます。
        val cut = "预订G7541次车05:52从上海虹桥出发，06:51到杭州东…\n预订G1321次车06:07从上海虹桥出发，06:56到杭州东…"
        assertTrue(Grounding.builtOnTruncated(trainAnswer, listOf(cut)))
        // 引用文が... で始まり、画面上の行の前半が切れています
        assertTrue(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf("…到杭州东,当日到达")))
        assertFalse(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf(trainRows)))
    }

    // ---------- リテラルのみをチェックしてください: 正しい答えは書き込み (実機記録エミュ-ウェブ-smoke-2) のためブロックされませんが、間違った答えは引き続きブロックされます ----------

    private val doubanRows = listOf("1", "肖申克的救赎 / The Shawshank Redemption", "9.7 3353059人评价", "4", "阿甘正传 / Forrest Gump", "9.5 2465343人评价",
        "5", "千与千寻 / 千と千尋の神隠し", "9.4 2577773人评价").joinToString("\n")

    // 実際のテスト web-douban-top-five: 次の行のリスト番号を削除すると、次の行の「9.5」と「千と千尋の神隠し」が「9.5 千」と綴られます。記載されている閲覧日を確認日とみなします。
    @Test fun answerLinesAreNotJoinedAndBasisProseIsNotAClaim() {
        val a = "前五如下:\n\n1. 肖申克的救赎 — 评分 9.7\n4. 阿甘正传 — 评分 9.5\n5. 千与千寻 — 评分 9.4\n\n" +
            "依据:网页正文「1 肖申克的救赎 … 9.7 3353059人评价」「4 阿甘正传 … 9.5 2465343人评价」「5 千与千寻 … 9.4 2577773人评价」(movie.douban.com/top250,2026-10-24 读取)。"
        val g = Grounding.check("豆瓣 Top250 前五的电影名和评分", a, listOf(doubanRows), today)
        assertEquals(g.unmatched.toString(), true, g.found)
        assertFalse(g.claims.toString(), g.claims.any { "千" in it.text && it.kind == Grounding.Kind.Number })
        // 根拠内の引用は引き続きチェックされます: 集計された人数と誤って引用された映画タイトルはブロックされます
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("2577773人评价", "2577999人评价"), listOf(doubanRows), today).found)
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("「5 千与千寻", "「5 天空之城"), listOf(doubanRows), today).found)
    }

    // 実測値 web-rivers-length-compare: 「黄河：全長約 5464 キロメートル（入力文字列「…」）」を「タグ：値」とみなし、全区間が画面上にある必要がある
    @Test fun unquotedLabelValuesAreNotLiteralClaims() {
        val a = "- 黄河:全长约 5464 千米(词条正文「全长约5464千米 [51],流域面积约79.47万平方千米」)\n依据:黄河词条「全长约5464千米 [51],流域面积约79.47万平方千米」"
        assertEquals(true, Grounding.check(riverGoal, a, riverPages).found)
        assertEquals(listOf("5465"), Grounding.check(riverGoal, a.replaceFirst("5464 千米", "5465 千米"), riverPages).unmatched.map { it.text })
    }

    // 実際のテスト web-github-trending-top: 「Today +N」は、画面上の単語ではなく、書き込んだ数字に答える代わりに N を使用します。
    @Test fun quoteThatNamesTheAnswersOwnWordingIsNotAScreenClaim() {
        val page = listOf("## cloudflare / security-audit-skill", "JavaScript 43,250 2,699 Built by", "2,271 stars today")
        val a = "1. cloudflare / security-audit-skill — 43,250 颗星(今日 +2,271)\n说明:「今日 +N」是当天新增星标数。\n依据:「2,271 stars today」"
        assertEquals(true, Grounding.check("GitHub Trending 前三", a, page).found)
        // 回答には対応する番号がありません。引用はまだ画面上に表示される必要があります。コンパイルされた引用もブロックされます
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("(今日 +2,271)", ""), page).found)
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("「今日 +N」", "「Top N」"), page).found)
    }

    // 実際のテスト設定-android-バージョン: 画面が2行に分かれており、引用符内の文字に挟まれた数字(AQ3A)は別の数字として扱われません。
    @Test fun basisQuoteAcrossTwoLinesOfOneEntryLocates() {
        val about = listOf("当前 App:com.android.settings\nAndroid 版本\n15 AQ3A.240829.003")
        val a = "安卓版本是 Android 15(系统版本号 AQ3A.240829.003)。\n依据:关于手机页显示「Android 版本：15 AQ3A.240829.003」"
        assertEquals(true, Grounding.check("安卓版本", a, about).found)
        // 引用文の複数の段落は同じ場所にある必要があります。2 回読まれた段落は、配置されているとは見なされません。
        val split = listOf("当前 App:com.android.settings\nAndroid 版本\n16", "15 AQ3A.240829.003")
        assertEquals(false, Grounding.check("安卓版本", a, split).found)
    }
}
