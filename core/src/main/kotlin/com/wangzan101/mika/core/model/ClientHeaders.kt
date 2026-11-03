package com.wangzan101.mika.core.model

import okhttp3.Request
import java.util.UUID

/**
 * 会話の外部セッション番号：同一会話の各リクエスト（メインモデル、画像閲覧、軽量化、タイトル作成、検証、意図判断、停止後の説明）は同じものを使用します。
 * これに基づいて、サービス プロバイダーはリクエストを 1 か所にルーティングし、プロンプト ワード キャッシュを再利用します。会話 ID (UUID v3、不可逆) から推定されるため、会話 ID 自体は送信されません。
 * どの会話にも属さないリクエスト (接続テスト、列モデル) は、毎回新しい番号 [fresh] を使用します。
 */
@JvmInline
value class ModelSession(val id: String) {
    companion object {
        fun of(conversationId: String): ModelSession =
            ModelSession(UUID.nameUUIDFromBytes("mika-conversation:$conversationId".toByteArray()).toString())

        fun fresh(): ModelSession = ModelSession(UUID.randomUUID().toString())
    }
}

/** モデル サービスに送信される各リクエストにはヘッダーが含まれます。 Key ヘッダーは各アダプターによって追加されます。 */
object ClientHeaders {
    /**
     * [userAgent] は、Mika のバージョンを示します (アプリによって与えられ、コアはバージョン番号を書き込みません)。 [sessionHeader] は、サービス プロバイダーによって宣言されたセッション番号の名前です。
     * ステートメントがない場合 (null または空)、セッション番号は送信されません。
     */
    fun of(userAgent: String, sessionHeader: String?, session: ModelSession): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (!sessionHeader.isNullOrBlank()) put(sessionHeader.trim(), session.id)
    }
}

internal fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder =
    apply { headers.forEach { (k, v) -> header(k, v) } }
