package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * ビジュアル モデルに写真を見て質問に答えてもらいます。設定で表示できるモデルがある場合にのみビルドしてください。
 * 呼び出し元には、保護されたページと支払いページを渡さなかった責任があります。
 */
class VisionService(private val model: ChatModel, val ops: ImageOps) {
    /** このモデルの画像位置精度は検証されています。検証されていない場合、find_visually には、画像フレームを確認することによってのみ決定できるポイント要件を裏付ける追加の証拠があります。 */
    val groundingVerified: Boolean get() = model.capabilities.groundingVerified

    /** はツールを使用しない呼び出しです。失敗すると [ModelError] がスローされます。 */
    suspend fun ask(system: String, text: String, images: List<ByteArray>): String {
        val parts = listOf(Part.Text(text)) + images.map { ModelImage.part(it, ops) }
        val reply = model.complete(system, listOf(Message(Role.User, parts, Author.Mika)), emptyList())
        val answer = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        if (answer.isBlank()) throw ModelError.BadResponse("画像認識モデルが回答を返しませんでした")
        return answer
    }
}
