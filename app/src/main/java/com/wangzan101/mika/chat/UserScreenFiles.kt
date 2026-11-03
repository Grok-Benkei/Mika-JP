package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenJson
import com.wangzan101.mika.core.userscreen.UserScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ユーザーによって送信された画面は会話とともに保存され、その会話のワークスペース (`/workspace/screens/<番号>.jpg` および `.json`) の添付ファイルと同じ場所に配置されます。
 * ダイアログ ワークスペースは削除されると消えます。サンドボックス内のコマンドでもこの画像を読み取ることができます。読み取りと書き込みはすべてワークスペースのファイル ゲートを通過します (サンドボックス内のコマンドがリンクに置き換えられると、他の場所では読み取りまたは書き込みができなくなります)。
 * は、ツリー ロック許可 (およびミューテックスのリセット) を使用して書き込みます。
 */
class UserScreenFiles(private val dirs: SandboxDirs) {
    constructor(context: Context) : this(SandboxDirs(context))

    /** は会話 [conversationId] を保存し、番号付きの説明を返します。 */
    suspend fun save(conversationId: String, shot: CapturedScreen): UserScreen = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val id = "s" + UUID.randomUUID().toString().replace("-", "").take(12)
            val screen = shot.screen.copy(id = id)
            val ws = dirs.workspace(conversationId)
            ws.mkdirs()
            shot.jpeg?.let { dirs.gate.replace(ws, "$DIR/$id.jpg", it) }
            dirs.gate.replace(ws, "$DIR/$id.json", UserScreenJson.encode(screen).toByteArray())
            screen
        }
    }

    suspend fun screen(conversationId: String, id: String): UserScreen? = read(conversationId, "$id.json")?.let { UserScreenJson.decode(String(it)) }

    suspend fun image(conversationId: String, id: String): ByteArray? = read(conversationId, "$id.jpg")

    /** このダイアログの画面は、番号に従ってタスクに割り当てられ、取得されます。 */
    fun source(conversationId: String): UserScreenSource = object : UserScreenSource {
        override suspend fun screen(id: String) = this@UserScreenFiles.screen(conversationId, id)
        override suspend fun image(id: String) = this@UserScreenFiles.image(conversationId, id)
    }

    private suspend fun read(conversationId: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE.matches(name)) return@withContext null
        runCatching {
            dirs.gate.openRead(dirs.workspace(conversationId), "$DIR/$name").use { f ->
                if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
            }
        }.getOrNull()
    }

    private companion object {
        const val DIR = "screens"
        const val MAX_BYTES = 20L shl 20
        val SAFE = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|json)$")
    }
}
