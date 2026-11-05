package com.wangzan101.mika.cap.phone.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.PairingCode
import com.wangzan101.mika.ui.PhoneSetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ペアリングの全プロセス: ペアリング ウィンドウを見つけ、障害物がなく開いたら自分でペアリング コード ([PairingCodeReader]) を読み取ります。
 * がポップアップ入力ボックスの通知を読み取ることができない場合は、コードを組み込みアシスタント [control] に渡し、結果を表示します。
 * はインターフェイスではなくプロセスに従います (ユーザーがシステム設定でペアリング コードを見ると、Mika のインターフェイスは最前面に表示されなくなります): [com.wangzan101.mika.cap.phone.PhoneServices] でコピーを作成します。
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    /** ペアリング、再試行、ペアリング解除などのアクションもここで実行されます。これらのアクションは、インターフェイスが閉じている場合でも完了する必要があります。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private val deadline = SlidingDeadline(WINDOW_MS) { SystemClock.elapsedRealtime() }
    /** 通知に現在表示されているもの; 再度知らせる前に照合し、別の内容に変わっていれば（たとえばコードが入力された直後）古い内容は出さない。 */
    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    /** ユーザーが「再ペアリング」をタップし、新しいペアリングがまだ始まっていない: ヘルパーが動作中でも新しいペアリングを待つ。 */
    val repairing: StateFlow<Boolean> = _repairing
    /**
     * コードが入力され、ペアリングがまだ始まっていない（[submit] から [pair] が状態を「ペアリング中」に変えるまで）: この間、通知はすべて「ペアリング中」になる。
     * [submit] 内の [begin] により、ウィンドウを探す状態はその場で「探している」へ戻る。そうしないと、通知がコード入力前の状態で再計算されてしまう
     * (ペアリングに失敗した場合は、「ペアリングに失敗しました」という意味です。間違ったコードを入力して再入力すると、入力ボックスから再びビープ音が鳴ります)。ステータス更新通知はこの時点では終了しません。[PairingNotice.follow] を参照してください。
     */
    @Volatile private var submitted = false

    fun startRepair() { _repairing.value = true }

    fun cancelRepair() { _repairing.value = false }

    /** はペアリングを開始します。ステータスが「ペアリング中」に変わるとすぐに、再ペアリングの待機 (古い実行ステータスによって完了と判断されるのを避けるため) とコード ([submitted]) を入力した後の待機を終了します。 */
    suspend fun pair(code: String, port: Int?) = coroutineScope {
        Log.i(TAG, "${if (port != null) "ポート $port" else "検出したポート"}でペアリングします")
        val job = launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }
        _repairing.value = false
        submitted = false
        job.join()
    }

    /** はペアリングの確認を開始します。すでにご覧になっている場合は、[WINDOW_MS] 以降、最初からやり直すことなく、制限時間がリセットされます (通知されずに最初からやり直されるため)。 */
    @Synchronized
    fun begin() {
        deadline.extend()
        control.startDiscovery()
        if (watch?.isActive == true) {
            Log.i(TAG, "探索中のため期限を延長しました")
            return
        }
        Log.i(TAG, "ペアリングを探索します（状態 ${control.status.value}、ユーザー補助 ${ScreenAccessibility.instance.value != null}）")
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            val notices = launch {
                // 3 つの項目はそれぞれ、コルーチンによって結合するために送信されます。送信の順序は必ずしも変更の順序ではありません (最初に変更された「ペアリング」が後で到着する可能性があります)。毎回、現在の値に従って計算されます。
                // は書き込みと逆の順序で読み出されます。後で書き込まれた値を読み取る場合、最初に書き込まれた値も読み取られます: submit。最初に submit と書いてから、検索ウィンドウのステータスを「検索中」に戻します。
                // ペア 最初に「ペアリング」と書き込み、次に修復をクリアして送信します
                combine(control.status, control.pairingDiscovery, _repairing) { _, _, _ ->
                    val d = control.pairingDiscovery.value
                    val pending = submitted
                    val r = _repairing.value
                    PairingNotice.follow(control.status.value, d, r, pending)
                }
                    .distinctUntilChanged()
                    .onEach { (notice, done) -> Log.i(TAG, "通知 ${notice?.let { it::class.simpleName } ?: "なし"}（探索 ${control.pairingDiscovery.value}、状態 ${control.status.value}）${if (done) "、完了" else ""}") }
                    .transformWhile { emit(it.first); !it.second }
                    // ステータスが変化すると、その時点に到達していないポップアップとリマインダーは破棄されます。
                    .collectLatest { notice -> notice?.let { post(it) } }
            }
            if (!deadline.await(notices)) {
                Log.i(TAG, "${WINDOW_MS / 60_000}分以内にペアリングできないため探索を終了しました")
                notices.cancel()
                cancelNotice()
            }
            reader.cancel()
            control.stopDiscovery()
        }
    }

    /** は [notice] を送信します。誰も気にしなければ、しばらくすると再び再生されます。回数には上限があります。 */
    private suspend fun post(notice: PairingNotice) {
        delay(PairingNotice.delayBefore(notice, autoRead = ScreenAccessibility.instance.value != null))
        show(notice)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice)
            sent++
        }
    }

    private fun show(notice: PairingNotice) {
        Log.i(TAG, "通知 ${notice::class.simpleName} を表示します")
        shown = notice
        PairingNotifier.show(app, notice)
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        if (watch?.isActive == true) Log.i(TAG, "探索を終了しました")
        watch?.cancel()
        watch = null
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    /** 6桁の数字以外は再入力を案内して null を返します。有効ならペアリング中へ切り替え、通知側が完了を待てる Job を返します。 */
    fun submit(raw: String, port: Int?): Job? {
        val code = PairingCode.normalize(raw)
        if (code == null) {
            Log.i(TAG, "入力されたコードが6桁ではありません")
            show(PairingNotice.EnterCode(port, PhoneSetupCopy.NOTIFY_CODE_INVALID))
            return null
        }
        submitted = true
        begin()
        show(PairingNotice.Working)
        return scope.launch { pair(code, port) }
    }

    private companion object {
        const val TAG = "MikaPairing"
        /** のペアリング期間は期間限定で公開されています。最後の [begin] から長い間一致しなかった場合、再度検索されることはなく、次回 [begin] から再度検索されます。 */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** 入力ボックスの着地点を通知します。 */
open class PairingCodeInput : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        val pairing = PhoneServices.of(context).pairing
        val job = pairing.submit(code, port) ?: return
        val pending = goAsync()
        pairing.scope.launch {
            try {
                withTimeoutOrNull(HOLD_MS) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** ブロードキャストの応答期限10秒に余裕を残し、ポート探索5秒とペアリング処理を待ちます。 */
        const val HOLD_MS = 8_000L
    }
}
