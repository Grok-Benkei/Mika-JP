package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import java.math.BigDecimal
import kotlin.math.abs
import com.wangzan101.mika.core.tool.Verb

/** モデル宣言はコストのかかる操作です。フィールドは空白または切り詰められており、インターフェイス上ではそのように表示されます。 [app] はパッケージ名です。 */
data class ConfirmationRequest(val action: IrrevAction, val target: String, val content: String, val amount: String?, val app: String)

sealed interface DeclareResult {
    /** 宣言されたアプリはフロントエンドと矛盾している (またはフロントエンドが読み取れない) ため、ユーザーに引き渡されません。 */
    data object Mismatch : DeclareResult
    /** このタスクでは、ユーザーは同じアプリの同様の操作をすでに拒否しているため、再度要求されることはありません。 */
    data object Declined : DeclareResult
    /** ここでは支払わないで、乗っ取りに行きます。 */
    data object Unsupported : DeclareResult
    data object Pending : DeclareResult
}

sealed interface Permit {
    data object Allowed : Permit
    /** は同意しませんでした。 */
    data object NoGrant : Permit
    /** は同意しましたが、このステップに対応していません (または有効期限が切れています)。この同意は無効になりました。 */
    data class Mismatch(val reason: String) : Permit
}

/**
 * ユーザーは、お金がかかる操作に同意します (要求時に支払いのみが求められます)。次の一致する操作のみが許可されます。
 * は、宣言内容をバインドすることに同意します。同じフロントエンド アプリ、同様のアクション、画面上に表示されるオブジェクト (送信時にタイトル バー)、送信時に送信される単語は宣言と同じです。
 * 支払う文脈では申告金額が画面に表示されます。通常の価格・待遇・範囲は支払額として照合しません。
 * 正解か否かに関わらず、次ヒットの不可逆操作を使用します。タイピング以外の書き込み操作（アプリに戻る、開くなど）を実行した場合、
 * または [MAX_STEPS] ステップが完了しました。契約は無効です。今回のみ同意できます。ミッション開始時と終了時 [reset] の 1 つのミッション内でのみ有効です。
 * タスク ループとインターフェイス応答が異なるスレッドにある可能性があり、読み取りと書き込みの両方がロックされています。
 */
class ConfirmationGate {
    private class Grant(val request: ConfirmationRequest, val step: Int)

    private var pending: ConfirmationRequest? = null
    private var grant: Grant? = null
    private val declined = mutableSetOf<Pair<IrrevAction, String>>()

    @Synchronized fun declare(req: ConfirmationRequest, foregroundApp: String?): DeclareResult {
        if (req.action == IrrevAction.pay) return DeclareResult.Unsupported
        if (req.action to req.app in declined) return DeclareResult.Declined
        if (foregroundApp == null || foregroundApp != req.app) return DeclareResult.Mismatch
        pending = req
        return DeclareResult.Pending
    }

    /** [step] は回答のステップ番号であり、有効期限はこれに基づいて計算されます。 */
    fun decide(approved: Boolean, step: Int = 0) {
        decide(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny, step)
    }

    /** は、ユーザーの選択に従って 1 回限りの同意を記録します。 */
    @Synchronized fun decide(choice: ConfirmChoice, step: Int) {
        val req = pending ?: return
        pending = null
        if (!choice.approved) {
            declined += req.action to req.app
            return
        }
        grant = Grant(req, step)
    }

    /**
     * [screen] は現在の画面です。 [typing] は入力するテキストであり、この手順で Enter キーを押します (type_text)。
     * [seen] は、画像を見て再度確認した後に見つかったテキストです。画面の読み取り (自己描画インターフェイス) にテキストがない場合、被験者はそれを確認できます。
     * 送信する文字が読み取れない場合は認証されません（画面上の文字は認証できません）。同意する場合は、結果に関係なく使用してください。支払いは決して解放されません。
     */
    @Synchronized fun permits(hit: Irreversible, screen: ScreenSnapshot?, step: Int, typing: String? = null, seen: List<String> = emptyList()): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        val r = g.request
        val reason = when {
            hit.action == IrrevAction.pay -> "payment is never approved here"
            step - g.step > MAX_STEPS -> "the approval expired ($MAX_STEPS steps)"
            hit.action != r.action -> "approved ${r.action.name} but this step is ${hit.action.name}"
            screen?.packageName == null || screen.packageName != r.app -> "approved in ${r.app} but the screen shows ${screen?.packageName ?: "unknown"}"
            !targetShown(r, screen) && !(textless(screen) && seenShows(r, seen)) -> "the target \"${r.target}\" is not shown " + (if (r.action == IrrevAction.send) "in the title area" else "on the screen")
            r.action == IrrevAction.send && !contentMatches(r.content, screen, typing) && !(textless(screen) && seenShows(r, seen)) -> "the text to send is not exactly what was approved"
            amountBinds(hit, screen) && !amountShown(r.amount, screen, hit.matched) -> "the approved amount ${r.amount ?: "(none)"} is not on the screen"
            else -> return Permit.Allowed
        }
        return Permit.Mismatch(reason)
    }

    /**
     * ツールが確認を申告した画面外の操作では、承認した申告内容と完全一致する1回だけを許可する。
     * 承認がある場合は、判定結果にかかわらず消費する。
     */
    @Synchronized fun permitsExactly(ask: ConfirmationRequest, step: Int): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        return when {
            step - g.step > MAX_STEPS -> Permit.Mismatch("承認の有効期限（$MAX_STEPS ステップ）が切れました")
            g.request != ask -> Permit.Mismatch("今回の操作は承認した内容と一致しません")
            else -> Permit.Allowed
        }
    }

    /** 読み取り画面に文字のある要素が一つもない場合（独自描画やcanvas）。 */
    private fun textless(s: ScreenSnapshot) = s.elements.none { it.name.isNotBlank() }

    /** 宣言されたオブジェクトは、レビューによって確認されたテキスト (少なくとも 2 単語) に表示されます。 */
    private fun seenShows(r: ConfirmationRequest, seen: List<String>): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        return t.length >= 2 && seen.any { t in norm(it) }
    }

    /** は書き込み操作を実行しました。入力ボックスの入力とクリアは同意に影響しませんでしたが、残りの操作 (他の場所をクリック、スライド、戻る、アプリを開くなど) は無効になりました。 */
    /** 合意されたが使用されていない操作があります。 */
    val hasGrant: Boolean @Synchronized get() = grant != null

    /** ユーザーが同意したが、まだ使用されていない操作。そうでない場合は null です。 */
    val granted: ConfirmationRequest? @Synchronized get() = grant?.request

    /** [verb]: 実行されたばかりの書き込み操作のアクション。入力およびクリアは同意を無効にするものではありません (同意は入力されるステップです)。 */
    @Synchronized fun noteWrite(verb: Verb?) {
        if (verb !in KEEPS_GRANT) grant = null
    }

    @Synchronized fun reset() {
        pending = null
        grant = null
        declined.clear()
    }

    /**
     * オブジェクトは、画面上の編集不可のテキスト (入力ボックスへの入力はカウントされません) 内に少なくとも 2 単語存在する必要があります。
     * 送信時、画面上部のタイトル バー (チャット パートナーの名前が表示される部分) のみが認識され、その他のアクションは画面上のどこにでも可能です。
     */
    private fun targetShown(r: ConfirmationRequest, s: ScreenSnapshot): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        if (t.length < 2) return false
        return s.elements.any { e ->
            !e.editable && t in norm(e.name) && (r.action != IrrevAction.send || (e.bounds.size == 4 && e.bounds[1] <= TITLE_BAND))
        }
    }

    /**
     * 送信したいのは合意された段落です。送信をクリックすると、入力ボックス内の単語はその段落と等しく、type_text を押すと、入力される単語はそれと等しくなります。
     * 宣言はプレフィックスよりも切り詰められます (... で終わる)。
     */
    private fun contentMatches(content: String, s: ScreenSnapshot, typing: String?): Boolean {
        val capped = content.endsWith("…")
        val c = norm(content.removeSuffix("…"))
        fun same(x: String) = norm(x).let { if (capped) it.startsWith(c) else it == c }
        if (typing != null) return same(typing)
        return s.elements.any { it.editable && !it.hint && same(it.name) }
    }

    /** 明示的な注文・支払い・金額操作は常に照合します。提出と削除は決済画面や合計がある場合だけ金額を照合し、通常の待遇や価格との誤判定を防ぎます。 */
    private fun amountBinds(hit: Irreversible, s: ScreenSnapshot): Boolean = when (hit.action) {
        IrrevAction.order, IrrevAction.pay, IrrevAction.other -> true
        IrrevAction.send -> false
        IrrevAction.submit, IrrevAction.delete -> IrreversibleDetector.hasPayableAmount(s)
    }

    /**
     * 申告金額は画面上の通貨（￥/￥、元、$）付きの数値とのみ比較され、「25個売れた」「3マイナス25個」はカウントされません。入力ボックスとプロンプトはカウントされません。
     * 割引、減額、控除、運賃、クーポン、赤い封筒のすぐ隣にある金額、および保存された金額 (またはマイナス記号付き) はカウントされません。
     * 合計/実際の支払い/支払可能/合計がある場合: スクロール可能領域外の各合計は、宣言された金額と等しくなければなりません。すべてがスクロール可能な領域内にある場合は、ボタンに最も近いものを確認します。
     * は、総文字数や金額が読めない場合（同一行の次の段落は綴っても読めない場合）には解放されません。
     * 合計がない場合、スクロール可能領域外の最大金額と等しくなければなりません。番号のない申請は認められません。
     */
    private fun amountShown(amount: String?, s: ScreenSnapshot, button: String): Boolean {
        // 位取りを含む金額を先頭や末尾の数値だけで比較しない。通貨を問わず、換算に未対応の間は不一致として実行を止める。
        // 申告欄は金額専用なので、通貨の省略や「日本円」「ドル」でも位取りを検出する。
        if (amount?.let { IrreversibleDetector.UNSCALED_NUMBER.containsMatchIn(currencyFree(Hant.fold(it))) } == true) return false
        val declared = amount?.let { currencyFree(it) }?.let { NUMBER.find(it)?.value }?.toBigDecimalOrNull() ?: return false
        val expectedCurrency = currencyTag(amount.orEmpty())
        fun same(x: BigDecimal) = x.compareTo(declared) == 0
        val shown = s.elements.filter { !it.editable && !it.hint }
        if (shown.any { unscaledAmount(IrreversibleDetector.rowText(it, shown)) }) return false
        val scrollables = s.elements.filter { it.scrollable }
        fun outside(e: UiElement) = scrollables.none { inside(e, it) }
        val labels = shown.filter { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } }
        if (labels.isNotEmpty()) {
            val totals = labels.map { it to totalOf(it, shown, expectedCurrency) }
            if (totals.any { it.second == null }) return false
            val out = totals.filter { outside(it.first) }
            if (out.isNotEmpty()) return out.all { same(it.second!!) }
            val b = shown.firstOrNull { it.name.trim() == button }
            val near = totals.minByOrNull { (e, _) -> if (b == null) -top(e) else abs(centerY(e) - centerY(b)) }!!
            return same(near.second!!)
        }
        val largest = shown.filter(::outside).flatMap { amounts(it.name, expectedCurrency) }.maxOrNull() ?: return false
        return same(largest)
    }

    /** 合計ラベルの背後にある金額: 最初に単独で見てから、同じ行の右側の段落を横方向にまとめて見てください。 */
    private fun totalOf(label: UiElement, shown: List<UiElement>, expectedCurrency: String?): BigDecimal? {
        fun after(text: String) = TOTAL_LABELS.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 }?.let { text.substring(it + w.length) } }
            .firstNotNullOfOrNull { amounts(it, expectedCurrency).firstOrNull() }
        return after(Hant.fold(label.name)) ?: after(Hant.fold(IrreversibleDetector.rowText(label, shown)))
    }

    private fun currencyFree(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC).replace(THOUSANDS, "")

    private fun unscaledAmount(t: String) = IrreversibleDetector.UNSCALED_AMOUNT.containsMatchIn(currencyFree(Hant.fold(t)))

    /** ¥だけでは円と人民元を区別できない。明示された通貨が異なる場合だけ拒否する。 */
    private fun currencyTag(t: String): String? {
        val text = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC).uppercase(java.util.Locale.ROOT)
        return when {
            "円" in text || "JPY" in text -> "JPY"
            "元" in text || "CNY" in text || "RMB" in text -> "CNY"
            "$" in text || "USD" in text -> "USD"
            else -> null
        }
    }

    /** 通貨を含む金額の場合は、「割引」という語またはその横にあるマイナス記号を削除します。 */
    private fun amounts(name: String, expectedCurrency: String? = null): List<BigDecimal> {
        val t = currencyFree(Hant.fold(name))
        val matches = CURRENCY.findAll(t).toList()
        val firstCurrency = matches.firstOrNull()?.value?.let(::currencyTag)
        if (expectedCurrency != null && firstCurrency != null && expectedCurrency != firstCurrency) return emptyList()
        return matches.filter { m ->
            val actualCurrency = currencyTag(m.value)
            val before = t.substring(maxOf(0, m.range.first - DISCOUNT_WINDOW), m.range.first)
            (expectedCurrency == null || actualCurrency == null || expectedCurrency == actualCurrency) &&
                DISCOUNTS.none { Hant.fold(it) in before } && !before.trimEnd().endsWith("-")
        }.mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.toBigDecimalOrNull() }.toList()
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun centerY(e: UiElement) = if (e.bounds.size == 4) (e.bounds[1] + e.bounds[3]) / 2 else 0

    private fun inside(c: UiElement, p: UiElement): Boolean {
        val a = c.bounds; val b = p.bounds
        return a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    }


    private fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    companion object {
        /** 同意後、多くの手順を経て使用されない場合は無効となります。 */
        const val MAX_STEPS = 3
        const val MATCH_CHARS = 20
        /** 送信オブジェクトは、この行 (0 ～ 999) より上、つまり画面上部のタイトル バーの約 15% にある必要があります。 */
        const val TITLE_BAND = 150
        private val NUMBER = Regex("\\d+(\\.\\d+)?")
        private val CURRENCY = Regex("(?:[¥￥$＄]|JPY|CNY|USD)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:元|円|JPY|CNY|USD)", RegexOption.IGNORE_CASE)
        private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
        private val TOTAL_LABELS = IrreversibleDetector.TOTAL_LABELS
        private val DISCOUNTS = listOf("优惠", "减", "抵扣", "运费", "券", "红包", "已省", "割引", "値引", "クーポン", "送料", "還元", "ポイント")
        private const val DISCOUNT_WINDOW = 4
        private val KEEPS_GRANT = setOf(Verb.TypeText, Verb.ClearText)
    }
}
