package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.observation.ReadTag
import kotlinx.serialization.Serializable

@Serializable
sealed interface Part {
    @Serializable
    data class Text(val text: String) : Part
    /**
     * [width]/[height]: エンコードされたピクセル サイズ、0 は不明を意味します (トークンを推定するためにのみ使用されます)。
     * [screenshotId]: この画像は、どの画面読み取り時間のものです (view_past_screens を使用して確認できます)。画面ピクチャ (読み込みイメージ ファイル) でない場合は、null になります。
     */
    @Serializable data class Image(
        val pngBase64: String,
        val mime: String = "image/png",
        val width: Int = 0,
        val height: Int = 0,
        val screenshotId: Int? = null,
    ) : Part
    /**
     * [opaque]: adapter-private data that the SAME adapter must send back unchanged when this call is
     * replayed; other adapters ignore it.
     */
    @Serializable data class ToolCall(val id: String, val name: String, val argsJson: String, val opaque: String? = null) : Part
    /**
     * [reads]: 観察結果 (数値と形式) が結果から読み取られ、証拠台帳に記録されます。これは、Mika の内部でのみ使用され (観察フォームに従ってクロップが短縮され、短縮後に番号が返されます)、サービス プロバイダーには送信されません。
     */
    @Serializable data class ToolResult(val callId: String, val content: String, val isError: Boolean, val reads: List<ReadTag> = emptyList()) : Part
    /**
     * [opaque]: [ToolCall.opaque] と同様に、アダプターに対してプライベートです。この考えがどこから来たのかに注意してください (たとえば、そのアドレスとフィールドは互換性のあるインターフェイスで受信されます)。
     * を再生する場合、それを認識するアダプターのみがそれを使用します。他のアダプターはそれを監視しません。古い履歴や再生する必要のない履歴は null です。
     */
    @Serializable data class Thinking(val text: String, val opaque: String? = null) : Part
}

@Serializable
enum class Role { System, User, Assistant, Tool }

/** メッセージを書いた人: [User] はユーザー自身が発言したもの (タスクの元の単語、タスク中に追加された単語、写真)、[Mika] はミカによって生成されたもの (メモ、コンテキスト、ツールの往復)。 */
@Serializable
enum class Author { User, Mika }

/**
 * [author] 必ず指定してください (デフォルト値なし): 新しく追加された場所にユーザーの言葉や写真が配置された場合、それらはミカのメモとして切り取られません。
 * は、Mika によって内部でのみ使用され (圧縮中にユーザーを保持するため)、サービス プロバイダーには送信されません。
 */
@Serializable
data class Message(val role: Role, val parts: List<Part>, val author: Author)

data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

enum class StopReason { EndTurn, ToolUse, MaxTokens, Other }

/**
 * [inputTokens] は、入力トークンの合計 (キャッシュ ヒット部分、OpenAI プロンプト トークン キャリバーを含む) です。 [outputTokens] には思考が含まれています。
 * [reasoningTokens] は、サービスプロバイダーによって別途報告される思考トークンです (Anthropic など、別途報告されないものは 0)。
 */
data class Usage(val inputTokens: Int, val outputTokens: Int, val cachedInputTokens: Int = 0, val reasoningTokens: Int = 0)

data class ModelReply(val parts: List<Part>, val stopReason: StopReason, val usage: Usage?)
