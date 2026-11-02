package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * トーク セッションを長押しすると、2 つのエンジン (携帯電話の内蔵認識、サービス プロバイダーの文字起こし) に同じインターフェイスが表示されます。
 * インターフェイスは、背後にあるエンジンの種類に関係なく、[events] のみを受け入れ、リリース時に [release] を調整し、キャンセル時に [cancel] を調整します。
 */
sealed interface SpeechEvent {
    /** 0..1、波形を描画します。 */
    data class Level(val value: Float) : SpeechEvent

    /** 私がこれまでに聞いた全文: [tail] は変更される可能性がある最後のいくつかの単語で、残りは [stable] です。 */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** サービスプロバイダー：緩めてから回してください。 */
    data object Converting : SpeechEvent

    /** 最終テキスト;空の文字列 = はっきりと聞こえませんでした。 */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    /** マイクを開始できません (識別サービスがクライアントでエラーを報告し、リッスンを開始するとエラーがスローされ、サービス プロバイダーのパスの記録を開始できません): 他のアプリケーションによって占有されている可能性があります。 */
    data object MicUnavailable : SpeechFailure
    /**
     * 認識サービスが録音エラー(ERROR_AUDIO)を報告しました: 録音許可が与えられていても録音できない場合があります。たとえば、メイン スイッチを使用してシステム マイクをオフにします。
     * またはシステム権限管理でマイクが個別にオフになっています。インターフェースはどこを見るべきかを示します。
     */
    data object AudioError : SpeechFailure
    data class Provider(val error: ModelError) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** は 1 人のコレクターにのみ与えられます。各イベントは 1 回だけ送信され、ストリームは [SpeechEvent.Final]/[SpeechEvent.Failed] または [cancel] の後に終了します。 */
    val events: Flow<SpeechEvent>

    /** は、発話中に単語を生成できます (システムとネイティブ ストリーミング ASR は true、ファイル全体のトランスコーディングは false)。 */
    val live: Boolean

    /** リリース: まとめてファイナルを与えます。 */
    fun release()

    /** は破棄されました。冪等。 */
    fun cancel()
}

/**
 * セッション イベント終了: 無制限のバッファリング。インターフェイスは失われることなく後で収集されます。終了イベントが送信された後に閉じられます。
 * キャンセルするときは、持ち去られていないアイテムも一緒に捨ててください。ユーザーは今回はもう必要ないので、遅れた結果が再び表示されることはありません。
 */
internal class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    /** 最後のイベントを送信して閉じます。終了またはキャンセルされた場合は何もしません。今度は本当に送信されたかどうかを確認してください。 */
    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    /** は、キャンセルするのが初めてかどうかを返します。 */
    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** 最初から使用できないセッション (権限なし、エンジンが使用不可): Failed が 1 つだけ送信されます。 */
internal class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}
