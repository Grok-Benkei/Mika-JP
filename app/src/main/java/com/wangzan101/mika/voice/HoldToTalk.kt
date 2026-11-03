package com.wangzan101.mika.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 押して話すステート マシン: 押す、上にスライドしてキャンセルする、離す、その時点で終了する、およびセッションを閉じるというイベントで、現時点でインターフェイスに表示される内容を計算します。
 * はインターフェイスやジェスチャーに触れません。会話入力フィールドとフローティングカプセルは共有されます。セッションは呼び出し元によって開かれ、渡されます ([press])。
 * イベントは発信者 ([onEvent]) によって転送され、[tick] イベントは発信者が押し続けると発信者によってスケジュールされます (タイミング、[maxMs] で終了)。
 * 手が押されている間に会話が自動的に終了しました (携帯電話には、文章が話された後は再開されないという認識が組み込まれており、途中でエラーが発生しましたが、単語は聞こえました): 結果が最初に保持されます。
 * 指が離れるのを待って、その時点で着地ポイントを押します。キャンセルで止まったら投げ捨てます。そうでない場合は、引き継がれます。これにより、ユーザーがキャンセルしたいときにメッセージは送信されません。
 * は 1 つのスレッド (メインスレッド) でのみ発生します。
 */
class HoldToTalk(private val clock: () -> Long, private val maxMs: Long = 60_000, private val minMs: Long = 500) {
    sealed interface State {
        data object Idle : State

        /**
         * を押します (または、電話機の内蔵認識がまだ終了しているため、ボタンを放します): [stable] が決定され、[tail] の最後にいくつかの文字が変更されている可能性があります。
         * [ended]: ハンドはまだ押されていますが、セッションは終了し、結果のリリースを待っています。
         */
        data class Listening(
            val stable: String,
            val tail: String,
            val level: Float,
            val elapsedMs: Long,
            val cancelArmed: Boolean,
            val live: Boolean,
            val ended: Boolean = false,
        ) : State

        /** サービスプロバイダー: リリース後、テキストが転送されます。 */
        data object Converting : State

        /** インターフェイスを使用して入力ボックスに入力し、次に [reset] を入力します。 */
        data class Done(val text: String) : State

        /** null = はっきりと聞こえませんでした (何も認識しなかったか、または押した時間が短すぎました)。 */
        data class Problem(val why: SpeechFailure?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SpeechSession? = null
    private var startedAt = 0L
    /** は解放され (またはポイントに到達し)、セッションの終了に引き渡されました。それが解放されるか、ポイントに到達すると、再び繰り返されることはありません。 */
    private var released = false
    /** 手が押されたままの状態で、セッションの結果 (完了または問題(null)) が返されました。解除されるまで待ってからお渡しください。 */
    private var pending: State? = null

    fun press(session: SpeechSession) {
        // 前の段落は終了していません (まれに、前の段落がまだ転送中であるなど): ユーザーが今度はもう一度押したので、前の段落は不要になりました。
        this.session?.cancel()
        this.session = session
        startedAt = clock()
        released = false
        pending = null
        _state.value = State.Listening("", "", 0f, 0, cancelArmed = false, live = session.live)
    }

    /** 指は押された位置から [upPx] まで上に移動します。 [thresholdPx]を超えた場合は「解除してキャンセル」を意味し、元に戻して再開します。 */
    fun drag(upPx: Float, thresholdPx: Float) = armCancel(upPx > thresholdPx)

    /** 現在、指は「キャンセル」になっていません。浮遊カプセルの着地点は上向きのスライドの距離ではなく、ここで直接語られます。 */
    fun armCancel(armed: Boolean) {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        if (armed != s.cancelArmed) _state.value = s.copy(cancelArmed = armed)
    }

    fun release() {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val early = pending
        val current = session
        if (current == null && early == null) return
        when {
            s.cancelArmed -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Idle
            }
            // 短すぎる押しは、おそらく誤ってタッチされた可能性があります。転送しないでください (サービスプロバイダーもクレジットを支払う必要があります)。はっきりと聞こえなかったふりをしてください。
            clock() - startedAt < minMs -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Problem(null)
            }
            // 手が押されたままセッションが終了しました。保持されている結果が引き継がれるのは今だけです。
            early != null -> {
                pending = null
                _state.value = early
            }
            current != null -> {
                released = true
                current.release()
                // サービス プロバイダー: Converting イベントが到着するまでレコーダーが停止するまでに少し時間がかかります。ボタンを放すとすぐに「変換中」と表示され、入力ボックスがフラッシュバックして最初にスペースを占めることがなくなります。
                if (!s.live) _state.value = State.Converting
            }
        }
    }

    /** を押して時間を調整します。時間を更新します。 [maxMs] に達するとプロセスを解放します。録音は通知なしに自動的に停止し、終了しないと最後の単語が失われます。 */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        if (released || (session == null && pending == null)) return
        val elapsed = clock() - startedAt
        if (elapsed >= maxMs) {
            _state.value = s.copy(elapsedMs = maxMs)
            release()
        } else if (elapsed != s.elapsedMs) {
            _state.value = s.copy(elapsedMs = elapsed)
        }
    }

    fun onEvent(e: SpeechEvent) {
        if (session == null) return
        val now = _state.value
        when (e) {
            // テキストに変換した後、サービス プロバイダーのエンジンは 1 ボリューム分遅れる可能性があります。リスニング時にのみ認識されます。
            is SpeechEvent.Level -> if (now is State.Listening) _state.value = now.copy(level = e.value)
            is SpeechEvent.Partial -> if (now is State.Listening) _state.value = now.copy(stable = e.stable, tail = e.tail)
            SpeechEvent.Converting -> _state.value = State.Converting
            is SpeechEvent.Final -> {
                session = null
                val text = e.text.trim()
                val result = if (text.isEmpty()) State.Problem(null) else State.Done(text)
                if (released || now !is State.Listening) {
                    _state.value = result
                } else {
                    // 手がまだ押されている場合は、最初に押し続けてください。インターフェイスには、聞いた単語が表示されます。放すときはキャンセルで止めて捨ててください。そうしないと渡してしまいます。
                    pending = result
                    _state.value = now.copy(stable = text, tail = "", level = 0f, ended = true)
                }
            }
            is SpeechEvent.Failed -> {
                session = null
                val result = State.Problem(e.why)
                // 間違った場合は、ボタンを放すまで待つ必要はありません (もう一度押すと何も表示されません)。キャンセル ボタンで手が止まったときに長押しするだけでよく、キャンセル ボタンで手を離してもプロンプトは表示されません。
                if (!released && now is State.Listening && now.cancelArmed) {
                    pending = result
                    _state.value = now.copy(level = 0f, ended = true)
                } else {
                    _state.value = result
                }
            }
        }
    }

    /** ページを離れ、画面を切り替え、アプリのバックグラウンドに移動します。この段落を破棄して通常に戻ります。繰り返し調整可能です。 */
    fun dispose() {
        session?.cancel()
        session = null
        released = false
        pending = null
        _state.value = State.Idle
    }

    /** インターフェイスが使い果たされると、[State.Done]/[State.Problem] は通常に戻ります。まだ動かずに耳を傾け、振り向いています。 */
    fun reset() {
        val s = _state.value
        if (s is State.Done || s is State.Problem) _state.value = State.Idle
    }

    companion object {
        /**
         * の発言は入力ボックス内の既存の単語に接続されます。両側の単語が中国語、日本語、韓国語の文字または全角句読点の場合は、直接接続します。
         * それ以外の場合は、途中にスペースを追加します（既存の単語がスペースで終わる場合は追加されません）。
         */
        fun join(existing: String, spoken: String): String {
            if (existing.isEmpty()) return spoken
            if (spoken.isEmpty()) return existing
            val left = existing.last()
            if (left.isWhitespace()) return existing + spoken
            return if (isCjk(left) && isCjk(spoken.first())) existing + spoken else "$existing $spoken"
        }

        private fun isCjk(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) return true
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }
    }
}
