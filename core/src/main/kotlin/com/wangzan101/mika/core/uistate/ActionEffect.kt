package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.visual.FrameChange
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** アプリまたは Web ページのインターフェイス状態に対するアクションの実際の影響: アクションの前後のインターフェイス状態 (要素テーブル、コントロール状態、入力フォーカス、場所) によって判断されます。画面の違いは状況証拠にすぎません。 [wire] は、effect.kind が結果に書き込まれる方法です。 */
enum class EffectKind(val wire: String) {
    /** インターフェイスは、このアクションに合わせて明らかに変更されました。 */
    Done("done"),
    /** には、ローカルな小さな変更 (フォーカス、スイッチ、1 つまたは 2 つの要素) しかないか、まだロード中の可能性があります。結果を見てください。 */
    SmallChange("small_change"),
    /** 何も変わっていません。同じ方法ではなく、別の方法で実行してください。 */
    NoEffect("no_effect"),
    /** は場所を変更しました。携帯電話のフロントデスクは別のアプリに変更され、Web ページは別のページに変更されました (アドレスまたはラベルが変更されました)。新しい場所の [NewPlace] を参照してください。 */
    PlaceChanged("place_changed");

    companion object {
        fun of(wire: String?): EffectKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * アクションの前後でのインターフェイス状態 (要素テーブル) の構造の違い。要素は、番号を参照せずに、内容 (ロール + 名前 + リソース ID + プロンプトの有無) に従って配置されます (番号は各画面読み取りに共通ではありません)。
 * は同じ内容、同じ位置、同じステータスですが、制御ステータス ([UiElement.states]) が変更されています。 [toggled]、同じ内容で位置が変わりました。 [moved] (スクロール、並べ替え)、
 * 同じ入力ボックス内の文字は [edited] に変更され、残りは [added]/[removed] になります。 [focusMoved]: 入力フォーカスを持つ要素が変更されました (またはフォーカスが表示または非表示になります)。
 * は情報要素 ([UiElement.meaningful]) のみを参照します。
 */
data class InterfaceDiff(
    val added: Int,
    val removed: Int,
    val moved: Int,
    val edited: Int,
    val toggled: Int,
    val focusMoved: Boolean,
    val total: Int,
) {
    val changes: Int get() = added + removed + moved + edited + toggled
    val none: Boolean get() = changes == 0 && !focusMoved

    /** には十分な変更があります (しきい値については [EffectTuning] を参照)。 */
    val major: Boolean get() = changes >= max(EffectTuning.MAJOR_MIN, ceil(total * EffectTuning.MAJOR_SHARE).toInt())

    companion object {
        fun between(before: ScreenSnapshot, after: ScreenSnapshot): InterfaceDiff {
            val a = before.elements.filter { it.meaningful }
            val b = after.elements.filter { it.meaningful }
            // 最初に同一のものと一致し、次にステータスのみが異なるもの (スイッチが切り替わっている) と一致し、次に移動されたものと一致し、次に同じ入力ボックス内の単語が変更されたものと一致します。
            val leftA = a.toMutableList()
            val leftB = b.toMutableList()
            pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds && x.states == y.states }
            val toggled = pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds }
            val moved = pairOff(leftA, leftB) { x, y -> key(x) == key(y) }
            val edited = pairOff(leftA, leftB) { x, y -> x.editable && y.editable && x.role == y.role && x.resourceId == y.resourceId && near(x, y) }
            val focusA = a.firstOrNull { it.focused }?.let(::place)
            val focusB = b.firstOrNull { it.focused }?.let(::place)
            return InterfaceDiff(leftB.size, leftA.size, moved, edited, toggled, focusA != focusB, max(a.size, b.size))
        }

        /** [match] を押して 2 つずつペアリングし、両側から削除して一致したペアに戻します。 */
        private fun pairOff(a: MutableList<UiElement>, b: MutableList<UiElement>, match: (UiElement, UiElement) -> Boolean): Int {
            var n = 0
            val it = a.iterator()
            while (it.hasNext()) {
                val x = it.next()
                val j = b.indexOfFirst { y -> match(x, y) }
                if (j >= 0) { b.removeAt(j); it.remove(); n++ }
            }
            return n
        }

        private fun key(e: UiElement) = listOf(e.role, e.name, e.resourceId.orEmpty(), e.hint.toString())
        private fun place(e: UiElement) = listOf(e.role, e.resourceId.orEmpty(), e.bounds.toString())

        private fun near(x: UiElement, y: UiElement): Boolean {
            if (x.bounds.size != 4 || y.bounds.size != 4) return x.resourceId != null
            return distance(x, y) <= EffectTuning.NEAR
        }
    }
}

/**
 * アクション後に到達した新しい場所: [name] は一般的な用語 (アプリのパッケージ名、Web サイトのホスト名、インターフェイスを読み取るときの「場所」と一致します)、
 * [kind] および [detail] は、このインターフェイスの詳細です (携帯電話の「アプリ」: {パッケージ}; Web ページ「ウェブ」: {url, title, tabId})。
 */
data class NewPlace(val name: String, val kind: String, val detail: JsonObject)

/**
 * が入力された後、入力ボックスが読み取られます。[text] は、現時点でボックス内の単語です (プロンプトが表示されるときは空の文字列です。パスワード ボックスは null なので読み取られません)。 [matches] は入力する内容と一致していますか?
 * (比較する場合はスペースを削除してください。一部のボックスでは番号グループにスペースが追加されます)。
 */
data class Readback(val text: String?, val secret: Boolean, val matches: Boolean)

/**
 * 影響を判断するために使用されるポートからの事実: [changed] ポートは、変更が変更されていないことをアクションで検証します。 [switchedTo] ポートでは、フロント デスクでアプリが変更されたことがわかります。
 * [verified] 入力が書き込まれた後、リードバックは一貫しています。 [loading] ポートは、ページがまだ静まっていないことを示します (ネットワークとナビゲーションがまだ進行中です)。
 * [onUserScreen] アクションにより、アプリがユーザーのホーム画面で開き、そこに留まります (ユーザーは現在それを使用しています)。ミカの画面には何も変化はなく、クリックもされていません。
 */
data class PortFacts(
    val changed: Boolean? = null,
    val switchedTo: String? = null,
    val verified: Boolean = false,
    val loading: Boolean = false,
    val onUserScreen: String? = null,
)

/**
 * アクションの実際の効果: タイプ、どこに移動したか (場所を変更するとき)、インターフェイス構造と画面の違い、入力のリードバック、およびまだロード中の可能性があります。
 * [treeBlind]: 変化は見られません (タイプは [EffectKind.NoEffect] のまま) が、この画面上のツリーはポイントを読み取ることができません (ツリーが使用できないか、ピースが欠落しており、ポイントがどの要素にもありません)。
 * はフォーカスを変更しただけですが、隠れた点も同じように見えます。クリックされていないとは言えません。
 */
data class ActionEffect(
    val kind: EffectKind,
    val place: NewPlace? = null,
    val diff: InterfaceDiff? = null,
    val frame: FrameChange? = null,
    val readback: Readback? = null,
    val loading: Boolean = false,
    val treeBlind: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        place?.let { put("to", it.name); put(it.kind, it.detail) }
        diff?.let { t ->
            if (t.added > 0) put("added", t.added)
            if (t.removed > 0) put("removed", t.removed)
            if (t.moved > 0) put("moved", t.moved)
            if (t.edited > 0) put("edited", t.edited)
            if (t.toggled > 0) put("toggled", t.toggled)
            if (t.focusMoved) put("focusMoved", true)
        }
        frame?.let { put("frameChanged", (it.whole * 100).roundToInt() / 100.0) }
        readback?.let { r ->
            put("readback", if (r.secret) HIDDEN else r.text?.take(READBACK_MAX))
            put("readbackMatches", r.matches)
        }
        if (loading) put("mayStillBeLoading", true)
        if (treeBlind) put("treeBlind", true)
    }

    companion object {
        const val HIDDEN = "(hidden)"
        private const val READBACK_MAX = 200

        /** 変化が見られず、クリックとジェスチャーのクリックアクションがないとは言えません（フォーカスが変更されるだけで、非表示状態ではツリーや画面が動かない可能性があります）。 */
        private val BLIND_VERBS = setOf(Verb.Tap, Verb.PhoneGesture)

        /**
         * この画面上のツリーは、このアクションが機能する場所を読み取ることができません。ツリーは使用できないか、ピースが欠落しており、点 (画面領域 [region] の中心) が情報のある要素上にありません。
         * は、ツリーが使用できない場合にのみ、ツリー内の要素ポイントに従って計算されます ([target] は null ではありません)。 [quality] は、十分であるかどうかを判断するためにこの画面で判断される最後のツリーであり、null であるかどうかは不明です。
         */
        internal fun treeBlind(verb: Verb, quality: TreeQuality?, before: ScreenSnapshot?, target: UiElement?, region: List<Int>?): Boolean {
            if (verb !in BLIND_VERBS) return false
            return when (quality) {
                TreeQuality.Unusable -> true
                TreeQuality.Partial -> {
                    if (target != null || region == null || region.size != 4) return false
                    val x = (region[0] + region[2]) / 2
                    val y = (region[1] + region[3]) / 2
                    before?.elements.orEmpty().none { e -> e.meaningful && e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3] }
                }
                else -> false
            }
        }

        /**
         * アクション前後のインターフェースの状態に応じて効果を判定します。 [before]/[after] が読み取れない場合は、ポートの事実と写真のみが判断されます。どちらも見つからない場合は、null になります (影響は報告されません)。
         * [target] は、アクションのターゲット要素 (モデルによって読み取られ、入力ボックスを見つけるために使用される要素) です。
         * ([Verb.TypeText]) と [typed] (入力する単語) を入力すると、[pressedEnter] は読み戻されません (ボックスがクリアされるか、Enter キーを押した後にページが変更される可能性があります)。
         *
         * タイミングが不明な場合は効果がありません。ポートが変更されたと示す場合、それは少なくとも小さな変更です。インターフェイスが移動している場合、またはポートがまだロード中であると表示している場合、それはわずかな変化にすぎません（まだロード中である可能性があります）。
         */
        fun classify(
            verb: Verb,
            before: ScreenSnapshot?,
            after: ScreenSnapshot?,
            port: PortFacts,
            frame: FrameChange?,
            target: UiElement? = null,
            typed: String? = null,
            pressedEnter: Boolean = false,
            secret: Boolean = false,
        ): ActionEffect? {
            // ポート フロント デスクがアプリを変更したことがわかりました。それが優先されます (2 つの測定値は切り替えの前または後である可能性があります)
            port.switchedTo?.let { return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it) }), frame = frame) }
            // がユーザーのホーム画面で開かれました: 場所が変更されましたが、ミカの画面上ではありませんでした
            port.onUserScreen?.let {
                return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it); put("onUserScreen", true) }), frame = frame)
            }
            val raw = judge(verb, before, after, port, frame, target, typed, pressedEnter, secret) ?: return null
            val loading = port.loading || after?.changedDuringRead == true || after?.let(::showsProgress) == true
            return when {
                raw.kind != EffectKind.NoEffect -> raw.copy(loading = loading && raw.kind == EffectKind.SmallChange)
                loading -> raw.copy(kind = EffectKind.SmallChange, loading = true)
                port.changed == true -> raw.copy(kind = EffectKind.SmallChange)
                else -> raw
            }
        }

        private fun judge(
            verb: Verb, before: ScreenSnapshot?, after: ScreenSnapshot?, port: PortFacts, frame: FrameChange?,
            target: UiElement?, typed: String?, pressedEnter: Boolean, secret: Boolean,
        ): ActionEffect? {
            if (before == null || after == null) {
                return when {
                    port.changed == true || frame?.visible == true -> ActionEffect(EffectKind.SmallChange, frame = frame)
                    port.changed == false -> ActionEffect(EffectKind.NoEffect, frame = frame)
                    else -> null
                }
            }
            moved(before, after)?.let { return ActionEffect(EffectKind.PlaceChanged, it, frame = frame) }
            val diff = InterfaceDiff.between(before, after)
            val readback = if ((verb == Verb.TypeText || verb == Verb.ClearText) && !pressedEnter) {
                readback(after, target, if (verb == Verb.ClearText) "" else typed, secret)
            } else null
            val kind = when {
                readback != null && !readback.secret && readback.matches -> EffectKind.Done
                // ポートが書き込まれた後、その場で読み戻され、一貫性がありましたが、このインターフェイスでボックス (またはパスワード ボックス) が見つかりませんでした: ポートに基づいて確認してください
                port.verified && (readback == null || readback.secret) && !pressedEnter -> EffectKind.Done
                readback != null && !readback.secret -> if (diff.none && frame?.visible != true) EffectKind.NoEffect else EffectKind.SmallChange
                diff.major -> EffectKind.Done
                diff.none -> when {
                    frame?.large == true && max(before.elements.count { it.meaningful }, after.elements.count { it.meaningful }) < EffectTuning.FEW_ELEMENTS -> EffectKind.Done
                    frame?.visible == true -> EffectKind.SmallChange
                    else -> EffectKind.NoEffect
                }
                else -> EffectKind.SmallChange
            }
            return ActionEffect(kind, diff = diff, frame = frame, readback = readback)
        }

        /** インターフェイス (読み込み中) にはプログレスバーなどのコントロールがあります。 */
        private fun showsProgress(s: ScreenSnapshot) = s.elements.any { it.role.contains("progress", ignoreCase = true) && UiStates.DISABLED !in it.states }

        /** アプリの変更またはページの変更：ウェブページのアドレス（#以降はカウントしない）とタグを確認し、携帯電話のフロントデスクでパッケージ名を確認してください。どれも null に置き換えられません。 */
        private fun moved(before: ScreenSnapshot, after: ScreenSnapshot): NewPlace? {
            val wb = before.web; val wa = after.web
            if (wb != null || wa != null) {
                if (wb == null || wa == null) return null
                if (wb.tabId != wa.tabId || wb.url.substringBefore('#') != wa.url.substringBefore('#')) {
                    return NewPlace(after.packageName ?: wa.url, "web", buildJsonObject {
                        put("egressBlocked", true); put("reason", "browser_or_webview"); put("tabId", wa.tabId)
                        com.wangzan101.mika.core.security.EgressPolicy.permittedHost(wa.url)?.let { put("currentSite", it) }
                    })
                }
                return null
            }
            val pa = after.packageName ?: return null
            return if (before.packageName != null && before.packageName != pa) NewPlace(pa, "app", buildJsonObject { put("package", pa) }) else null
        }

        /**
         * 現時点での入力ボックス内の単語: 同じボックス (同じロール、同じリソース ID、同様の場所) が優先され、次にフォーカスのある入力ボックスが続きます。見つからない場合は null になります (リードバックは報告されません)。
         * [want] は予期される単語です (クリアされた場合は空の文字列)。 null の場合は読み戻されません。
         */
        private fun readback(after: ScreenSnapshot, target: UiElement?, want: String?, secret: Boolean): Readback? {
            want ?: return null
            val fields = after.elements.filter { it.editable }
            val field = target?.let { t ->
                fields.filter { it.role == t.role && it.resourceId == t.resourceId }.minByOrNull { distance(it, t) }
                    ?.takeIf { distance(it, t) <= EffectTuning.SAME_FIELD }
            } ?: fields.firstOrNull { it.focused } ?: return null
            if (secret || field.secret) return Readback(null, secret = true, matches = false)
            val text = if (field.hint) "" else field.name
            return Readback(text, secret = false, matches = norm(text) == norm(want))
        }

        private fun norm(s: String) = s.filterNot { it.isWhitespace() }
    }
}

/** 2 つの要素の中心間の距離 (0 ～ 999 マイル)。欠落している範囲は 0 です。 */
private fun distance(a: UiElement, b: UiElement): Int {
    if (a.bounds.size != 4 || b.bounds.size != 4) return 0
    val dx = (a.bounds[0] + a.bounds[2]) / 2 - (b.bounds[0] + b.bounds[2]) / 2
    val dy = (a.bounds[1] + a.bounds[3]) / 2 - (b.bounds[1] + b.bounds[3]) / 2
    return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
}

/** は、エフェクトに応じた文をモデルに与えます (prompts/フィードバック/tools.md のeffect.*)。明らかに変わっているので、これ以上は言いません。 */
internal object EffectFeedback {
    /** [portSwitched]: ポートはフロント デスクに報告され、切り替えられました (その説明はすでに結果にあります)。そのため、繰り返しません。 */
    fun hint(e: ActionEffect, verb: Verb, portSwitched: Boolean): String? {
        val r = e.readback?.takeIf { !it.secret && !it.matches }
        val differs = r?.let { Feedback.format("effect.readback_differs", "readback" to JsonPrimitive(it.text.orEmpty()).toString()) }
        return when (e.kind) {
            EffectKind.Done -> null
            EffectKind.PlaceChanged -> if (portSwitched) null else Feedback.format("effect.place_changed", "to" to e.place?.name)
            EffectKind.SmallChange -> differs ?: Feedback[if (e.loading) "effect.loading" else "effect.small_change"]
            EffectKind.NoEffect -> listOfNotNull(
                differs,
                Feedback[when {
                    e.treeBlind -> "effect.no_visible_change"
                    verb == Verb.Back -> "effect.no_effect.back"
                    verb == Verb.Scroll || verb == Verb.Swipe -> "effect.no_effect.scroll"
                    else -> "effect.no_effect"
                }],
            ).joinToString(" ")
        }
    }
}
