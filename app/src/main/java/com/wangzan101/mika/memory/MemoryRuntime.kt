package com.wangzan101.mika.memory

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.skills.PersonalSkillFiles
import com.wangzan101.mika.knowledge.AppKnowledgeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** 実行完了後の結果カードの下のプロンプト。実行番号を押すと、その質問と回答にリンクします。 */
data class RunNotes(
    val conversationId: String,
    val runId: String,
    val memoryNotes: List<MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<SkillNote> = emptyList(),
)

/** 「メモリとスキル」のリクエストを開きます (結果カードの「見る」をクリック): [draftId] は、表示したいスキルのドラフトです。 */
data class MemoryOpen(val draftId: String? = null)

/**
 * メモリストレージ、個人スキル、スイッチ、結果カードプロンプト。アプリによって作成され、[Owner] によって提供される: 複数の並列タスクが同じ [MemoryStore] を共有します。
 * は、独自のロックによってシリアルに書き込まれます。初めてアクセスするときにディスクが読み取られて復号化されるため、メインスレッドで調整しないでください。
 */
class MemoryRuntime(context: Context) {
    private val app = context.applicationContext

    private var file: EncryptedMemoryFile? = null
    private var store: MemoryStore? = null
    private var skills: PersonalSkillFiles? = null
    private var settings: MemorySettings? = null
    private var profile: ProfileStore? = null
    private var appKnowledge: AppKnowledgeFiles? = null

    /** タスク完了後の学習はここで実行されます。サービスが終了してもキャンセルされません。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notes = MutableStateFlow<Map<String, RunNotes>>(emptyMap())
    /** 実行番号 → プロンプト;それに応じて、インターフェイスが結果カードの下に表示されます。 */
    val notes: StateFlow<Map<String, RunNotes>> = _notes.asStateFlow()

    private val _open = MutableStateFlow<MemoryOpen?>(null)
    val openRequest: StateFlow<MemoryOpen?> = _open.asStateFlow()

    fun requestOpen(o: MemoryOpen?) { _open.value = o }

    fun publish(n: RunNotes) = _notes.update { it + (n.runId to n) }

    /**
     * メモリ エントリ、結果カードのプロンプト テキストをフェッチします。初めてアクセスするとき、ディスクはバックグラウンドで読み取られて復号化されますが、読み込む前はディスクは空です。
     * の後にストレージの変更が続きます。
     */
    fun items(): StateFlow<List<MemoryItem>> {
        val out = MutableStateFlow<List<MemoryItem>>(emptyList())
        scope.launch(Dispatchers.IO) { runCatching { store() }.getOrNull()?.items?.collect { out.value = it } }
        return out.asStateFlow()
    }

    @Synchronized fun file(): EncryptedMemoryFile =
        file ?: EncryptedMemoryFile(app.filesDir, KeystoreKeyCipher(KEY_ALIAS), System::currentTimeMillis).also { file = it }

    @Synchronized fun store(): MemoryStore =
        store ?: MemoryStore(file(), System::currentTimeMillis, { UUID.randomUUID().toString() }).also { store = it }

    @Synchronized fun skills(): PersonalSkillFiles =
        skills ?: PersonalSkillFiles(app.filesDir).also { skills = it }

    @Synchronized fun profile(): ProfileStore =
        profile ?: ProfileStore(FileProfileBackend(File(app.filesDir, "memory/profile.bin"), KeystoreKeyCipher(KEY_ALIAS)), ids = { UUID.randomUUID().toString() }).also { profile = it }

    @Synchronized fun appKnowledge(): AppKnowledgeFiles =
        appKnowledge ?: AppKnowledgeFiles(app.filesDir).also { appKnowledge = it }

    @Synchronized fun settings(): MemorySettings =
        settings ?: MemorySettings(File(app.filesDir, "memory_settings.json")).also { settings = it }

    /** 「すべてクリア」: メモリ、保存された不良ファイル、個人スキル、下書きがすべて削除され、メモリ キーが再構築されます。会話中の記憶プロンプトも削除されます。 */
    @Synchronized fun wipeAll() {
        store().clearAll()
        file().wipe()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        File(app.filesDir, "skills").deleteRecursively()
        File(app.filesDir, "knowledge/app-drafts").deleteRecursively()
        stripMemory(null)
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(skillNotes = emptyList()) } }
    }

    /** はこれらの思い出を削除しました ([ids] は null = すべて)。これらは結果カードと会話ファイルで言及されなくなりました。 */
    fun stripMemory(ids: Set<String>?) {
        fun keep(id: String) = ids != null && id !in ids
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(memoryNotes = n.memoryNotes.filter { keep(it.memoryId) }, usedMemory = n.usedMemory.filter(::keep)) } }
        runCatching { ConversationStore.shared(app).stripMemory(ids) }
    }

    /** はプロセス中のアプリケーションを示します。 */
    interface Owner {
        val memory: MemoryRuntime
    }

    companion object {
        const val KEY_ALIAS = "mika-memory"

        fun of(context: Context): MemoryRuntime = (context.applicationContext as? Owner)?.memory
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement MemoryRuntime.Owner to give the memory runtime")
    }
}
