package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.CdpKey
import com.wangzan101.mika.core.uistate.CdpKeys
import com.wangzan101.mika.core.uistate.KeyCombo
import com.wangzan101.mika.core.uistate.KeyModifier

// Web ページ上の実際のマウスとキーボード (入力フィールド)。座標は常にメイン フレーム ビューポートの CSS ピクセルであり、ページ スクリプトによって指定されたものと一致します。
// のリズム (トラック、ホールド期間、キー間隔、ホイール分割) は [InputPacing] によって与えられ、デフォルトの [InputPacing.Instant] ではポーズが追加されません。
// スクロール ホイールは、表示されているラベルでのみ使用できます。背景ラベルのマウスホイールはハングし、このページ以降のすべての入力コマンドはブロックされます (呼び出し元が判断する責任があります)。

/** は、ビュー ピクセルと CSS ピクセル間の変換です。 [scale] は、各 CSS ピクセルが占めるビュー ピクセル数 (ビューの幅 ÷ ページのビューポートの幅) です。 */
object CdpGeometry {
    fun scale(viewWidth: Int, cssViewportWidth: Double): Double =
        if (viewWidth > 0 && cssViewportWidth > 0) viewWidth / cssViewportWidth else 1.0

    fun toCss(viewPx: Int, scale: Double): Double = if (scale > 0) viewPx / scale else viewPx.toDouble()
}

// ---------- 1 つの入力アクション ----------

/**
 * 入力アクションの結果。 [NotSent]: ページを変更するイベントさえも送信されませんでした (セッションが切断され、最初の送信がプロトコルによって拒否されました)。
 * 元の入力をやり直しても安全です。 [Uncertain]: イベント送信後に失敗(タイムアウト含む)しました。ページは受信された可能性があり、再度受信することはできません。
 * このとき、押されたマウスボタンとキーボードボタンは可能な限り持ち上げられています。
 */
sealed interface InputOutcome<out T> {
    data class Done<T>(val value: T) : InputOutcome<T>
    data class NotSent(val error: Throwable) : InputOutcome<Nothing>
    data class Uncertain(val error: Throwable) : InputOutcome<Nothing>
}

/**
 * 入力アクションで発行されたすべてのコマンドはそれを通過します。ページに到着したイベントがあったかどうか、およびその時点でマウス ボタンとキーボード キーが押されたかどうかに注目してください。 失敗すると、それに応じて
 * が発生します。読み取り専用クエリ (ビューポートの取得、読み取りオプション) はイベントとしてカウントされません。
 */
class InputTrack internal constructor(
    val session: CdpSession,
    private val timeoutMs: Long,
    val pacing: InputPacing = InputPacing.Instant,
) {
    var mayHaveDelivered = false
        private set
    private var pressedAt: Pair<Double, Double>? = null
    private var dragging = false
    private val heldKeys = ArrayList<CdpKey>()

    /** はページのコマンドを変更します。送信前にセッションが切断されました: 配信されませんでした。契約はエラーを返しました。この問題は発効していません。以前に送信されていない場合は、まだ配信されていません。 */
    internal suspend fun effect(method: String, params: JsonObject): JsonObject {
        if (session.closed.value) throw CdpConnectionClosedException("CDP session closed before $method")
        val before = mayHaveDelivered
        mayHaveDelivered = true
        try {
            return session.send(method, params, timeoutMs = timeoutMs)
        } catch (e: CdpException) {
            mayHaveDelivered = before
            throw e
        }
    }

    internal suspend fun query(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        session.send(method, params, timeoutMs = timeoutMs)

    internal suspend fun mouse(type: String, x: Double, y: Double, button: String, buttons: Int, clickCount: Int) {
        val s = session.inputScale
        // まず記録してから送る: この送信を押すのがタイムアウトしても、既に押されている可能性がある
        if (type == "mousePressed") pressedAt = x to y
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", type); put("x", x * s); put("y", y * s)
                put("button", button); put("buttons", buttons); put("clickCount", clickCount)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
        if (type == "mouseReleased") pressedAt = null
    }

    internal suspend fun wheel(x: Double, y: Double, deltaY: Double) {
        val s = session.inputScale
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", "mouseWheel"); put("x", x * s); put("y", y * s)
                put("button", "none"); put("buttons", 0); put("deltaX", 0); put("deltaY", deltaY)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
    }

    /** リズムに合わせて少し止まる; [Instant][InputPacing.Instant] のときは止まらない。 */
    internal suspend fun pause(ms: Long) {
        if (ms > 0) delay(ms)
    }

    internal suspend fun drag(type: String, x: Double, y: Double, data: JsonObject) {
        dragging = true
        val s = session.inputScale
        effect("Input.dispatchDragEvent", buildJsonObject { put("type", type); put("x", x * s); put("y", y * s); put("data", data) })
        if (type == "drop") { dragging = false; pressedAt = null }
    }

    internal suspend fun key(type: String, k: CdpKey, modifiers: Int, text: String?) {
        if (type != "keyUp") heldKeys += k
        effect(
            "Input.dispatchKeyEvent",
            buildJsonObject {
                put("type", type)
                if (modifiers != 0) put("modifiers", modifiers)
                put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
                if (text != null) { put("text", text); put("unmodifiedText", text) }
            },
        )
        if (type == "keyUp") heldKeys.remove(k)
    }

    /** 押さえているキーをできるだけ離す: 各送信には短い上限があり、失敗しても気にしない（ページの Input が既に固まっている可能性がある）。 */
    internal suspend fun release() {
        suspend fun quiet(method: String, params: JsonObject) {
            runCatching { session.send(method, params, timeoutMs = RELEASE_TIMEOUT_MS) }
        }
        if (dragging) quiet("Input.cancelDragging", JsonObject(emptyMap()))
        pressedAt?.let { (x, y) ->
            quiet("Input.dispatchMouseEvent", buildJsonObject {
                put("type", "mouseReleased"); put("x", x * session.inputScale); put("y", y * session.inputScale)
                put("button", "left"); put("buttons", 0); put("clickCount", 1); put("pointerType", "mouse")
            })
        }
        for (k in heldKeys.asReversed()) {
            quiet("Input.dispatchKeyEvent", buildJsonObject {
                put("type", "keyUp"); put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
            })
        }
        pressedAt = null; dragging = false; heldKeys.clear()
    }

    companion object {
        const val RELEASE_TIMEOUT_MS = 1_000L
    }
}

/** リズムを付けない入力アクションを1回行う（以下参照）。 */
suspend fun <T> CdpSession.input(timeoutMs: Long, block: suspend InputTrack.() -> T): InputOutcome<T> = input(timeoutMs, InputPacing.Instant, block)

/** は入力アクションを行い、リズムは [pacing] です。各コマンドの上限は[timeoutMs]です。失敗には「未達」と「不確実」があり、後者は押されたキーが上がっています。 */
suspend fun <T> CdpSession.input(timeoutMs: Long, pacing: InputPacing, block: suspend InputTrack.() -> T): InputOutcome<T> {
    val t = InputTrack(this, timeoutMs, pacing)
    return try {
        InputOutcome.Done(t.block())
    } catch (e: CancellationException) {
        withContext(NonCancellable) { t.release() }
        throw e
    } catch (e: Exception) {
        if (!t.mayHaveDelivered) InputOutcome.NotSent(e)
        else {
            t.release()
            InputOutcome.Uncertain(e)
        }
    }
}

/**
 * ページ スクリプトなしのブラウザ レイアウト データ (Page.getLayoutMetrics) からのページ ビジュアル ビューポートの CSS 幅:
 * ページでは変更できません。これを使用して、ビューのピクセルを入力に使用される CSS ピクセルに置き換えます。
 */
suspend fun InputTrack.viewportCssWidth(): Double {
    val m = query("Page.getLayoutMetrics")
    fun width(k: String) = (m[k] as? JsonObject)?.get("clientWidth")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    return width("cssVisualViewport") ?: width("cssLayoutViewport") ?: throw CdpException(0, "no viewport size", "Page.getLayoutMetrics")
}

/** 移動して (リズムに合わせてトラックをたどり、しばらく停止します)、押して、しばらく押し続けて、持ち上げます。ページに表示されるのは、信頼されたマウス クリック (pointerType マウス) です。 */
suspend fun InputTrack.mouseClick(x: Double, y: Double) {
    approach(x, y)
    pause(pacing.dwell())
    mouse("mousePressed", x, y, "left", 1, 1)
    pause(pacing.press())
    mouse("mouseReleased", x, y, "left", 0, 1)
}

/** マウスのみを移動します (リズムに応じた軌跡に沿って): マウスオーバー/マウスエンターと CSS :hover をトリガーします。 */
suspend fun InputTrack.mouseMove(x: Double, y: Double) = approach(x, y)

/** ポインターは最後の位置 (近くにあったのでわかりません) から (x, y) に移動し、最後のショットはターゲットに正確に着弾しました。 */
private suspend fun InputTrack.approach(x: Double, y: Double) {
    val from = session.pointer ?: pacing.origin(x, y)
    val points = pacing.path(from, x to y)
    points.forEachIndexed { i, (px, py) ->
        if (i > 0) pause(pacing.moveStep())
        mouse("mouseMoved", px, py, "none", 0, 0)
    }
}

/**
 * ポインタを (x, y) に移動し、ホイールを回して [distance] CSS ピクセルを一度に 1 グリッド (正は下方向を意味します) スクロールし、グリッド間で一時停止します。
 * は、表示されるラベルにのみ使用されます (ファイルの先頭を参照)。
 */
suspend fun InputTrack.mouseWheel(x: Double, y: Double, distance: Double) {
    approach(x, y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(x, y, d)
    }
}

/**
 * ポインターは動かず、現在の位置 (([x]、[y]) の場所はわかりません) でスクロール ホイールを回して、[distance] CSS ピクセルを 1 つずつスクロールします (正は下)。
 * 滞在中に簡単にスクロールするために使用します。マウスの移動やホバリングは必要ありません。表示されているラベルにのみ使用されます (ファイルの先頭を参照)。
 */
suspend fun InputTrack.wheelInPlace(x: Double, y: Double, distance: Double) {
    val (px, py) = session.pointer ?: (x to y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(px, py, d)
    }
}

/**
 * 左ボタンを押したまま、始点から終点まで [steps] ステップでドラッグします。ポインター イベント (スライダー、ドラッグ ソート) によって書き込まれたドラッグは、動きを直接受け取ります。
 * ページが HTML ネイティブ ドラッグ可能を使用する場合、まずブラウザーにドラッグを引き渡させます (setInterceptDrags)。
 * ドラッグデータを取得したら、終点にdragEnter/dragOver/dropを追加します。ネイティブのドラッグ アンド ドロップがなくなったかどうかを返します。
 */
suspend fun InputTrack.mouseDrag(fromX: Double, fromY: Double, toX: Double, toY: Double, steps: Int, stepDelayMs: Long = 16): Boolean = coroutineScope {
    val intercept = runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", true) }) }.isSuccess
    val intercepted = if (intercept) async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it.method == "Input.dragIntercepted" } } else null
    try {
        approach(fromX, fromY)
        pause(pacing.dwell())
        mouse("mousePressed", fromX, fromY, "left", 1, 1)
        pause(pacing.press())
        val n = steps.coerceAtLeast(1)
        for (i in 1..n) {
            delay(stepDelayMs)
            if (intercepted?.isCompleted == true) break
            val f = i.toDouble() / n
            mouse("mouseMoved", fromX + (toX - fromX) * f, fromY + (toY - fromY) * f, "left", 1, 0)
        }
        val data = intercepted?.takeIf { it.isCompleted }?.await()?.params?.get("data") as? JsonObject
        if (data != null) {
            for (type in listOf("dragEnter", "dragOver", "drop")) drag(type, toX, toY, data)
        } else {
            mouse("mouseReleased", toX, toY, "left", 0, 1)
        }
        data != null
    } finally {
        intercepted?.cancel()
        if (intercept) runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", false) }) }
    }
}

/** キーの組み合わせを 1 回押します。修飾キーを順番に押し、メイン キーを押して持ち上げ、修飾キーを逆の順序で持ち上げます。単語を生成するキーには keyDown (テキスト付き) を使用し、残りには rawKeyDown を使用します。 */
suspend fun InputTrack.pressCombo(combo: KeyCombo) {
    var held = 0
    for (m in combo.modifiers) {
        held = held or m.bit
        key("rawKeyDown", m.key, held, null)
        pause(pacing.modifierGap())
    }
    val text = combo.text
    val k = if (text != null && text != combo.key.text) combo.key.copy(key = text) else combo.key
    key(if (text != null) "keyDown" else "rawKeyDown", k, held, text)
    pause(pacing.keyHold())
    key("keyUp", k, held, null)
    for (m in combo.modifiers.asReversed()) {
        pause(pacing.modifierGap())
        held = held and m.bit.inv()
        key("keyUp", m.key, held, null)
    }
}

/**
 * タイピング: 文字、数字、スペースを 1 つずつリズミカルに (Shift キーを押しながら大文字で) キー間隔を変えて入力します。その他の単語 (句読点、中国語、改行)
 * インプットメソッドの送信と同様に、段落全体をフォーカスに送信します。リズムがない場合、またはテキストが [InputPacing.Limits.maxKeyedChars] より長い場合は、段落全体がフォーカスされます (貼り付けなど)。
 * はテキストに改行があるため Enter キーを押しません。 入力時に
 */
suspend fun InputTrack.typeText(text: String) {
    if (!pacing.paced || text.length > pacing.limits.maxKeyedChars) return insertText(text)
    var first = true
    for (run in TypingRuns.split(text)) {
        if (!first) pause(pacing.keyGap())
        first = false
        if (!run.keyed) {
            insertText(run.text)
            continue
        }
        run.text.forEachIndexed { i, c ->
            if (i > 0) pause(pacing.keyGap())
            val k = CdpKeys.named(c.toString())!!
            if (c in 'A'..'Z') pressCombo(KeyCombo(listOf(KeyModifier.Shift), k))
            else {
                key("keyDown", k, 0, k.text)
                pause(pacing.keyHold())
                key("keyUp", k, 0, null)
            }
        }
    }
}

/** をセクションに分割する方法: [keyed] の 1 つのセクションをキーごとに入力し、残りのセクションを全体として送信します。 */
object TypingRuns {
    data class Run(val text: String, val keyed: Boolean)

    fun keyable(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    fun split(text: String): List<Run> {
        val out = ArrayList<Run>()
        val cur = StringBuilder()
        var keyed: Boolean? = null
        for (c in text) {
            val k = keyable(c)
            if (keyed != null && k != keyed) {
                out += Run(cur.toString(), keyed)
                cur.clear()
            }
            keyed = k
            cur.append(c)
        }
        if (keyed != null) out += Run(cur.toString(), keyed)
        return out
    }
}

/** テキスト全体を現在のフォーカスに与えます (入力メソッドの送信と同じ入力イベントをトリガーします)。 */
suspend fun InputTrack.insertText(text: String) {
    effect("Input.insertText", buildJsonObject { put("text", text) })
}

// ---------- ネイティブ ドロップダウン ボックス ----------

data class SelectOption(val text: String, val value: String, val disabled: Boolean)

sealed interface SelectOutcome {
    /** [byScript]: キーボードが選択されておらず、値がスクリプトによって設定され、入力/変更が再発行されます (信頼できるイベントではありません)。 */
    data class Selected(val index: Int, val text: String, val changed: Boolean, val byScript: Boolean) : SelectOutcome
    data object NotASelect : SelectOutcome
    data object Disabled : SelectOutcome
    data class NoSuchOption(val available: List<String>) : SelectOutcome
    data class OptionDisabled(val text: String) : SelectOutcome
    /** キーボードもスクリプトもこの項目を選択しませんでした。 */
    data class NotApplied(val text: String) : SelectOutcome
}

object SelectMatch {
    /** 最初に完全に等しくなるように値を押し、次に表示テキスト (空白を削除) を押して等しくなります。最後に大文字と小文字が区別されません。どちらも null ではありません。 */
    fun indexOf(options: List<SelectOption>, wanted: String): Int? {
        val w = wanted.trim()
        options.indexOfFirst { it.value == wanted }.takeIf { it >= 0 }?.let { return it }
        options.indexOfFirst { it.text.trim() == w }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { it.text.trim().equals(w, ignoreCase = true) || it.value.equals(w, ignoreCase = true) }.takeIf { it >= 0 }
    }
}

private const val SELECT_OF = "function(){var n=this;while(n&&!(n.tagName&&n.tagName.toUpperCase()==='SELECT')){" +
    "n=n.parentElement||(n.parentNode&&n.parentNode.host)||null;}return n;}"
private const val SELECT_INFO = "function(){return {disabled:!!this.disabled,multiple:!!this.multiple,index:this.selectedIndex," +
    "options:Array.prototype.slice.call(this.options,0,500).map(function(o){return {text:String(o.label||o.text||''),value:String(o.value),disabled:!!o.disabled};})};}"
private const val SELECT_INDEX = "function(i){return this.multiple?(this.options[i]&&this.options[i].selected?i:-1):this.selectedIndex;}"
private const val SELECT_SET = "function(i){if(this.multiple){this.options[i].selected=true;}else{" +
    "Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'selectedIndex').set.call(this,i);}" +
    "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));" +
    "return this.multiple?(this.options[i].selected?i:-1):this.selectedIndex;}"

private fun callParams(objectId: String, fn: String, args: List<Int>, byValue: Boolean) = buildJsonObject {
    put("objectId", objectId); put("functionDeclaration", fn); put("returnByValue", byValue)
    if (args.isNotEmpty()) put("arguments", buildJsonArray { args.forEach { add(buildJsonObject { put("value", it) }) } })
}

private fun callResult(r: JsonObject): JsonObject {
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/**
 * (x, y) のネイティブ ドロップダウン ボックスで項目を選択します。メソッド: 座標によってノードを検索 (DOM.getNodeForLocation、シャドウと iframe を貫通)、
 * 上に移動して選択を見つけます。フォーカスした後、方向キーを使用して目的の項目に 1 つずつ移動し、押すたびに現在の項目を読み戻します。
 * 矢印キーは信頼できるキーボード イベントです。ページ フレームには実際の入力/変更が表示され、システム選択ボックスはポップアップしません。
 * スクリプトは、方向キーに到達できない場合（ページ上でボタンと複数選択ボックスがブロックされている場合）に値を設定し、入力/変更を再実行する場合にのみ使用します。フォーカス、キーの押下、およびスクリプトの設定はすべて、ページを変更するイベントです。
 */
suspend fun InputTrack.selectOptionAt(x: Double, y: Double, wanted: String): SelectOutcome {
    runCatching { query("DOM.enable") }
    val loc = query(
        "DOM.getNodeForLocation",
        buildJsonObject { put("x", x.toInt()); put("y", y.toInt()); put("includeUserAgentShadowDOM", false); put("ignorePointerEventsNone", true) },
    )
    val backend = (loc["backendNodeId"] as? JsonPrimitive)?.intOrNull ?: return SelectOutcome.NotASelect
    val node = query("DOM.resolveNode", buildJsonObject { put("backendNodeId", backend) })["object"]?.jsonObject
    val nodeId = (node?.get("objectId") as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    val sel = callResult(query("Runtime.callFunctionOn", callParams(nodeId, SELECT_OF, emptyList(), byValue = false)))
    val selectId = (sel["objectId"] as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    try {
        val info = callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INFO, emptyList(), byValue = true)))["value"] as? JsonObject
            ?: return SelectOutcome.NotASelect
        if ((info["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SelectOutcome.Disabled
        val multiple = (info["multiple"] as? JsonPrimitive)?.booleanOrNull == true
        val options = (info["options"] as? JsonArray).orEmpty().mapNotNull { o ->
            val m = o as? JsonObject ?: return@mapNotNull null
            SelectOption(
                (m["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
                (m["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (m["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val target = SelectMatch.indexOf(options, wanted) ?: return SelectOutcome.NoSuchOption(options.map { it.text })
        val want = options[target]
        if (want.disabled) return SelectOutcome.OptionDisabled(want.text)
        suspend fun current() =
            (callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INDEX, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull ?: -1
        val start = (info["index"] as? JsonPrimitive)?.intOrNull ?: -1
        if (!multiple && start == target || multiple && current() == target) return SelectOutcome.Selected(target, want.text, changed = false, byScript = false)
        if (!multiple) {
            effect("DOM.focus", buildJsonObject { put("objectId", selectId) })
            var at = start
            var presses = 0
            // 押すたびに読み戻します。ボタンを押しても移動しない場合 (ページがボタンをブロックしている場合)、もう一度押します。合計回数は選択肢の数を超えません。
            while (at != target && presses <= options.size) {
                pressCombo(KeyCombo(emptyList(), CdpKeys.named(if (at < target) "ArrowDown" else "ArrowUp")!!))
                presses++
                val now = current()
                if (now == at) break
                at = now
            }
            if (at == target) return SelectOutcome.Selected(target, want.text, changed = true, byScript = false)
        }
        val set = (callResult(effect("Runtime.callFunctionOn", callParams(selectId, SELECT_SET, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull
        return if (set == target) SelectOutcome.Selected(target, want.text, changed = true, byScript = true) else SelectOutcome.NotApplied(want.text)
    } finally {
        runCatching { query("Runtime.releaseObject", buildJsonObject { put("objectId", selectId) }) }
    }
}
