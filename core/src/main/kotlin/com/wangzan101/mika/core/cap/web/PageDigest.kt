package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * web_read_page で一度必要なもの: [url] は、現在のタグを読み取るために null であり、一時的なバックグラウンド タグで読み取られます。 [unfold] が true の場合、折りたたまれたリスト ブロックの全文も表示されます
 * (セグメントのオフセットは同じ展開に従って計算されます)。
 */
data class PageRead(
    val maxChars: Int,
    val offset: Int,
    val unfold: Boolean = false,
    val url: String? = null,
    /** 読む前にスクロールして遅延読み込みをトリガーし、テキストが安定するまで待ちます。証拠を検証して収集するとき、それは虚偽であり、ページは移動せずに読むことしかできません。 */
    val lazy: Boolean = true,
)

/** このページの読み方: テキストは十分ですか、最初にログインする必要がありますか、拒否されますか、コンテンツは主に写真ですか? [wire] はモデルの記述方法です。 */
enum class ReadVerdict(val wire: String) {
    OK("ok"), THIN("thin"), NEEDS_SIGN_IN("needs_sign_in"), REFUSED("refused"), IMAGE_TEXT("image_text"),
}

/**
 * ページ読み取りの品質信号: [chars] はページ全体のテキスト数 (今回の折り方による)、[textDensity] は本文中の非リンクテキストの割合、
 * [cause] は、判断根拠の簡単な説明です。 [published]/[updated] はページ自体によって宣言された時間です。 [dialogs] は開いているダイアログ ボックスの数です (テキストの先頭に置かれます)。
 * [folded] は、1 行に折りたたまれたリスト ブロックの数です。
 */
data class ReadQuality(
    val chars: Int,
    val textDensity: Double,
    val verdict: ReadVerdict,
    val cause: String?,
    val published: String? = null,
    val updated: String? = null,
    val dialogs: Int = 0,
    val folded: Int = 0,
)

/**
 * ページ スクリプト (page.js ダイジェスト) のページ全体の読み取り結果がここに渡されます。どのリスト ブロックを折りたたむかを決定し、テキストを詳しく説明し、品質を判断し、ステートメントの時間を抽出します。
 * ページのスクリプトは構造 (ランドマークの役割、リンクの割合、繰り返しの下部構造、画像の位置) に従ってのみ収集され、判断はすべてここにあり、Web サイトによって区別されません。
 * ページ スクリプトの候補ブロックを選択するためのしきい値もここで指定され ([scriptOptions])、両側にコピーが 1 つだけ存在します。
 */
object PageDigest {
    /** テキストが薄いと思われるよりも短いです。 */
    const val THIN_CHARS = 200
    /** 候補ブロック: 少なくともこの数のリンク、および少なくともこれだけのリンク テキストを占めています (ページ スクリプトはそれに応じて候補を選択します。ここでは慎重に判断します)。 */
    const val CANDIDATE_MIN_LINKS = 5
    const val CANDIDATE_LINK_SHARE = 0.5
    /** テキストに対するリンク テキストの比率は、リンク (推奨事項、ディレクトリ) のリストです。 */
    const val LINK_LIST_SHARE = 0.7
    /** サブ要素が同じ構造 (カード リスト) を繰り返す場合、リンク率は十分です。 */
    const val REPEATED_LINK_SHARE = 0.5
    const val MIN_REPEAT = 5
    /** ランドマーク ブロック (ナビゲーション、フッター、サイドバー) へのリンクは、折りたたまれる前に少なくともこの長さである必要があります。テキストベースのサイドバーとフッターは、それに応じて読む必要があります。 */
    const val LANDMARK_LINK_SHARE = 0.3
    /** リストブロックとランドマーク以外のテキストの比率はこちらです。テキストそのもの（カタログページ、検索結果ページ）であり、閉じられていません。 */
    const val DOMINANT_SHARE = 0.4
    /** 折りたたんだ後、少なくともこの量のテキストを残す必要があります。そうでない場合は、最大のブロックから始めて元に戻します。 */
    const val MIN_LEFT_SHARE = 0.1
    /** ページの先頭を占める写真とキャンバスの割合はここまでで、単語が少ないため、image_text とみなされます。 */
    const val PICTURE_SHARE = 0.5
    const val PICTURE_MAX_CHARS = THIN_CHARS * 4
    /** アイコン、アバター、ディバイダーなど、この辺の長さ (CSS ピクセル) より小さい画像はカウントされません。 */
    const val MIN_PICTURE_PX = 48
    /** ページ全体のテキストに対するログイン ボックス (パスワード ボックスがある領域) のテキストの割合は次のとおりです。このページはログインページです。 */
    const val SIGN_IN_BOX_SHARE = 0.5
    private const val COVER_GRID = 64
    private const val PREVIEW_ITEMS = 6
    private const val PREVIEW_CHARS = 120
    private val LANDMARKS = setOf("navigation", "footer", "complementary")

    /** これらの単語がログイン ボックス、タイトル、ダイアログ ボックスに表示され、パスワード ボックスがある場合は、最初にログインする必要があります。空白スペースは削除され、比較時に大文字と小文字は区別されません。 */
    private val SIGN_IN_WORDS = listOf("登录", "登入", "登陆", "signin", "login", "logon", "账号", "帐号", "密码", "password")

    /** ページ スクリプト ダイジェスト パラメーター (候補ブロックしきい値)。 */
    val scriptOptions: String get() = """{"minLinks":$CANDIDATE_MIN_LINKS,"linkShare":$CANDIDATE_LINK_SHARE}"""

    /** リンク: [group] は、それが含まれるリスト ブロックです (ブロック内にない場合は -1)。 */
    data class Link(val text: String, val url: String, val group: Int)

    /** テキストの段落: テキストの行、または折りたたみ可能なリスト ブロック。 */
    sealed interface Part {
        data class Line(val text: String) : Part
        /**
         * [kind] は、ナビゲーション/フッター/補完 (ランドマークによる) またはリンク (リンクの割合によって認識されるリスト) です。 [lines] はブロック内のテキストです。
         * [chars]/[linkChars] はブロック内のすべてのテキストとリンク テキストの数、[repeat] は同じ構造内のサブ要素の数、[links] はブロック内のリンクの数です。
         */
        data class Group(
            val kind: String, val label: String, val lines: List<String>,
            val chars: Int, val linkChars: Int, val repeat: Int, val links: Int,
        ) : Part {
            val landmark: Boolean get() = kind in LANDMARKS
            val linkShare: Double get() = if (chars > 0) linkChars.toDouble() / chars else 0.0
        }
    }

    /** ページ上の画像の位置 (ドキュメント座標、CSS ピクセル)。 */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 判定に使用されるページサイン（すべてページスクリプトから来ています）。 */
    data class Signals(
        val passwordFields: Int = 0,
        /** ページ全体のテキストに対するログイン ボックス自体のテキストの比率 (テキストを除く、パスワード ボックスから上の最​​大のブロック。フォームまたはダイアログ ボックスに遭遇すると停止します)。 */
        val signInBoxShare: Double = 0.0,
        /** 「ログイン」という単語が見つかる場所: ログイン ボックスのテキスト、ページ タイトル、ダイアログ ボックスの先頭。 */
        val signInText: List<String> = emptyList(),
        /** 目を引く場所のテキスト (タイトル、h1 ～ h3、ダイアログ ボックスの先頭) は、傍受と判定に使用されます。 */
        val prominent: List<String> = emptyList(),
        /** ページの先頭の領域 (幅、高さ) とその内部の写真、キャンバス、ビデオ。 */
        val regionW: Int = 0,
        val regionH: Int = 0,
        val pictures: List<Box> = emptyList(),
        val frames: List<String> = emptyList(),
        val textChars: Int = 0,
        val linkChars: Int = 0,
        /** ページで宣言された時刻: (ソース、元の値)、ソースは ld:datePublished、meta:article:published_time、itemprop:dateModified、time などです。 */
        val dates: List<Pair<String, String>> = emptyList(),
    )

    data class Digest(
        val url: String,
        val title: String,
        val dialogs: List<List<String>>,
        val parts: List<Part>,
        val links: List<Link>,
        val images: List<Pair<String, String>>,
        val signals: Signals,
    )

    /** はまとめた結果です。今回数個片付けた結果が[folded]です。 */
    data class Rendered(val text: String, val links: List<Pair<String, String>>, val folded: Int)

    // ---------- 分析 ----------

    fun parse(o: JsonObject): Digest {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun int(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun strings(e: JsonElement?) = (e as? JsonArray).orEmpty().map(::str).filter { it.isNotEmpty() }
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
            when (p) {
                is JsonPrimitive -> str(p).takeIf { it.isNotEmpty() }?.let(Part::Line)
                is JsonObject -> Part.Group(str(p["g"]), str(p["label"]), strings(p["lines"]), int(p["chars"]), int(p["linkChars"]), int(p["repeat"]), int(p["links"]))
                else -> null
            }
        }
        val links = (o["links"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Link(str(m["text"]), url, (m["p"] as? JsonPrimitive)?.intOrNull ?: -1)
        }
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["alt"]) to url
        }
        val s = o["signals"] as? JsonObject
        val dates = (s?.get("dates") as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val v = str(m["v"]).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["k"]) to v
        }
        val region = (s?.get("region") as? JsonArray).orEmpty().map(::int)
        val pictures = (s?.get("pictures") as? JsonArray).orEmpty().mapNotNull { e ->
            val a = (e as? JsonArray)?.map(::int)?.takeIf { it.size == 4 } ?: return@mapNotNull null
            Box(a[0], a[1], a[2], a[3])
        }
        return Digest(
            url = str(o["url"]), title = str(o["title"]),
            dialogs = (o["dialogs"] as? JsonArray).orEmpty().map(::strings).filter { it.isNotEmpty() },
            parts = parts, links = links, images = images,
            signals = Signals(
                passwordFields = int(s?.get("password")),
                signInBoxShare = num(s?.get("signInShare")),
                signInText = strings(s?.get("signInText")),
                prominent = strings(s?.get("prominent")),
                regionW = region.getOrElse(0) { 0 }, regionH = region.getOrElse(1) { 0 },
                pictures = pictures,
                frames = strings(s?.get("frames")),
                textChars = int(s?.get("textChars")),
                linkChars = int(s?.get("linkChars")),
                dates = dates,
            ),
        )
    }

    // ---------- 閉じる ----------

    /**
     * どのブロックが折りたたまれているか ([Digest.parts] のブロックのシリアル番号に従って、ブロックのみがカウントされます)。十分なリンクがある場合、ランドマーク ブロックが収集されます。リンク リストには、できるだけ多くのリンクが含まれます。
     * であり、ランドマーク (つまりテキスト自体) 以外のテキストの大部分を占めることはできません。最大のブロックがリンク リストで、ブロックの外側のテキストよりも多くのテキストがある場合、それがメイン テキストとなり、含まれません。
     * が収集され、残りのテキストが [MIN_LEFT_SHARE] 未満の場合は、最大のブロックから順に戻されます。
     */
    fun foldSet(parts: List<Part>): Set<Int> {
        val groups = parts.filterIsInstance<Part.Group>()
        if (groups.isEmpty()) return emptySet()
        fun Part.size() = when (this) { is Part.Line -> text.length + 1; is Part.Group -> lines.sumOf { it.length + 1 } }
        val total = parts.sumOf { it.size() }
        val free = parts.filterIsInstance<Part.Line>().sumOf { it.size() }
        val outsideLandmarks = free + groups.filter { !it.landmark }.sumOf { it.size() }
        val largest = groups.indices.maxBy { groups[it].size() }
        val fold = groups.indices.filter { i ->
            val g = groups[i]
            val wanted = if (g.landmark) g.linkShare >= LANDMARK_LINK_SHARE
            else (g.linkShare >= LINK_LIST_SHARE || (g.repeat >= MIN_REPEAT && g.linkShare >= REPEATED_LINK_SHARE)) &&
                g.size() < outsideLandmarks * DOMINANT_SHARE
            // 最大のブロックがリンク リストで、ブロックの外側のテキストよりも多くのテキストがある場合、それがメイン テキストになります (ランドマークはページ自身のステートメントに従って受け入れられます)
            wanted && !(i == largest && !g.landmark && g.size() > free)
        }.toMutableSet()
        while (fold.isNotEmpty() && total - fold.sumOf { groups[it].size() } < total * MIN_LEFT_SHARE) {
            fold.remove(fold.maxBy { groups[it].size() })
        }
        return fold
    }

    /** はテキストを綴ります。ダイアログ ボックスが前面にあり、折りたたまれたブロックがそれぞれ 1 行を占めます。リンクテーブルが表示されている限り、同じアドレスは一度だけ残ります。 */
    fun render(d: Digest, unfold: Boolean): Rendered {
        val folded = if (unfold) emptySet() else foldSet(d.parts)
        val lines = ArrayList<String>()
        for (dl in d.dialogs) {
            lines += "<<dialog>>"
            lines += dl
            lines += "<<end of dialog>>"
        }
        var groupIndex = -1
        for (p in d.parts) when (p) {
            is Part.Line -> lines += p.text
            is Part.Group -> {
                groupIndex++
                if (groupIndex in folded) lines += collapsed(p) else lines += p.lines
            }
        }
        val links = d.links.filter { it.group < 0 || it.group !in folded }.distinctBy { it.url }.map { it.text to it.url }
        return Rendered(lines.joinToString("\n"), links, folded.size)
    }

    private fun collapsed(g: Part.Group): String {
        val name = g.kind + (g.label.takeIf { it.isNotBlank() }?.let { " \"${it.take(60)}\"" } ?: "")
        val sample = g.lines.take(PREVIEW_ITEMS).joinToString(" | ").let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…" else it }
        val count = if (g.links > 0) "${g.links} links" else "${g.lines.size} lines"
        return "<<collapsed $name · $count · $sample>>"
    }

    // ---------- 判決 ----------

    /**
     * このページがどの程度読みやすいかを判断します。 [check] は、Web ページの障害物判定です (目を引くテキスト、クロスソース フレームワーク、および HTTP ステータスを使用して行われます。[barrierFacts] を参照)。
     * [httpStatus] は、メインドキュメントの HTTP エラーステータスです。まず拒否を確認し、次にログインを確認し、次に写真かどうかを確認し、最後に文字数を確認します。
     */
    fun verdict(chars: Int, s: Signals, check: ScreenCheck?, httpStatus: Int?): Pair<ReadVerdict, String?> {
        val reason = check?.takeoverReason
        if (reason == "captcha" || reason == "access_blocked") return ReadVerdict.REFUSED to (check.cause ?: reason)
        if (reason == ScreenDiagnosis.UNSUPPORTED_BROWSER) return ReadVerdict.REFUSED to "the site refuses this browser"
        if (httpStatus != null && httpStatus >= 400 && chars < PICTURE_MAX_CHARS) return ReadVerdict.REFUSED to "HTTP $httpStatus"
        if (s.passwordFields > 0) {
            val cue = signInCue(s.signInText)
            if (cue != null && (s.signInBoxShare >= SIGN_IN_BOX_SHARE || chars < PICTURE_MAX_CHARS)) return ReadVerdict.NEEDS_SIGN_IN to "password field and \"$cue\""
        } else if (reason == "login" && chars < PICTURE_MAX_CHARS) {
            return ReadVerdict.NEEDS_SIGN_IN to (check.cause ?: "login")
        }
        val cover = pictureShare(s)
        if (cover >= PICTURE_SHARE && chars < PICTURE_MAX_CHARS) return ReadVerdict.IMAGE_TEXT to "pictures cover ${(cover * 100).toInt()}% of the page top"
        if (chars < THIN_CHARS) return ReadVerdict.THIN to "$chars characters of text"
        return ReadVerdict.OK to null
    }

    private fun signInCue(texts: List<String>): String? {
        val text = texts.joinToString("\n").lowercase().filterNot(Char::isWhitespace)
        return SIGN_IN_WORDS.firstOrNull { it in text }
    }

    /**
     * 画像で覆われているページ先頭の領域の割合: 領域全体にグリッドを広げ、グリッドの中心がどの画像に当たるかを数えます。重なった写真は 1 回だけカウントされます。
     * の辺の長さが [MIN_PICTURE_PX] より小さい場合はカウントされません。
     */
    fun pictureShare(s: Signals): Double {
        val w = s.regionW
        val h = s.regionH
        if (w <= 0 || h <= 0) return 0.0
        val boxes = s.pictures.filter { it.w >= MIN_PICTURE_PX && it.h >= MIN_PICTURE_PX }
        if (boxes.isEmpty()) return 0.0
        val cols = COVER_GRID
        val rows = maxOf(1, (COVER_GRID.toLong() * h / w).toInt())
        var covered = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5) * w / cols
            val y = (r + 0.5) * h / rows
            if (boxes.any { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }) covered++
        }
        return covered.toDouble() / (rows * cols)
    }

    /** から [WebBarrier.classify] までの事実: 目立つ場所のテキストのみ (記事中の「認証コード」の記載はカウントされません)、パスワード ボックスは個別に判断されます。 */
    fun barrierFacts(d: Digest, httpStatus: Int?, host: String?): WebFacts = WebFacts(
        base = ScreenFacts(
            currentApp = host, hasWindow = true, readableElements = 1, changing = false, blackFrames = emptyList(),
            texts = d.signals.prominent + d.dialogs.flatten(), passwordField = false,
        ),
        httpStatus = httpStatus, frameUrls = d.signals.frames, host = host,
    )

    /** テキスト内のリンクではないテキストの割合（小数点以下 2 桁）。言葉は0です。 */
    fun density(s: Signals): Double {
        if (s.textChars <= 0) return 0.0
        val share = 1.0 - s.linkChars.coerceAtMost(s.textChars).toDouble() / s.textChars
        return Math.round(share * 100) / 100.0
    }

    // ---------- 時間 ----------

    /**
     * ページ ステートメントのリリース時間と更新時間: 最初に構造化ステートメント (JSON-LD、次にメタ、次に itemprop) を確認し、最後にテキスト内の <time> を確認します。
     * 同じカテゴリ内で、表示されている順に押してください。判読できない値はスキップされます。
     */
    fun dates(s: Signals): Pair<String?, String?> {
        fun rank(src: String) = when {
            src.startsWith("ld:") -> 0
            src.startsWith("meta:") -> 1
            src.startsWith("itemprop:") -> 2
            else -> 3
        }
        fun pick(kind: (String) -> Boolean) = s.dates.withIndex()
            .filter { kind(it.value.first.lowercase()) }
            .sortedWith(compareBy({ rank(it.value.first) }, { it.index }))
            .firstNotNullOfOrNull { DeclaredTime.read(it.value.second) }
        val published = pick { k -> PUBLISHED_HINTS.any { it in k } || k == "time" || k == "time:pubdate" || k in PLAIN_DATE_KEYS }
        val updated = pick { k -> UPDATED_HINTS.any { it in k } }
        return published to updated
    }

    private val PUBLISHED_HINTS = listOf("published", "datecreated", "issued", "created", "pubdate", "publishdate", "publish_date")
    private val UPDATED_HINTS = listOf("modified", "updated", "lastmod", "revised")
    private val PLAIN_DATE_KEYS = setOf("meta:date", "meta:dc.date")

    /** 全ページ読み取り品質。 */
    fun quality(d: Digest, r: Rendered, check: ScreenCheck?, httpStatus: Int?): ReadQuality {
        val (v, cause) = verdict(r.text.length, d.signals, check, httpStatus)
        val (pub, upd) = dates(d.signals)
        return ReadQuality(r.text.length, density(d.signals), v, cause, pub, upd, d.dialogs.size, r.folded)
    }
}

/**
 * のページに書かれている時刻はISO-8601です。数値フィールドを順番に取り出します: 4 桁の年、月、日、および場合によっては時、分、秒 (秒の小数部は切り捨てられます)。
 * 時間の後に Z または ±時間[:分] が続くのがタイムゾーンです。年、月、日の間の任意の記号 (-、/、.、年、月、日) を使用できます。年、月、日が認識できない場合、値が無効であれば null になります。
 */
object DeclaredTime {
    private class Scan(val s: String) {
        var i = 0
        fun skipNonDigits() { while (i < s.length && !s[i].isDigit()) i++ }
        fun digits(): String {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i)
        }
        fun peek(): Char? = s.getOrNull(i)
    }

    fun read(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 60) return null
        val sc = Scan(text)
        sc.skipNonDigits()
        val year = sc.digits().takeIf { it.length == 4 }?.toInt() ?: return null
        val nums = ArrayList<Int>()
        // 月と日：セパレータで区切る
        repeat(2) {
            val sep = sc.peek() ?: return null
            if (sep.isDigit() || sep.isWhitespace() && nums.isEmpty()) return null
            sc.i++
            val n = sc.digits().takeIf { it.length in 1..2 } ?: return null
            nums += n.toInt()
        }
        val date = runCatching { LocalDate.of(year, nums[0], nums[1]) }.getOrNull() ?: return null
        if (sc.peek() == '日') sc.i++
        // 時刻: T またはスペースの後に時: 分 [: 秒]
        val tStart = sc.i
        if (sc.peek() == 'T' || sc.peek() == 't' || sc.peek() == ' ') sc.i++ else return date.toString()
        val hour = sc.digits().takeIf { it.length in 1..2 }?.toInt()
        if (hour == null || sc.peek() != ':') { sc.i = tStart; return date.toString() }
        sc.i++
        val minute = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return date.toString()
        var second = 0
        if (sc.peek() == ':') {
            sc.i++
            second = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return null
        }
        if (sc.peek() == '.' || sc.peek() == ',') { sc.i++; sc.digits() }
        val time = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
        val local = "$date" + "T" + "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        return local + (zone(sc) ?: "")
    }

    /** Z、+08:00、+0800、-05 などのタイムゾーン。 nullではありません。 */
    private fun zone(sc: Scan): String? {
        while (sc.peek() == ' ') sc.i++
        val c = sc.peek() ?: return null
        if (c == 'Z' || c == 'z') return "Z"
        if (c != '+' && c != '-') return null
        sc.i++
        val a = sc.digits()
        val (hh, mm) = when {
            a.length == 4 -> a.substring(0, 2) to a.substring(2)
            a.length in 1..2 && sc.peek() == ':' -> { sc.i++; a to sc.digits() }
            a.length in 1..2 -> a to "00"
            else -> return null
        }
        val h = hh.toIntOrNull() ?: return null
        val m = mm.toIntOrNull() ?: return null
        if (h > 14 || m > 59) return null
        if (h == 0 && m == 0) return "Z"
        return "%c%02d:%02d".format(c, h, m)
    }
}
