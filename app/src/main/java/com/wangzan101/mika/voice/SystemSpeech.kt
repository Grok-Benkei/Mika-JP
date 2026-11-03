package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** 携帯電話に登録された識別サービス。[SystemSpeech.services] を参照。 [component] は「パッケージ名/クラス名」の略称です。 */
data class RecognizerService(val component: String, val open: Boolean, val permission: String?)

/** 携帯電話には、能力検出、エラー コードの分類方法、識別に使用される言語といった識別機能が組み込まれています。 */
object SystemSpeech {
    /**
     * Android 11 以降、クエリ RecognitionService はマニフェストで宣言する必要があります。宣言しないと、ここで使用できなくなります。
     * [silent] は、サービスが以前に試行した後に応答しなかったことを示します ([SpeechSettings.systemSilent] を参照)。システムは、サービスが利用可能かどうかしか判断できず、試してみるまでわかりません。
     */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice, responsive = !silent)
    }

    /**
     * は、サービスのエラー コードがどの種類の障害に属するかを識別します。 null は、「このセクションでは単語が聞こえなかった」ことを意味します (一致も開始もありません)。押すと再起動し、放すと結果は以前と同じになります。
     * リスト外のコード (サーバーエラー、サポートされていない言語など) はサービスの問題として処理されます。
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.AudioError
        SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.MicUnavailable
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    /** エラー コードが届いた後の対処法: 再起動し、聞こえた最後を押して、失敗を報告します。 */
    sealed interface Outcome {
        data object Retry : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] は手がまだ押していることを意味し、[heard] は言葉が聞こえたことを意味します。聞いた単語を失わないでください。単語を放した後、または押しているときに間違えた場合は、聞いた単語で終了してください。
     * がリリースされた後、一部のサービスは stopListening 後に ERROR_CLIENT を報告します。何も聞こえない場合は、音がはっきりと聞こえていないことを意味します。マイクが壊れているわけではありません。
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == SpeechRecognizer.ERROR_CLIENT -> Outcome.Finish
            else -> Outcome.Fail(failure)
        }
    }

    /**
     * がリッスンを開始した後、[SystemSpeechSession.NO_RESPONSE_MS] の認識サービスがコールバックを与えない場合はどうすればよいですか。
     * [answered] この認識エンジンは以前に応答しました (サービスは良好ですが、このセクションが行き詰まっていることを示しています): 聞こえた音声の最後を押してください。何も聞こえない場合は、はっきりと聞こえなかっただけです。
     * は応答しませんでした。これは、この携帯電話の識別サービスがサードパーティ アプリの要求を無視することを意味します。これ以上待たないと、メッセージは表示されなくなります。 [SpeechEngineSpec.Why.SystemNoResponse] をレポートします。
     */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /**
     * 手を放して待ちます。 [SystemSpeechSession.FINAL_WAIT_MS] 最終結果がまだない場合に閉じる方法。
     * は [afterSilence] と同じです。返信すると、聞いた内容で終了します。返信しない場合は、応答がないことを意味します。
     * 返信がないからといって何もすることはできません。返信しないと、サスペンション カプセルが「テキストを変換...」で停止します。
     */
    fun afterReleaseWait(answered: Boolean): Outcome = afterSilence(answered)

    /**
     * 携帯電話に登録された認識サービス (RecognitionService のコンポーネントが宣言されています)。 [RecognizerService.open] は、他のアプリに対してオープン (エクスポート) されていることを意味します。
     * [RecognizerService.permission] は、バインドするために必要な権限です。診断情報および [attempts] については。
     */
    fun services(context: Context): List<RecognizerService> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { r ->
            val s = r.serviceInfo
            RecognizerService(ComponentName(s.packageName, s.name).flattenToShortString(), s.exported, s.permission)
        }
    }.getOrDefault(emptyList())

    /**
     * 識別サービスは 1 つのセッションで次々に試行されます。null はシステムのデフォルトのサービスです (名前は付けられておらず、システムによって選択されています)。
     * 、以前に発信して応答したもの ([remembered]、まだ電話中) が最初にランクされます。デフォルトのコールバックが指定されていない場合は、他のコールバックが呼び出されて最初に試行され、他のアプリで開かれているコールバックが最初に試行されます。
     * 電話機にサービスが 1 つだけある場合、それがデフォルトのサービスとなり、名前を付けて再試行する必要はありません。最大でも [MAX_ATTEMPTS] を試すことができるため、ボタンを押す人を待つ必要はありません。
     */
    fun attempts(services: List<RecognizerService>, remembered: String?): List<String?> {
        val known = remembered?.takeIf { r -> services.any { it.component == r } }
        val others = if (services.size < 2) emptyList() else
            services.filter { it.component != known }.sortedBy { !it.open }.map { it.component }
        return (listOfNotNull(known) + listOf(null) + others).take(MAX_ATTEMPTS)
    }

    /** 1 つのセッションで最大複数の識別サービス (デフォルトのサービスを含む) を試行できます。 */
    const val MAX_ATTEMPTS = 3

    /** ローカル認識で、この言語は使用できないと言われます (言語パックがインストールされていないなど): ネットワーク認識に変更して再試行してください。 */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** 音声認識の言語。日本語専用フォークでは常に ja-JP。引数は互換のために残す。 */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = "ja-JP"

    /**
     * は「言語領域」のみを残します。システム言語にはテキスト サブタグ (zh-Hans-CN) が含まれることが多く、認識サービスは通常 zh-CN のみを認識します。
     * は 1 段落を超える場合、サポートされていない言語とみなされます。拡張機能とバリアントも削除されます。
     */
    private fun languageRegion(locale: Locale): String =
        Locale.Builder().setLanguage(locale.language).setRegion(locale.country).build().toLanguageTag()

    /** は、インターフェイスに必要な ISO-639-1 言語コードを転写します。 */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // は、Android 12 以降でのみ利用可能なエラー コードです。数値として書かれています。下位バージョンが受信できない場合は影響を受けません。
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * 携帯電話自体によって認識されるセッション。 [SpeechRecognizer] はメインスレッドでのみ作成および調整できるため、ここでのすべての操作はメインスレッドにキャストされます。
 * 期間識別サービスを押すと、セクションが自動的に終了し (最終結果が表示されるか、「聞こえませんでした」)、すぐに再開されます。各セクションは [Segments] によってまとめられます。
 * が解放された後に StopListening を実行し、最終結果を取得するまで最大 [FINAL_WAIT_MS] まで待機します。取得できない場合は、最後の中間結果を使用します。
 * 各 startListening の後、[NO_RESPONSE_MS] のサービスに対するコールバックはありません。手が押されたままで、[attempts] には未試行のサービスがあるため、次のサービスに変更してリッスンします。
 * は試行されましたが、[SystemSpeech.afterSilence] で終了しました。電話に出なかった場合は、[onNoResponse] にダイヤルしてください。電話に出たら [onAnswered] を 1 回ダイヤルし、コールバック サービスを開始します。
 * (null はシステムのデフォルト): この数値に従って、応答しない回数を設定します。十分に理解してから録音してください。試してみようと名付けた名前も覚えておいてください。
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: (String?) -> Unit = {},
    /** で識別サービスを順番に試行するには、[SystemSpeech.attempts] を参照してください。 null はシステムのデフォルトです。 */
    private val attempts: List<String?> = listOf(null),
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // 以下はメインスレッドでのみ読み書きされます
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    /** は [attempts] の番号を試行しています。 */
    private var attempt = 0
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    /** 現在の認識機能が応答しませんでした (コールバック数はありません)。 このセッションでは、 */
    private var answered = false
    /** がすでに報告されています。 [onAnswered] は、ボリューム コールバックごとに設定を書き込む必要がないように、セッションごとに 1 回だけ報告されます。 */
    private var reported = false
    /** このセッションは、無応答によりすでに 1 回減点されています。解除後の待機や無音のタイミングが連続して発生する場合があり、2度録画することはできません。 */
    private var notedSilent = false
    // を解放し、[FINAL_WAIT_MS] を待ちます。返信したら、聞いたとおりに終了します。コールバックがない場合でも終了する必要があり、「テキストに変換...」で停止することはできません。
    private val timeout = Runnable { concludeRelease() }
    private val silence = Runnable {
        if (done()) return@Runnable
        if (!answered && holding && attempt + 1 < attempts.size) {
            // このサービスはコールバックを 1 つも返しませんでした。手を押し続けたまま、次のサービスに切り替えてリッスンを続けます。
            attempt++
            open(preferOnDevice = false)
            return@Runnable
        }
        concludeSilence()
    }

    /** は最後まで沈黙し、次のサービスに移行せず、応答があるかどうかに応じて終了します。同じセッションに応答しないことは 1 回だけ記録されます。 */
    private fun concludeSilence() {
        if (done()) return
        when (val o = SystemSpeech.afterSilence(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    private val restart = Runnable {
        restartPending = false
        if (done()) return@Runnable
        if (holding) listen() else concludeRelease()
    }

    /** 指を離した後の待ち時間が経過した場合、または指を再度開いたときに指が離れていることに気付いた場合は、このセッションを終了する必要があります。 */
    private fun concludeRelease() {
        if (done()) return
        when (val o = SystemSpeech.afterReleaseWait(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }

    private fun noteSilent() {
        if (notedSilent) return
        notedSilent = true
        onNoResponse()
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (restartPending) {
            // 前のセグメントは終了し、まだ再開されていません。聞いていない場合は、聞いた終了ボタンを押してください。
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        try {
            recognizer?.stopListening()
        } finally {
            // stopListening の同期コールバックによってセッションが終了したか、待機が削除された可能性があります。まだ終わっていない場合は、再度キューに入れる必要があります。
            if (!done()) main.postDelayed(timeout, FINAL_WAIT_MS)
        }
    }

    override fun cancel() {
        // まずイベント出口を閉じて (どのスレッドもすぐに有効になります)、次にメインスレッドが認識機能をクリーンアップします。
        if (!out.cancel()) return
        onMain {
            recognizer?.cancel()
            teardown()
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
        usingOnDevice = r != null
        // 点呼サービスに使用します。点呼が行われない場合 (または名前が認識できない場合)、システムのデフォルト サービスに任せられます。
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)
        val rec = r ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context, named) }.getOrNull()
        if (rec == null) {
            finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)))
            return
        }
        recognizer = rec
        answered = false
        rec.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // インターネット上で識別する場合：携帯電話にローカル識別機能がある場合は、可能な限りオフラインで識別するようサービスに依頼してください。
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        main.removeCallbacks(silence)
        main.postDelayed(silence, NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (e: RuntimeException) {
            finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        }
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    /** を押すと、このセクションは空になります。[Restarts] を押して、再起動にかかる時間を決定するか、再起動しないかを決定し、聞こえたエンディングを押します。再起動は常にメインスレッドで行われ、コールバックで直接開始されることはありません。 */
    private fun emptySegment() {
        when (val d = restarts.onEmpty(android.os.SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> {
                restartPending = true
                main.postDelayed(restart, d.delayMs)
            }
        }
    }

    /** サービスは次のように応答しました: この期間は応答なしとみなされません。 */
    private fun heard() {
        answered = true
        main.removeCallbacks(silence)
        if (!reported) {
            reported = true
            onAnswered(if (usingOnDevice) null else attempts.getOrNull(attempt))
        }
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        restartPending = false
        val rec = recognizer
        recognizer = null
        // 最初に削除してから、destroy:destroy が onError を同期的にコールバックする可能性があります。現時点では、エンジンを変更することはできません。変更しない場合、エンジンはネストされ、障害処理で再起動されます。
        usingOnDevice = false
        rec?.let { runCatching { it.destroy() } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = heard()
        override fun onBeginningOfSpeech() = heard()
        override fun onBufferReceived(buffer: ByteArray?) = heard()
        override fun onEndOfSpeech() = heard()
        override fun onEvent(eventType: Int, params: Bundle?) = heard()

        override fun onRmsChanged(rmsdB: Float) {
            heard()
            if (done()) return
            // 識別サービスでは、およそ -2..10 dB が得られ、圧力は 0..1 に減らされて波形が描画されます。
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                // 識別サービスは 1 つの文が終了したように感じますが、手がまだ押されています: 次の段落を聞き続けます
                if (text.isBlank()) {
                    emptySegment()
                } else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            if (recognizer == null || done()) return
            heard()
            if (recognizer == null || done()) return
            if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
                open(preferOnDevice = false)
                if (!holding && !done()) {
                    // がリリースされました: 新しい識別子は終了したばかりで、上限が再計算されるのを待っています。
                    recognizer?.stopListening()
                    main.postDelayed(timeout, FINAL_WAIT_MS)
                }
                return
            }
            when (val o = SystemSpeech.afterError(error, holding, heard = segments.full().isNotEmpty())) {
                SystemSpeech.Outcome.Retry -> emptySegment()
                SystemSpeech.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
                is SystemSpeech.Outcome.Fail -> finish(SpeechEvent.Failed(o.why))
            }
        }
    }

    // はすべての属性の後に配置されます。onMain はメインスレッド上でその場で実行され、open で使用されるリスナーなどの属性はこの時点で初期化されます。
    init {
        onMain { open(onDevice && attempts.firstOrNull() == null) }
    }

    companion object {
        /** 解放後は最終結果の上限まで待ちます。 */
        const val FINAL_WAIT_MS = 2_000L

        /**
         * リッスン開始後の認識サービスの最初のコールバック (通常は onReadyForSpeech、通常は数百ミリ秒以内) を待機する上限。
         * サービス プロセスのコールド スタートを識別する時間を確保してください。この期間を超えると応答なしとみなされます。
         */
        const val NO_RESPONSE_MS = 3_000L
    }
}
