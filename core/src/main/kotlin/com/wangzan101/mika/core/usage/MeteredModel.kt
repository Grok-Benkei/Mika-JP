package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext

/** 呼び出しが発行される目的。 [key] はストレージに記録されます。認識されないキー (新しいバージョンで書き込まれた) は、[Other] として読み取られます。 */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** それは誰のモデルで、何の目的で作られていますか?利用記録時はそのままお持ちください。 [providerName] は、モデルを構築するときのサービス プロバイダーの名前です (サービス プロバイダーが削除されても保持されます)。 */
data class UsageSource(val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose)

/** への呼び出しの結果: [usage] は null です。これは、サービス プロバイダーが使用状況を報告しなかった (または呼び出しが失敗した) ことを意味します。今回は回数のみがカウントされ、トークンの推定は行われません。 */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)
}

/**
 * モデルに測定値を追加: [ChatModel.complete] (およびテキストの段落) が終了するたびに、合計が記録されます。成功して金額が記録されると、トークンが記録されます。
 * は、使用状況が報告されず、エラー (キャンセルを含む) がスローされた回数のみをカウントします。呼び出し元に対して透過的: 名前と機能は同じままで、例外はそのままスローされ、コールバックは通常どおり部分的に作成されます。
 * のオリジナル モデルは段落ごとのテキストをサポートしており、セットされたモデルもそれをサポートしています。アカウンティング自体のエラーは通話には影響しません。
 */
fun ChatModel.metered(source: UsageSource, sink: UsageSink): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink) else MeteredModel(this, source, sink)

private open class MeteredModel(private val inner: ChatModel, protected val source: UsageSource, protected val sink: UsageSink) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted { inner.complete(system, messages, tools) }

    protected suspend fun counted(call: suspend () -> ModelReply): ModelReply {
        // は実行中に呼び出され、この実行の名前にも記録されます (RunUsageTally を参照)
        val tally = currentCoroutineContext()[RunUsageTally]
        val reply = try {
            call()
        } catch (e: Throwable) {
            note(null, tally)
            throw e
        }
        note(reply.usage, tally)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?) {
        runCatching { sink.record(source, usage) }
        tally?.add(usage)
    }
}

private class MeteredStreamingModel(private val inner: StreamingChatModel, source: UsageSource, sink: UsageSink) :
    MeteredModel(inner, source, sink), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted { inner.completeStreaming(system, messages, tools, onText) }
}
