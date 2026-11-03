package com.wangzan101.mika.capsule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ChatCopy
import com.wangzan101.mika.ui.Spinner
import com.wangzan101.mika.ui.VoiceCopy
import com.wangzan101.mika.ui.VoiceWave
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 保留中のカプセルを長押しして発話します。リリースしたときに指が落ちる場所 ([CapsuleVoice.drop])、および発話後にカプセルが点滅する文 ([CapsuleVoice.sent])、
 * 会話セッションとインターフェイスのステータス ([CapsuleTalk]、ステート マシンは入力フィールドと同じ [HoldToTalk] を使用します)、
 * と、押すとカプセルの横に浮かぶパネル（[CapsuleVoicePanel]：誰かに伝える、リアルタイムの言葉、波形、「キャンセル」と「変更」の2つのドロップポイント）。
 * [CapsuleController] は、誰に渡すか、どのように送信するかを決定します (画面をダブルクリックしてターゲットを見つけるのと同じです)。
 */
object CapsuleVoice {
    /** 発売時：直接発送するか、カードを開いて変更するか、破棄してください。 */
    enum class Drop { Send, Edit, Cancel }

    /** カプセル上で点滅する文には、せいぜい数単語が含まれます。 */
    private const val SENT_CHARS = 12

    /**
     * 画面上の指の位置 ([x]、[y]) は、2 つの円がそれぞれ外側に広がります。 [slopPx] がヒットし、残りは送信されます。
     * パネルが出たばかりの際の誤判定を避けるため、サークルが配置されていない（空）場合は当たりとしてカウントされません。
     */
    fun drop(x: Float, y: Float, cancel: Rect, edit: Rect, slopPx: Int): Drop {
        fun Rect.hit() = !isEmpty && x >= left - slopPx && x <= right + slopPx && y >= top - slopPx && y <= bottom + slopPx
        return when {
            cancel.hit() -> Drop.Cancel
            edit.hit() -> Drop.Edit
            else -> Drop.Send
        }
    }

    /** カプセルで文を送信した後: 中断、応答または送信し、その後に話された内容の最初の数語を続けます。 */
    fun sent(kind: CardInputKind, text: String): String {
        val head = text.trim().let { if (it.length > SENT_CHARS) it.take(SENT_CHARS) + "…" else it }
        return when (kind) {
            CardInputKind.Steer -> tr(R.string.capsule_voice_steered, head)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answered, head)
            CardInputKind.Continue, CardInputKind.Start -> tr(R.string.capsule_voice_sent, head)
        }
    }

    /** パネル上部の文: 誰かに伝えてください。これは、カード入力ボックスが誰に与えられるかに一致します。新しい会話を開くときに、入力ボックス (承認と同意を要求する) や「聞く」ことはありません。 */
    fun title(card: CardView?): String {
        val input = card?.let(CapsuleCards::input)?.kind ?: return tr(R.string.voice_listening)
        val goal = when (card) {
            is CardView.Detail -> card.run.run.goal
            is CardView.Chat -> card.run?.run?.goal ?: card.title.takeIf { card.target != null }
            is CardView.Kept -> null
        }?.takeIf { it.isNotBlank() }
        return when (input) {
            CardInputKind.Steer -> goal?.let { tr(R.string.voice_steer_to, it) } ?: tr(R.string.voice_listening)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answer_title)
            CardInputKind.Continue, CardInputKind.Start -> goal?.let { tr(R.string.capsule_voice_continue_title, it) } ?: tr(R.string.voice_listening)
        }
    }
}

/**
 * カプセルが最後に話した時間: 会話を開く ([begin])、イベントを受け取る、押されたときにタイミングを押す、指が動いたときに着地点を計算する ([move])、リリースする ([finish])。
 * 指で押すと ([byTouch])、着地点は指に追従します。画面を読み上げる「発話」には指はなく、発することしかできません。そう言ってから（[HoldToTalk.State.Done]、
 * [HoldToTalk.State.Problem]) から [onResult] まで、リリース時の着地点も合わせて指定します。手が押されたままセッションが先に終了し、結果は[HoldToTalk]がキープした。
 * 指が離れるのを待ち、その時点で着地点を押します（キャンセルする場合は捨て、カードを開くように変更し、残りを送信します）。画面を読み取るときに指が必要なく、セッションが終了するとすぐに画面が「送信」されます。
 * 放した後、回してもう一度押します。古い部分はもう必要ないので、もう一度聞き始めます。メインスレッドでのみ使用されます。
 */
internal class CapsuleTalk(
    private val context: Context,
    private val voice: () -> VoiceSource,
    private val scope: CoroutineScope,
    private val onResult: (CapsuleVoice.Drop, HoldToTalk.State) -> Unit,
) {
    /** [begin] の結果: 起動した、または起動しなかった理由。 */
    sealed interface Begin {
        data object Started : Begin
        data object NoPermission : Begin
        data class Unavailable(val why: SpeechEngineSpec.Why) : Begin
    }

    // 「短すぎる」は指の押下からカウントされます: リスニングを開始するときに HOLD_MS が押されており、ステート マシンは残りのもののみをカウントします。
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** パネルが表示されています (押したり放したりすると回転します)。 */
    var active by mutableStateOf(false)
        private set
    /** がリリースされ (または画面を読み取って「送信」)、言葉を待っています。 */
    var released by mutableStateOf(false)
        private set
    var byTouch by mutableStateOf(true)
        private set
    var aim by mutableStateOf(CapsuleVoice.Drop.Send)
        private set
    var title by mutableStateOf("")
    var engine by mutableStateOf("")
        private set

    /** パネルが配置された後、画面上の 2 つの着地点の位置が報告されます。 */
    var cancelAt = Rect.Zero
    var editAt = Rect.Zero

    private var drop: CapsuleVoice.Drop? = null
    private var collector: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    private var startedAt = 0L

    /** が使用できるかどうか、および録音許可があるかどうか: 動作する場合はリスニングを開始します。カプセルはアクティビティではないため、システムの許可ボックスを開けることができません。 */
    fun begin(title: String, byTouch: Boolean): Begin {
        if (active && !released) return Begin.Started
        val source = voice()
        val spec = source.spec()
        if (spec is SpeechEngineSpec.Unavailable) return Begin.Unavailable(spec.why)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return Begin.NoPermission
        // は、前のセグメントを解放した後も回転しています (サービス プロバイダーが停止しているか、組み込みの認識が終了しています)。ユーザーがもう一度押すと、前のセグメントは不要になります。セッションはプレスによってキャンセルされました
        if (active) stop()
        hold.reset()
        val session = source.start()
        this.title = title
        this.byTouch = byTouch
        engine = VoiceCopy.engine(spec)
        aim = CapsuleVoice.Drop.Send
        drop = null
        released = false
        cancelAt = Rect.Zero
        editAt = Rect.Zero
        startedAt = SystemClock.elapsedRealtime()
        hold.press(session)
        active = true
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
        watcher = scope.launch {
            hold.state.collect { s ->
                // 画面の読み取りを待つ必要はありません。セッションが自動的に終了したら、「送信」を押すだけです。
                if (s is HoldToTalk.State.Listening && s.ended && !this@CapsuleTalk.byTouch) finish(CapsuleVoice.Drop.Send)
                if (s is HoldToTalk.State.Done || s is HoldToTalk.State.Problem) {
                    val d = drop ?: aim
                    stop()
                    hold.reset()
                    onResult(d, s)
                }
            }
        }
        ticker = scope.launch {
            while (active && !released) {
                delay(TICK_MS)
                // この時点で着地点をクリックして終了します（ステートマシンは着地点に到達すると解放されますが、着地点はわかりません）
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_MS) finish(aim) else hold.tick()
            }
        }
        return Begin.Started
    }

    /** 指が画面上の([x],[y])に移動しました。 */
    fun move(x: Float, y: Float, slopPx: Int) {
        if (!active || released || !byTouch) return
        aim = CapsuleVoice.drop(x, y, cancelAt, editAt, slopPx)
        // ステート マシンは、指がキャンセル ボタン上にあるかどうかも認識する必要があります。セッションが押されるとエラーが発生し、キャンセル ボタン上で停止するとプロンプトが表示されません。
        hold.armCancel(aim == CapsuleVoice.Drop.Cancel)
    }

    /** リリース（または画面読み上げで「送信」）：キャンセルする場合は破棄し、そうでない場合は「終了」などの文字で終了します。 */
    fun finish(at: CapsuleVoice.Drop) {
        if (!active || released) return
        if (at == CapsuleVoice.Drop.Cancel) {
            abort()
            return
        }
        aim = at
        drop = at
        released = true
        hold.armCancel(false)
        hold.release()
    }

    /** このセクションは不要になりました (キャンセルされるまでドラッグし、カプセルが削除され、タッチがシステムによって削除されます)。繰り返し調整可能です。 */
    fun abort() {
        stop()
        hold.dispose()
    }

    private fun stop() {
        active = false
        released = false
        drop = null
        ticker?.cancel()
        ticker = null
        watcher?.cancel()
        watcher = null
        collector?.cancel()
        collector = null
    }

    companion object {
        /** 長く押し続けると長押し（ミリ秒）とみなされます。 */
        const val HOLD_MS = 400L
        /** 指で押した瞬間から、この時間内に指を離すと、はっきり聞こえなかったふりをします。 */
        const val MIN_TOTAL_MS = 500L
        const val MAX_MS = 60_000L
        private const val TICK_MS = 100L
    }
}

/** パネルと 2 つの円はカプセルから、また互いからどのくらい離れていますか。 2つの円の大きさはどれくらいですか。 */
private val PanelGap = 16.dp
private val TargetsGap = 18.dp
private val TargetSize = 62.dp
private val PanelShape = RoundedCornerShape(24.dp)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val SaidText = TextStyle(fontSize = 17.5.sp, lineHeight = 26.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
private val TargetText = TextStyle(
    fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 3f),
)
/** の背後にあるアプリはわずかに薄暗くなり、パネルに注目が集まっています。 */
private const val DIM = 0.28f

/**
 * カプセルを長押しして（タッチせずに）話すときの画面全体のレイヤー: 背面がわずかに暗くなり、パネルはカプセルの側面に近くなります ([anchorTop]..[anchorBottom]、画面の縦軸)。
 * はカプセルの上部に配置できます。それ以外の場合は、カプセルの下に配置されます。 2 つの着地点はパネルの外側 (カプセルからさらに離れたところ) にあり、それらを指でドラッグします。
 * [insetTop]/[insetBottom] は、ステータス バーと下部のジェスチャ バーが占める高さです。 2つの円を配置したら、画面上の位置を[talk]に報告します。
 */
@Composable
internal fun CapsuleVoicePanel(talk: CapsuleTalk, anchorTop: Int, anchorBottom: Int, insetTop: Int, insetBottom: Int) {
    if (!talk.active) return
    val c = MikaTheme.colors
    val state by talk.hold.state.collectAsState()
    val view = LocalView.current
    val dim by animateFloatAsState(DIM, if (MikaTheme.reduceMotion) snap() else tween(200), label = "voice-dim")
    var below by remember { mutableStateOf(false) }
    // 画面上の円の位置: ウィンドウ内の座標に画面上のウィンドウの位置を加えたもの
    fun Rect.onScreen(): Rect {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return translate(Offset(at[0].toFloat(), at[1].toFloat()))
    }
    Layout(
        content = {
            Targets(talk, show = talk.byTouch && !talk.released) { cancel, edit ->
                talk.cancelAt = cancel.onScreen()
                talk.editAt = edit.onScreen()
            }
            Panel(talk, state, below)
        },
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).testTag("capsule-voice"),
    ) { measurables, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val targets = measurables[0].measure(loose)
        val panel = measurables[1].measure(loose)
        val gap = PanelGap.roundToPx()
        val between = TargetsGap.roundToPx()
        val total = targets.height + between + panel.height
        val top = insetTop + gap
        val bottom = cons.maxHeight - insetBottom - gap
        // カプセルが収まる場合は上に置きます (2 つの円が上にあります)。収まらない場合は下に置きます (2 つの円が下にあります)。
        val goBelow = anchorTop - gap - total < top
        if (goBelow != below) below = goBelow
        val groupTop = (if (goBelow) anchorBottom + gap else anchorTop - gap - total)
            .coerceAtMost(bottom - total).coerceAtLeast(top)
        layout(cons.maxWidth, cons.maxHeight) {
            if (goBelow) {
                panel.place((cons.maxWidth - panel.width) / 2, groupTop)
                targets.place((cons.maxWidth - targets.width) / 2, groupTop + panel.height + between)
            } else {
                targets.place((cons.maxWidth - targets.width) / 2, groupTop)
                panel.place((cons.maxWidth - panel.width) / 2, groupTop + targets.height + between)
            }
        }
    }
}

/** 「キャンセル」と「変更」の 2 つの円。指を動かすと拡大して色が変わります。放した後、回転しながら非表示にします（位置は保持され、パネルはジャンプしません）。 */
@Composable
private fun Targets(talk: CapsuleTalk, show: Boolean, onPlaced: (Rect, Rect) -> Unit) {
    // 2 つの円の位置 (ウィンドウ内の座標) がそれぞれ配置されます。両方が利用可能な場合にのみレポートします
    val at = remember { arrayOf(Rect.Zero, Rect.Zero) }
    Row(
        Modifier.alpha(if (show) 1f else 0f),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Target(MikaIcons.Close, stringResource(R.string.capsule_voice_cancel), hot = show && talk.aim == CapsuleVoice.Drop.Cancel, bad = true) {
            at[0] = it
            if (!at[1].isEmpty) onPlaced(at[0], at[1])
        }
        Target(MikaIcons.Edit, stringResource(R.string.capsule_voice_edit), hot = show && talk.aim == CapsuleVoice.Drop.Edit, bad = false) {
            at[1] = it
            if (!at[0].isEmpty) onPlaced(at[0], at[1])
        }
    }
}

@Composable
private fun Target(icon: ImageVector, label: String, hot: Boolean, bad: Boolean, onPlaced: (Rect) -> Unit) {
    val c = MikaTheme.colors
    val scale by animateFloatAsState(if (hot) 1.18f else 1f, if (MikaTheme.reduceMotion) snap() else tween(150), label = "target")
    val fill = when {
        !hot -> c.surface.copy(alpha = 0.95f)
        bad -> c.bad
        else -> c.accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(TargetSize)
                .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(c, CircleShape, need = false)
                .background(fill, CircleShape)
                .testTag(if (bad) "capsule-voice-cancel" else "capsule-voice-edit"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (hot) c.onAccent else c.ink)
        }
        Text(label, style = TargetText, color = Color.White, maxLines = 1)
    }
}

/** パネル: 上部の録音ポイント、誰に話すか、どのテキストを使用するか、使用した時間を指定します。真ん中のリアルタイムの単語。波形。そしてそれを一番下で放すとどうなるか。 */
@Composable
private fun Panel(talk: CapsuleTalk, state: HoldToTalk.State, below: Boolean) {
    val c = MikaTheme.colors
    val listening = state as? HoldToTalk.State.Listening
    val converting = talk.released || state is HoldToTalk.State.Converting
    val aim = if (converting) CapsuleVoice.Drop.Send else talk.aim
    val cancel = aim == CapsuleVoice.Drop.Cancel
    val edit = aim == CapsuleVoice.Drop.Edit
    Column(
        Modifier.padding(horizontal = 14.dp).widthIn(max = 520.dp).fillMaxWidth()
            .glass(c, PanelShape, need = false)
            .then(if (cancel) Modifier.border(1.5.dp, c.bad, PanelShape) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("capsule-voice-panel"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when {
                cancel -> c.bad
                edit || converting -> c.ink2
                else -> c.accent
            }
            Box(Modifier.size(8.dp).drawBehind { drawCircle(dot) })
            Text(talk.title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (talk.engine.isNotEmpty()) {
                Text(
                    talk.engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = ChipText, color = c.ink2, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            listening?.let { Text(ChatCopy.clock(it.elapsedMs), style = TimeText, color = c.ink3) }
        }
        val heard = listening?.let { it.stable + it.tail }.orEmpty()
        val words = buildAnnotatedString {
            if (heard.isEmpty()) {
                val ghost = when {
                    converting -> ""
                    listening?.live == true -> stringResource(R.string.voice_say_now)
                    else -> stringResource(R.string.voice_recording)
                }
                withStyle(SpanStyle(color = c.ink3)) { append(ghost) }
            } else {
                withStyle(SpanStyle(color = c.ink)) { append(listening?.stable.orEmpty()) }
                withStyle(SpanStyle(color = c.ink2)) { append(listening?.tail.orEmpty()) }
            }
        }
        if (converting && heard.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).testTag("capsule-voice-converting"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Spinner(Modifier.size(15.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
                Text(stringResource(R.string.voice_converting), style = SaidText, color = c.ink2, maxLines = 1)
            }
        } else {
            Text(
                words,
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).alpha(if (cancel) 0.4f else 1f),
                style = if (cancel) SaidText.copy(textDecoration = TextDecoration.LineThrough) else SaidText,
                maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!converting) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                VoiceWave(listening?.level ?: 0f, flat = cancel, reduceMotion = MikaTheme.reduceMotion, color = if (cancel || edit) c.ink3 else c.accent)
            }
        }
        // は転送時間の途中ですでに「テキストに変換...」を書き込んでいます。以下では繰り返しません。
        if (converting) return@Column
        val foot = when {
            !talk.byTouch -> stringResource(R.string.capsule_voice_foot_a11y)
            cancel -> stringResource(R.string.capsule_voice_foot_cancel)
            edit -> stringResource(R.string.capsule_voice_foot_edit)
            listening?.live == true -> stringResource(if (below) R.string.capsule_voice_foot_send_down else R.string.capsule_voice_foot_send_up)
            else -> stringResource(if (below) R.string.capsule_voice_foot_convert_down else R.string.capsule_voice_foot_convert_up)
        }
        Text(foot, Modifier.fillMaxWidth().padding(top = 8.dp), style = FootText, color = c.ink3, maxLines = 2)
    }
}
