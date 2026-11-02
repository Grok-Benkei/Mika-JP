package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 長押ししてダイアログ入力フィールドで話します: 入力ボックスのジェスチャー ([holdToTalk])、押すと入力フィールド全体が青色に変わります ([VoiceBarCover])。
 * 入力フィールドの上にある「リスニング」カードと、上にスワイプしてプロンプトをキャンセルしたり、はっきりと聞こえないなどの軽いリマインダー ([VoiceFloat])、
 * 「音声入力はまだ利用できません」と録音許可の説明 ([VoiceSheetHost])。ステータスはすべて[HoldToTalk]になっており、ここでは描画と配線のみを行っています。
 */

/** この長さ (ミリ秒) ほど押し続けると、リスニングが開始されます。そうでない場合は、クリックして入力してください。 */
private const val HOLD_MS = 300L

/** 指を押してから指を離すまでの時間がこの時間（ミリ秒）未満の場合は誤タッチとみなされ、聞こえにくくなります。 */
private const val MIN_TOTAL_MS = 500L

/** は一度に最大でこの長さまで聴くことができます。最後に、カードの右下隅で [COUNTDOWN_MS] がカウントダウンします。 */
private const val MAX_MS = 60_000L
private const val COUNTDOWN_MS = 10_000L

/** ここまで指を上にスライドさせると「離してキャンセル」を意味します。 */
private val CancelLift = 80.dp

/** 下部弾性層: どの説明。 */
internal sealed interface VoiceSheet {
    data object Permission : VoiceSheet
    /** は録音を許可しましたが、認識サービスは依然として許可がないか、録音エラーがあると表示します。これは、システムのマイク マスター スイッチと許可管理が他のアプリケーションによって占有されていることを意味します。 */
    data object MicBlocked : VoiceSheet
    data class Unavailable(val why: SpeechEngineSpec.Why) : VoiceSheet
}

/** 話すときにインターフェイス上のテキストを押し続けます。インターフェースには触れず、1回のテストで直接測定してください。 */
internal object VoiceCopy {
    /** カード上部の小さなラベル: テキストの変換に使用されます。 */
    fun engine(spec: SpeechEngineSpec): String = when (spec) {
        is SpeechEngineSpec.Doubao -> tr(R.string.speech_doubao_model)
        is SpeechEngineSpec.System -> tr(if (spec.onDevice) R.string.voice_engine_phone_offline else R.string.voice_engine_phone)
        is SpeechEngineSpec.Provider -> spec.model
        is SpeechEngineSpec.Unavailable -> ""
    }

    /** 失敗した場合の入力フィールドの上の文。権限とエンジンは使用できません。通常は説明ポップアップレイヤーに移動します。要点を伝えるには、たった 1 文だけです。 */
    fun failure(why: SpeechFailure): String = when (why) {
        SpeechFailure.Busy -> tr(R.string.voice_busy)
        SpeechFailure.Network -> tr(R.string.voice_network)
        SpeechFailure.MicUnavailable, SpeechFailure.AudioError -> tr(R.string.voice_mic_unavailable)
        // モデルエラーが発生した場合、サービスプロバイダーのエラーがステートメントに続きます (金額、キー、モデルが見つかりません...)
        is SpeechFailure.Provider -> tr(R.string.voice_provider_failed, why.error.message.orEmpty())
        SpeechFailure.NoPermission -> tr(R.string.voice_permission_title)
        is SpeechFailure.Unavailable -> tr(R.string.voice_unavailable_title)
    }

    val NO_MATCH get() = tr(R.string.voice_no_match)

    fun unavailable(why: SpeechEngineSpec.Why): String = when (why) {
        SpeechEngineSpec.Why.DoubaoNotConfigured -> tr(R.string.speech_doubao_missing)
        SpeechEngineSpec.Why.NoSystemNoModel -> tr(R.string.voice_unavailable_no_engine)
        SpeechEngineSpec.Why.SystemGone -> tr(R.string.voice_unavailable_system_gone)
        SpeechEngineSpec.Why.SystemNoResponse -> tr(R.string.voice_unavailable_system_silent)
        SpeechEngineSpec.Why.ProviderGone -> tr(R.string.voice_unavailable_provider_gone)
        SpeechEngineSpec.Why.Off -> tr(R.string.voice_unavailable_off)
    }

    /** 最後の 10 秒までのカウントダウン。まだ早い場合は null。 */
    fun countdown(elapsedMs: Long): String? {
        val left = MAX_MS - elapsedMs
        if (left > COUNTDOWN_MS) return null
        return tr(R.string.voice_left, ((left + 999) / 1000).toInt().coerceAtLeast(0))
    }
}

/** 録音許可が拒否されたことを示すサイン: システムがボックスをポップアップ表示しなくなったら (拒否されたため、再度説明する必要はありません)、ポップアップの説明を変更し、ボタンをクリックしてアプリの許可ページに直接移動します。 */
private object MicPermission {
    private const val PREFS = "voice_input"
    private const val DENIED = "mic_denied"

    fun denied(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DENIED, false)

    fun record(context: Context, granted: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DENIED, !granted).apply()
}

/**
 * 入力フィールドを押して話したときのインターフェイスのステータス: 指がまだ押されているかどうか ([holding])、カードのエンジン ラベル、ポップアップ レイヤー、およびライト プロンプト。
 * はセッションを開始し、イベントを転送し、すべてを [hold] に終了します。
 */
@Stable
internal class VoiceHold(private val voice: VoiceSource, private val scope: CoroutineScope) {
    // 「短すぎる」は、指で押したときの 0.5 秒未満です。聞き始めたときに HOLD_MS が押されており、ステート マシンは残りの時間のみをカウントします。
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** 長押しして開始した後、指が離れていません。 */
    var holding by mutableStateOf(false)
        private set
    var engine by mutableStateOf("")
        private set
    var sheet by mutableStateOf<VoiceSheet?>(null)
    var toast by mutableStateOf<String?>(null)

    /** は、記録許可がある場合は true を返します。録音許可がない場合は false を返し（または説明を再生し）、 false を返します。今回は始まりません。インターフェイスによってインストールされます (アクティビティが必要です)。 */
    internal var ensureMic: () -> Boolean = { true }
    internal var haptic: () -> Unit = {}
    private var collector: Job? = null

    /**
     * 時間を長押しします。まず使用できるかどうか、許可があるかどうかを確認してから、リスニングを開始します。
     * は、前のセクションを解放した後も回転しています (サービス プロバイダーが停止しているか、組み込みの認識が終了しています)。もう一度押すこともできます。前のセクションは不要になり、[HoldToTalk.press] によってキャンセルされます。
     */
    fun begin() {
        if (holding) return
        toast = null
        hold.reset()
        val spec = voice.spec()
        if (spec is SpeechEngineSpec.Unavailable) {
            sheet = VoiceSheet.Unavailable(spec.why)
            return
        }
        if (!ensureMic()) return
        haptic()
        val session = voice.start()
        engine = VoiceCopy.engine(spec)
        hold.press(session)
        holding = true
        collector?.cancel()
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
    }

    fun drag(upPx: Float, thresholdPx: Float) {
        if (holding) hold.drag(upPx, thresholdPx)
    }

    /** フィンガー リーフ: ステート マシンに渡されます (キャンセル、短すぎる、終了)。 */
    fun end() {
        if (!holding) return
        holding = false
        hold.release()
    }

    /** ジェスチャーが中断され、ページから離れ、画面が切り替わり、アプリがバックグラウンドに移行します。このセクションは不要になりました。 */
    fun abort() {
        holding = false
        hold.dispose()
        collector?.cancel()
        collector = null
    }
}

private val IdleState = MutableStateFlow<HoldToTalk.State>(HoldToTalk.State.Idle)

/**
 * は、入力フィールドに必要な [VoiceHold] です。ユーザーが設定で「使用しない」を選択した場合 (入力ボックスは入力のみ可能)、[voice] が存在しないか、null になります。
 * は完成した言葉を [onText] に渡しました。
 */
@Composable
internal fun rememberVoiceHold(voice: VoiceSource?, onText: (String) -> Unit): VoiceHold? {
    val off = remember(voice) {
        val spec = voice?.spec()
        voice == null || (spec is SpeechEngineSpec.Unavailable && spec.why == SpeechEngineSpec.Why.Off)
    }
    val scope = rememberCoroutineScope()
    val vh = remember(voice, off) { if (off || voice == null) null else VoiceHold(voice, scope) } ?: return null
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        MicPermission.record(context, granted)
    }
    vh.haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    vh.ensureMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            // 許可を求める場合は、初めて長押しするだけです。拒否され、システムがボックスをポップアップ表示しなくなった場合は、説明に変更してシステム設定に移動します
            val activity = context.findActivity()
            val blocked = MicPermission.denied(context) && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            if (blocked) vh.sheet = VoiceSheet.Permission else launcher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
    val state by vh.hold.state.collectAsState()
    LaunchedEffect(state) {
        when (val s = state) {
            is HoldToTalk.State.Done -> {
                onText(s.text)
                vh.hold.reset()
            }
            is HoldToTalk.State.Problem -> {
                when (val why = s.why) {
                    null -> vh.toast = VoiceCopy.NO_MATCH
                    is SpeechFailure.Unavailable -> vh.sheet = VoiceSheet.Unavailable(why.why)
                    // 録音許可がまだ与えられていません: システム ボックスをポップアップ表示するか、「設定に移動」します。すでに与えられているにもかかわらず、権限がないことが報告されるか、認識サービスが記録エラーを報告します。
                    // 別の説明（システムマイクマスタースイッチ、権限管理）。マイクをオンにできない他の状況でも、マイクが使用されている可能性があることを示すメッセージが表示されます。
                    SpeechFailure.NoPermission -> if (vh.ensureMic()) vh.sheet = VoiceSheet.MicBlocked
                    SpeechFailure.AudioError -> vh.sheet = VoiceSheet.MicBlocked
                    else -> vh.toast = VoiceCopy.failure(why)
                }
                vh.hold.reset()
            }
            else -> Unit
        }
    }
    // が押されるとインターフェイスによってプッシュされます: タイミング、60 秒で終了 (ポイントに到達すると録音が停止します。そうでない場合は、最後の単語が失われます)
    val listening = state is HoldToTalk.State.Listening
    LaunchedEffect(listening) {
        while (listening) {
            delay(100)
            vh.hold.tick()
        }
    }
    LaunchedEffect(vh.toast) {
        val t = vh.toast ?: return@LaunchedEffect
        delay(if (t == VoiceCopy.NO_MATCH) 2_000 else 3_500)
        vh.toast = null
    }
    // ページを離れ、画面を切り替え、アプリのバックグラウンドに移動します。話している段落を破棄します。
    LifecycleStartEffect(vh) { onStopOrDispose { vh.abort() } }
    return vh
}

/** [vh] は現在の状態です。 [vh] がなければ常に正常です。 */
@Composable
internal fun voiceState(vh: VoiceHold?): HoldToTalk.State = (vh?.hold?.state ?: IdleState).collectAsState().value

/**
 * フォーカスのない入力ボックスをカバーするジェスチャー: クリックして入力を続けます ([onTap] はキーボードの演奏に焦点を当てます)。約 0.3 秒間押し続けると音声が聞こえ始めます。
 * [CancelLift] を押して上にスライドさせてキャンセルし、解放してステート マシンに渡します。
 */
@Composable
internal fun Modifier.holdToTalk(vh: VoiceHold, onTap: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { CancelLift.toPx() }
    return pointerInput(vh, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // true: 1 回クリックします。 false: 指が動いたり消えたりした場合、それは私たちのものとしてカウントされません。 null: 指が完全に押されると、リスニングが開始されます。
            val tapped = withTimeoutOrNull<Boolean>(HOLD_MS) {
                var result: Boolean? = null
                while (result == null) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        ch == null -> false
                        !ch.pressed -> { ch.consume(); true }
                        (ch.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                        else -> null
                    }
                }
                result
            }
            when (tapped) {
                true -> { onTap(); return@awaitEachGesture }
                false -> return@awaitEachGesture
                null -> Unit
            }
            vh.begin()
            if (!vh.holding) return@awaitEachGesture
            var ended = false
            try {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) {
                        ch?.consume()
                        ended = true
                        vh.end()
                        break
                    }
                    ch.consume()
                    vh.drag(down.position.y - ch.position.y, threshold)
                }
            } finally {
                if (!ended) vh.abort()
            }
        }
    }
}

/** 押すと、入力フィールド全体が青に変わり (上にスライドするとキャンセルされ、赤に変わります)、中央の波形と「放すと入力」になります。ガラスの入力フィールドを覆います。 */
@Composable
internal fun VoiceBarCover(vh: VoiceHold, state: HoldToTalk.State, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val s = state as? HoldToTalk.State.Listening
    if (!vh.holding || s == null) return
    val c = MikaTheme.colors
    val label = when {
        s.cancelArmed -> stringResource(R.string.voice_release_cancel)
        s.live -> stringResource(R.string.voice_release_fill)
        else -> stringResource(R.string.voice_release_convert)
    }
    Row(
        modifier.clip(CircleShape).background(if (s.cancelArmed) c.bad else c.accent).testTag("voice-bar"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceWave(s.level, flat = s.cancelArmed, reduceMotion = reduceMotion, color = c.onAccent)
        Text(label, style = BarLabel, color = c.onAccent, maxLines = 1)
    }
}

/** 波形: 14 本の細いバー、高さはボリュームに従います。アニメーションをオフにすると、アニメーションはボリュームに追従するだけで、自動的には動きません。入力フィールドは、浮遊カプセルの発話パネルと共有されます。 */
@Composable
internal fun VoiceWave(level: Float, flat: Boolean, reduceMotion: Boolean, color: Color) {
    val loud by animateFloatAsState(if (flat) 0f else level.coerceIn(0f, 1f), if (reduceMotion) snap() else tween(120), label = "wave-level")
    val phase = if (reduceMotion || flat) 0f else rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "wave-phase",
    ).value
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(WAVE_BARS) { k ->
            val peak = 8f + kotlin.math.abs(kotlin.math.sin(k * 1.7f)) * 14f
            val swing = if (reduceMotion) 1f else 0.55f + 0.45f * kotlin.math.sin(phase + k * 0.9f)
            val h = if (flat) 4f else 5f + (peak - 5f) * (0.25f + 0.75f * loud) * swing
            Box(Modifier.width(3.dp).height(h.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

private const val WAVE_BARS = 14

/**
 * 入力フィールドの上に浮かぶレイヤーはレイアウトを占有しません (入力フィールドの前に配置し、高さを 0 として計算し、上向きに描画します)。
 * 「上にスワイプしてキャンセル」プロンプトを押すと、「リスニング」カードが表示され、はっきりと聞こえないなどの軽いプロンプトが表示されます。
 * [title] はカードの一番上の文です (通常は「聞いている」、タスクの実行中は「「...」に割り込む」)。
 */
@Composable
internal fun VoiceFloat(vh: VoiceHold, state: HoldToTalk.State, title: String, backdrop: GlassBackdrop?, reduceMotion: Boolean) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val listening = state as? HoldToTalk.State.Listening
    // カードを折るアニメーションも、直前の内容を描画する必要があります。
    val last = remember { arrayOfNulls<HoldToTalk.State.Listening>(1) }
    if (listening != null) last[0] = listening
    Box(
        Modifier.fillMaxWidth().layout { m, cons ->
            val p = m.measure(cons.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(cons.maxWidth, 0) { p.place(0, -p.height - gap) }
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val armed = listening?.cancelArmed == true
            AnimatedVisibility(vh.holding && listening != null, enter = fadeIn(tween(if (reduceMotion) 0 else 200)), exit = fadeOut(tween(if (reduceMotion) 0 else 150))) {
                CancelHint(armed, Modifier.padding(bottom = 14.dp))
            }
            AnimatedVisibility(
                listening != null,
                enter = if (reduceMotion) fadeIn(snap()) else
                    fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 12 } + scaleIn(tween(200), initialScale = 0.98f),
                exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
            ) {
                last[0]?.let { ListenCard(it, title, vh.engine, backdrop) }
            }
            val toast = vh.toast
            if (toast != null && listening == null) VoiceToast(toast)
        }
    }
}

/** 「上にスワイプしてキャンセル」用の小さなカプセル。上にスライドすると赤くなり、「放すとキャンセル」と表示されます。 */
@Composable
private fun CancelHint(armed: Boolean, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.floatShadow(c, shape).background(if (armed) c.bad else c.surface, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp).testTag("voice-cancel-hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (armed) c.onAccent else c.ink2
        Icon(MikaIcons.Up, null, Modifier.size(15.dp), tint = fg)
        Text(stringResource(if (armed) R.string.voice_release_cancel else R.string.voice_slide_cancel), style = HintText, color = fg)
    }
}

/**
 * 「リスニング」カード: 上部の赤い点 (録音)、誰に宛てたもの、どのテキストをいつ使用するか。途中のリアルタイムの単語 (最後のいくつかの単語は軽くなる場合があります)。
 * はリリース後に何が起こるかを示し、最後の 10 秒がカウントダウンされます。上にスライドしてキャンセルすると、文字が取り消され、枠線が赤くなります。
 */
@Composable
private fun ListenCard(s: HoldToTalk.State.Listening, title: String, engine: String, backdrop: GlassBackdrop?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(22.dp)
    GlassBar(
        Modifier.fillMaxWidth().testTag("voice-listen")
            .then(if (s.cancelArmed) Modifier.border(1.5.dp, c.bad, shape) else Modifier),
        backdrop, shape,
    ) {
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecDot(armed = s.cancelArmed)
                Text(title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (engine.isNotEmpty()) {
                    Text(
                        engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ChipText, color = c.ink2, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(ChatCopy.clock(s.elapsedMs), style = TimeText, color = c.ink3)
            }
            val heard = s.stable + s.tail
            val words = if (heard.isEmpty()) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink3)) {
                        append(stringResource(if (s.live) R.string.voice_say_now else R.string.voice_recording))
                    }
                }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink)) { append(s.stable) }
                    withStyle(SpanStyle(color = c.ink2)) { append(s.tail) }
                }
            }
            Text(
                words,
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 54.dp).alpha(if (s.cancelArmed) 0.4f else 1f),
                style = if (s.cancelArmed) ListenText.copy(textDecoration = TextDecoration.LineThrough) else ListenText,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(if (s.live) R.string.voice_foot_live else R.string.voice_foot_convert),
                    Modifier.weight(1f), style = FootText, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                VoiceCopy.countdown(s.elapsedMs)?.let { Text(it, style = FootText, color = c.warn) }
            }
        }
    }
}

/** カード上部のポイント: 記録中、青い円が外側に移動します。キャンセルすると赤くなり動かなくなります。 */
@Composable
private fun RecDot(armed: Boolean) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val halo = if (armed || reduce) 0f else rememberInfiniteTransition(label = "rec").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec-halo",
    ).value
    val dot = if (armed) c.bad else c.accent
    val soft = c.accentSoft
    Box(
        Modifier.size(8.dp).drawBehind {
            if (halo > 0f) drawCircle(soft, radius = size.minDimension / 2 + 5.dp.toPx() * halo)
            drawCircle(dot)
        },
    )
}

/** 入力フィールドの上にある軽いプロンプト (はっきりと聞こえませんでした。認識サービスがビジーです...) は、しばらくすると消えます。 */
@Composable
private fun VoiceToast(text: String) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier.background(c.ink.copy(alpha = 0.88f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 9.dp).testTag("voice-toast"),
        style = ToastText, color = c.bg, maxLines = 2,
    )
}

/** サービス プロバイダーを解放した後、[回転:] 入力ボックスに「テキストを回転...」を丸で囲んで追加します。 */
@Composable
internal fun VoiceConverting(modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.testTag("voice-converting"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner(Modifier.size(13.dp), spinning = !MikaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
        Text(stringResource(R.string.voice_converting), style = MikaType.Body, color = c.ink2, maxLines = 1)
    }
}

/** は、話すときに灰色の単語を押し続けることができます: 前面にある小さなマイク。 */
@Composable
internal fun VoicePlaceholder(mode: ComposerMode) {
    val c = MikaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(MikaIcons.Mic, null, Modifier.size(16.dp), tint = c.ink3)
        Text(ChatCopy.voicePlaceholder(mode), style = MikaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** を押して話すと、会話の内容がぼやけて色が薄くなり、フォーカスがカードに置かれます。 Android 12 以下ではフェードのみです。 */
@Composable
internal fun Modifier.voiceDim(on: Boolean, reduceMotion: Boolean): Modifier {
    val p by animateFloatAsState(if (on) 1f else 0f, if (reduceMotion) snap() else tween(250), label = "voice-dim")
    if (p == 0f) return this
    return graphicsLayer {
        alpha = 1f - 0.55f * p
        if (GlassBlurSupported) {
            val r = 1.5.dp.toPx() * p
            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
        }
    }
}

/** 「音声入力はまだ利用できません」 (「設定に移動」して「テキスト」を選択) と録音許可の説明 (システムのミカの許可ページに「設定に移動」)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheetHost(vh: VoiceHold, onPickSpeech: () -> Unit) {
    val sheet = vh.sheet ?: return
    val c = MikaTheme.colors
    val context = LocalContext.current
    val title: String
    val body: String
    val action: String
    val go: () -> Unit
    when (sheet) {
        is VoiceSheet.Unavailable -> {
            title = stringResource(R.string.voice_unavailable_title)
            body = VoiceCopy.unavailable(sheet.why)
            action = stringResource(R.string.voice_go_pick)
            go = onPickSpeech
        }
        VoiceSheet.Permission, VoiceSheet.MicBlocked -> {
            val blocked = sheet == VoiceSheet.MicBlocked
            title = stringResource(if (blocked) R.string.voice_mic_blocked_title else R.string.voice_permission_title)
            body = stringResource(if (blocked) R.string.voice_mic_blocked_body else R.string.voice_permission_body)
            action = stringResource(R.string.voice_permission_open)
            go = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vh.sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 18.dp).testTag("voice-sheet")) {
            Text(title, style = MikaType.Title, color = c.ink)
            Text(body, Modifier.padding(top = 8.dp), style = SheetBody, color = c.ink2)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(stringResource(R.string.ok_got_it), { vh.sheet = null }, Modifier.weight(1f), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
                MikaButton(action, { vh.sheet = null; go() }, Modifier.weight(1f), size = ButtonSize.Medium)
            }
        }
    }
}

private val BarLabel = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val HintText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ListenText = TextStyle(fontSize = 18.sp, lineHeight = 27.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val ToastText = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
private val SheetBody = TextStyle(fontSize = 14.sp, lineHeight = 23.sp)
