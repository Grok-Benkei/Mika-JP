package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.NoteKind
import com.wangzan101.mika.core.skills.SkillNote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 各会話の小さな JSON ファイル。アプリのプライベート ディレクトリに配置されます。再起動後もまだ存在します。 */
class ConversationStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 書かれたダイアログ番号: 誰が書いたかに関係なく、ラウンドの保存、特定のラウンドの変更、およびメモリ プロンプトの削除時に 1 回送信されます (ダイアログ ページ、中断されたカプセル、時限タスク)。
     * 会話ページとサイドバーが適宜再読み込みされ、画面に表示されるのは保存されたコピーです。
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** が会話番号を削除しました: [delete] が 1 回送信されます ([changes] も送信されます)。それに応じて、一時停止カプセルは会話を終了したばかりのタスクを削除し、カードはそれを継続しなくなります。 */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * ユーザーが現在どの会話に参加しているかについては、[CurrentChat] を参照してください。 `current` ファイルはセッション ディレクトリに存在します (`.json` ではなく、[list] は入力されません)。
     * は、プロセスの再起動後も読み取ることができます。カプセルは、どのセッション ファイルが新しく開かれたかを知るために、セッション ファイルが書き込まれるのを待つ必要はありません。
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** ユーザーが現在どの会話に参加しているかを覚えておいてください。削除された会話は記憶できません。記入後、[current]を送信してください。 */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * はダイアログ リストで、最後に変更されたものが最初になります。 [ConversationSummary.title] はタイトル (ユーザーによって変更または作成) であり、そうでない場合は最初の文です。
     * [ConversationSummary.pinnedAt] がトップタイムです。 [ConversationSummary.page] はどのページのものですか。
     * [ConversationSummary.branchOf] はどの会話のどのラウンドから分離されます。内容のない会話は表示されません。
     */
    @Synchronized
    fun list(): List<ConversationSummary> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                val first = turns(id).firstOrNull() ?: return@mapNotNull null
                ConversationSummary(id, title(id) ?: first.userText, f.lastModified(), pinnedAt(id), pageOrigin(id), branchOrigin(id))
            }

    /** 会話に適したタイトル ([ConversationTitler] を参照)。そうでない場合、読み取れない場合は null になります。タイトルは、会話ファイルの隣の `.title` ファイルに配置され、会話ファイルの形式は変更されません。 */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * ダイアログを削除しました。削除後の「書き換え」（バックグラウンドで作成されたタイトル、タスク完了後のエンディングや説明）は書き戻されなくなります。
     * この番号を再度使用し、ここから削除するには、別のラウンドのデポジット ([append]) を追加します。途中でのみ記録されます。
     */
    private val deleted = mutableSetOf<String>()

    /**
     * は、会話に保存されているすべてのものを削除します。会話ファイル、タイトル、ピン、Web ソース、ブランチ ソースに加え、書きかけの一時ファイルや脇に移動された破損したファイルも削除されます。
     * はすべてを削除し、[changes] と [deletions] に一度送信します。
     * 会話型ワークスペース、スケジュールされたタスク、実行中のタスクはここでは管理されません。[ConversationDeleter] を参照してください。
     */
    @Synchronized
    fun delete(conversationId: String) {
        deleted += safe(conversationId)
        val main = file(conversationId).name
        dir.listFiles { f ->
            f.isFile && (f.name == main || f.name.startsWith("$main.") || f.name == titleFile(conversationId).name || f.name == pinFile(conversationId).name ||
                f.name == pageFile(conversationId).name || f.name == branchFile(conversationId).name)
        }
            .orEmpty().forEach { it.delete() }
        // は、ユーザーが現在見ているものを正確に削除します。それを指さなくなり、カプセルは最も近いものに戻ります。
        if (_current.value == CurrentChat.Open(safe(conversationId))) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * 会話のタイトルを書き留めます。すでにタイトル（ユーザーが指定または変更）がある場合、それは変更されません（タイトルは 1 回だけ指定されます）。書き込まれた場合は true を返します。
     * したがって、背景タイトルが遅れている場合、ユーザーが最初に変更した名前はブロックされません。
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** ユーザーが会話の名前を変更します。タイトルがあってもなくても、[title] に置き換えられます。空の場合は変更されません。書き込まれた場合は true を返します。 */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 会話がトップに固定された時刻。固定されていない場合、または読み取ることができない場合は、null になります。これを会話ファイルの隣の `.pin` ファイルに配置します。 */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** ピンを先頭に置くか ([pinned] は true、時間は [at] であることに注意)、ピンをキャンセルします。削除された会話は変更されずに残ります。 trueを返すように変更しました。 */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 会話が開始されたページ (ユーザーはブラウザ ページでミカに質問します)。そうでない場合、読み取れない場合は null になります。
     * は、会話ファイル (JSON: URL、ホスト、タイトル) の隣の `.page` ファイルに配置され、会話ファイルの形式は変更されません。古い会話にはこのファイルがありません。
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** 会話がどのページから始まるかをメモします (同じページで次の質問をするときは、最新のページに変更します)。削除された会話は変更されずに残ります。書き込まれた場合は true を返します。 */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        if (safe(conversationId) in deleted) return false
        dir.mkdirs()
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { pageFile(conversationId).writeText(json.toString()) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * [sourceId] のターンを新しい会話 [targetId] にコピーし、[throughTurn] (0 から始まり、両端を含む) にコピーします。
     * ソースが存在しない場合、ソース ファイルが破損している場合、[throughTurn] が範囲外である場合、またはターゲットにすでに会話ファイルがある場合は、false を返し、何も書き込みません。
     * [title] が空白でない場合は、新しい会話のタイトルとして書き込まれます。 [origin] が空白でない場合は、`<targetId>.branch` と書き込まれます。
     * タイトル、Web ページのソース、およびブランチのソースはすべてサイド ファイルです。書き込めなくても、コピーされた会話自体には影響しません。
     */
    @Synchronized
    fun branch(
        sourceId: String,
        targetId: String,
        throughTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, throughTurn, includeTurn = true, title, origin)

    /**
     * は、[beforeTurn] の前のラウンド [sourceId] (このラウンドを除く) を、「ユーザー メッセージからの再質問」の新しい会話 [targetId] にコピーします。
     * [beforeTurn] が 0 の場合、新しいダイアログにはまだ順番がありません (新しく開かれたダイアログと同じで、サイド列リストには入りません)。このダイアログの最初のラウンドは、最初の文が保存されるまでカウントされません。
     * の残りの条件は、[branch] と同じです。
     */
    @Synchronized
    fun branchBefore(
        sourceId: String,
        targetId: String,
        beforeTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, beforeTurn, includeTurn = false, title, origin)

    /** [branch] と [branchBefore] の共有: [sourceId] の最初の [turn] ラウンド (このラウンドは [includeTurn] に接続されています) をコピーし、タイトル、Web ページのソース、およびブランチ ソースを書き込みます。 */
    private fun copyBranch(sourceId: String, targetId: String, turn: Int, includeTurn: Boolean, title: String?, origin: BranchOrigin?): Boolean {
        val source = file(sourceId)
        if (!source.isFile) return false
        val records = read(source) ?: return false
        if (turn !in records.indices) return false
        if (file(targetId).exists()) return false
        deleted -= safe(targetId)
        write(targetId, records.take(if (includeTurn) turn + 1 else turn))
        title?.let { writeTitle(targetId, it) }
        pageOrigin(sourceId)?.let { setPageOrigin(targetId, it) }
        origin?.let { writeBranchOrigin(targetId, it) }
        return true
    }

    /** 分岐元: どのラウンドの会話から分岐したか。そうでない場合、読み取れない場合は null になります。 `<id>.branch`に置きます。 */
    @Synchronized
    fun branchOrigin(conversationId: String): BranchOrigin? = runCatching {
        val f = branchFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val parentId = str(o, "parent")?.takeIf(String::isNotEmpty) ?: return@runCatching null
        val parentTurn = (o["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        val afterTurn = (o["after"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return@runCatching null
        val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@runCatching null
        BranchOrigin(parentId, parentTurn, afterTurn, at)
    }.getOrNull()

    /** ブランチソースを書き込みます。最初に一時ファイルを書き込んでから、名前を変更します。書き込み途中で強制終了するとファイルの半分も残りません。 */
    private fun writeBranchOrigin(conversationId: String, origin: BranchOrigin) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("parent", origin.parentId); put("turn", origin.parentTurn); put("after", origin.afterTurn); put("at", origin.at)
        }
        val f = branchFile(conversationId)
        val tmp = File(dir, "${f.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    /** ファイルが破損すると、新しいタスクがブロックされないように、空のダイアログとして使用されます。 */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** 各ラウンドと、このラウンド用にエクスポートされたファイル (ファイル カード)。 */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    /** を読み取れない場合 (JSON 配列ではない場合)、null を返します。 */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(f.readText()) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                keptPage = field("keptPage"),
                keptPageClosed = field("keptPageClosed"),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs") ?: return null, field("uri") ?: return null)
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        deleted -= safe(conversationId)
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // 破損したファイルを脇に移動し、新しいコンテンツで上書きしないでください。
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        write(conversationId, prior.orEmpty() + (turn to files))
    }

    /** ラウンド番号を [runId] に変更します (エンディング、メモリー、ジャッジメントのスキルヒントはミッション終了後に追加されます);見つからない場合は、動かないでください。 */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** メモリが削除されるか、すべてクリアされる場合: これらのエントリを指すプロンプトと「使用済み」レコードを削除します。 [ids] が null の場合は、すべて削除します。 */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                // はタイプと番号のみを保存します。メモリの内容は暗号化されたメモリ ファイルにのみ存在し、インターフェイスはすぐに取得できます。
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(it.interruptedReplies.map(::JsonPrimitive)))
                if (it.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(it.finishedReplies.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                it.keptPage?.let { h -> put("keptPage", h) }; it.keptPageClosed?.let { r -> put("keptPageClosed", r) }
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject { put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri) }
                }))
            }
        })
        // 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了すると、ファイルの半分も残りません。
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** アプリ プロセスによって共有されるもの (読み取りと書き込みはロック内でシリアル化されます)。 */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.wangzan101.mika.MikaApp).conversations

        /** `current` 「まだ話されていない新しい会話を開く」ことを意味するファイル内の値 (会話番号は `-`、`_` の英数字のみであり、衝突しません)。 */
        private const val CURRENT_FRESH = "fresh"
    }

    // ID は newConversation から取得されるか、外部から渡されます。ディレクトリ外のパスのスペルアウトを避けるために、安全な文字のみが残されます。
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")
    private fun branchFile(id: String) = File(dir, "${safe(id)}.branch")

    /** ユーザーが現在参加している会話。[CurrentChat] を参照。 `.json` でない場合は、[list] を入力しないでください。 */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinnedAt: Long? = null,
    val page: PageOrigin? = null,
    val branchOf: BranchOrigin? = null,
)

/** 分岐ソース: [parentId] の [parentTurn] ラウンド (0 から開始) から分岐。 [afterTurn] が false の場合、このラウンドの前にコピーされます (第 2 段階のユーザー メッセージから再質問されます)。 */
data class BranchOrigin(val parentId: String, val parentTurn: Int, val afterTurn: Boolean, val at: Long)

/**
 * ユーザーが現在参加している会話: アプリで新しく開かれてまだ話していない会話は [Fresh] で、現在表示されている会話は [Open] です。レコードはなく (Mika の古いバージョンにはありません)、null です。
 * これに基づいて、フローティング カプセルは、「どの会話ファイルが最後に書き込まれたか」に基づいて推測するのではなく、接続されるべきものを取得します。新しく開かれた会話にはまだファイルがなく、推測されたものが前の会話になります。
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** 会話が開始されるページ: URL、ホスト名、ページ タイトル (そうでない場合は空の文字列)。 */
data class PageOrigin(val url: String, val host: String, val title: String = "")
