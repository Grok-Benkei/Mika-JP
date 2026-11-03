package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** レコーダーの最小インターフェイス: [PcmRecorder] これを実装し、テストを偽のものに置き換えます。 */
interface Recorder {
    val level: StateFlow<Float>
    fun start(): Boolean
    fun stop(): ShortArray
    fun cancel()
}

/**
 * サービス プロバイダーのメソッド: 押すと、録音は [SpeechEvent.Level] のみを送信します。解放されると、[SpeechEvent.Converting] が送信されます。
 * は、録音全体を [transcribe] に渡し (WAV 入力、テキスト出力、失敗時に [ModelError] をスロー)、最終または失敗 (プロバイダー) を与えます。
 * 構築時に記録を開始します。録音が不十分な場合 [MIN_MS] はっきりと聞こえない場合は、サービスプロバイダーに電話しないでください。
 * は [timeoutMs] (デフォルトは [TRANSCRIBE_TIMEOUT_MS]) まで転写できます。ネットワークが利用できない場合、タイムアウトが発生すると「Failed (Network)」が表示され、インターフェイスは回転し続けません。
 */
class ProviderSpeech(
    private val recorder: Recorder,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = TRANSCRIBE_TIMEOUT_MS,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false

    private var levels: Job? = null
    private var work: Job? = null
    private var released = false

    init {
        if (!recorder.start()) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        } else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
        }
    }

    @Synchronized
    override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        levels?.cancel()
        // stop は、読み取りループが終了する (一時的にブロックされる) まで待機し、それをバックグラウンド スレッドに置きます。メインスレッドをブロックしないでください。
        work = scope.launch {
            val pcm = recorder.stop()
            if (pcm.size < Wav.RATE * MIN_MS / 1000) {
                out.finish(SpeechEvent.Final(""))
                return@launch
            }
            out.send(SpeechEvent.Converting)
            val wav = Wav.encode(pcm)
            // 接続は戻りません (クライアントには長い読み取りタイムアウトがあるだけで、全体的なタイムアウトはありません)。ポイントに到達するまで待機しません。
            // タイムアウトブロックでエラーが発生した場合はキャッチされ、エラーはそのまま引き継がれます（コルーチンの境界を越えず、コピーに置き換えられません）
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    SpeechEvent.Final(transcribe(wav))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelError) {
                    SpeechEvent.Failed(SpeechFailure.Provider(e))
                } catch (e: Exception) {
                    SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse(e.message.orEmpty(), e)))
                }
            } ?: SpeechEvent.Failed(SpeechFailure.Network)
            out.finish(result)
        }
    }

    @Synchronized
    override fun cancel() {
        if (!out.cancel()) return
        levels?.cancel()
        work?.cancel()
        recorder.cancel()
    }

    companion object {
        /** これより短い録音は、誤ってトリガーされたことが原因である可能性が高いため、はっきりと聞こえなかったものとして扱ってください。 */
        const val MIN_MS = 500

        /** は、緩めた後にサービス プロバイダーが転記するまでの待機時間の上限です。最大 60 秒の録音は、通常、数秒以内に書き起こされます。 */
        const val TRANSCRIBE_TIMEOUT_MS = 30_000L
    }
}
