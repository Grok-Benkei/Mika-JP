package com.wangzan101.mika.task

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import com.wangzan101.mika.cap.phone.shell.SharedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** 永続的な「元の値」のセット。値が null は、この項目が元々存在しなかったことを意味します。 [load] はレコードがない場合は null を返します。 */
interface MarkerStore {
    fun load(): Map<String, String?>?
    fun save(values: Map<String, String?>)
    fun clear()
}

/** は SharedPreferences に存在し、同期的に送信されます。まずダウンロードしてから、システム ステータスを変更する必要があります。 */
class PrefsMarker(private val prefs: SharedPreferences, private val name: String) : MarkerStore {
    override fun load(): Map<String, String?>? {
        val keys = prefs.getStringSet("$name.keys", null) ?: return null
        return keys.associateWith { prefs.getString("$name.v.$it", null) }
    }

    override fun save(values: Map<String, String?>) {
        val e = prefs.edit()
        clearInto(e)
        e.putStringSet("$name.keys", values.keys.toSet())
        values.forEach { (k, v) -> e.putString("$name.v.$k", v) }
        e.commit()
    }

    override fun clear() {
        prefs.edit().also { clearInto(it) }.commit()
    }

    private fun clearInto(e: SharedPreferences.Editor) {
        prefs.all.keys.filter { it.startsWith("$name.") }.forEach { e.remove(it) }
    }
}

/** ミュートに必要なシェル機能。パラメータはミュート キーです ([AppAudioKey] を参照)。元のコピーはパッケージ名で、ユーザー内のもう一方のコピーにはユーザー番号が含まれます。 */
interface AudioPort {
    /** 現在の PLAY_AUDIO モード。読み込まれなかった場合は null を返します。 */
    fun mode(packageName: String): String?
    fun mute(packageName: String): Boolean
    fun setMode(packageName: String, mode: String): Boolean
}

class ShellAudio(private val shell: IMikaShell) : AudioPort {
    override fun mode(packageName: String): String? = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return null
        if (user == null) shell.getAudioMode(pkg) else shell.getAudioModeInUser(pkg, user)
    }.getOrNull()

    override fun mute(packageName: String): Boolean = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return false
        if (user == null) shell.setAudioMuted(pkg, true) else shell.setAudioMutedInUser(pkg, user, true)
    }.getOrDefault(false)

    override fun setMode(packageName: String, mode: String): Boolean = runCatching {
        // フォーマットされていないキーは回復されたものとして扱われます。残っていても回復できません。
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return true
        if (user == null) shell.setAudioMode(pkg, mode) else shell.setAudioModeInUser(pkg, user, mode)
    }.getOrDefault(false)
}

/**
 * タスク中の対象アプリのサウンドをオフにします。ミュートする前に、元のパターンを永続レコードに書き込みます。シェル サービスは、Mika プロセスとともに終了します。
 * メモリ内の元の値は失われ、次回起動時にこのレコードのみが復元されます。
 */
class AudioMute(private val port: AudioPort, private val store: MarkerStore) {
    @Synchronized
    fun mute(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) {
            val prior = port.mode(packageName) ?: return false
            store.save(saved + (packageName to prior))
        }
        return port.mute(packageName)
    }

    /** は 1 つのアプリのみを復元します (ユーザーが引き継ぐときに音を鳴らします)。復元できない場合は記録に残ります。ミュートされていないものは復元されたとみなされます。 */
    @Synchronized
    fun restore(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) return true
        if (!port.setMode(packageName, saved[packageName] ?: "default")) return false
        val left = saved - packageName
        if (left.isEmpty()) store.clear() else store.save(left)
        return true
    }

    /** レコード内のすべてのアプリを復元します。復元できないものは記録に残され、次回再試行されます。 */
    @Synchronized
    fun restoreAll(): Boolean {
        val saved = store.load() ?: return true
        val left = saved.filterNot { (pkg, mode) -> port.setMode(pkg, mode ?: "default") }
        if (left.isEmpty()) store.clear() else store.save(left)
        return left.isEmpty()
    }
}

/**
 * ミカは、クリップボードの貼り付け中にユーザーの元のクリップボード テキスト (およびミカによって入力されたテキスト) の暗号化を使用します。シェル プロセスがミカとともに強制終了されると、
 * メモリ内の元のクリップボードが失われており、次回起動時にはこれに依存してユーザーのコンテンツを返すことしかできません。 [cipher]を使用して暗号化してテキストとして保存し、復元後に削除します。
 */
class ClipBackup(private val read: () -> String?, private val write: (String?) -> Unit, private val cipher: KeyCipher) {
    fun save(original: String, ours: String) {
        val plain = buildJsonObject { put("original", original); put("ours", ours) }.toString().toByteArray()
        write(Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    /** (原文、Mika が入力したテキスト);左下がない、またはロック解除できない(キーが変更されている)場合はnull。 */
    fun load(): Pair<String, String>? = runCatching {
        val data = read() ?: return null
        val o = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(data)))).jsonObject
        o.getValue("original").jsonPrimitive.content to o.getValue("ours").jsonPrimitive.content
    }.getOrNull()

    fun clear() = write(null)
}

/**
 * ミカが異常終了した後に残ったシステム変更（アプリのミュート、貼り付け中にクリップボードが一時的に置き換えられた）は、次回起動時に復元されます。
 * は、各タスクのコピーを 1 つサイレントに記録します ([TaskRun.key] を押します)。複数のタスクを同時に実行すると、各パッケージが記録されます。
 * タスク中の変更はタスク自体によって完了し、復元では誰も完了していない変更のみが処理されます。まだ実行中のタスク ([live]) のレコードは移動されません。
 * は、タスクの実行中にクリップボードを復元しません (その時点でクリップボード経由で貼り付けられる可能性があります)。
 * はアプリによって作成され、[Owner] によって与えられます ([live] はタスク間で共有される必要があります)。アシスタントを待つときは、プロセス内で共有されている [sharedShell] を使用します。
 */
class TaskRecovery(context: Context, private val sharedShell: SharedShell) {
    private val app = context.applicationContext
    private val clipCipher by lazy { KeystoreKeyCipher(CLIP_KEY_ALIAS) }

    /** リカバリは、タスクの開始時に登録と共有され、2 つが重複しないようにします。 */
    val lock = Mutex()

    /** はまだタスクを実行中です。プロセスが強制終了された後は空になり、すべてのレコードが復元されます。 */
    private val live = ConcurrentHashMap.newKeySet<String>()

    fun audioMute(shell: IMikaShell, runKey: String) = AudioMute(ShellAudio(shell), marker(runKey))

    private fun marker(runKey: String) = PrefsMarker(prefs(), "${MUTED}_$runKey")

    /** 既存のすべてのサイレント レコード名 (古いバージョンを含む)。 */
    private fun markerNames(): List<String> =
        prefs().all.keys.filter { it.startsWith(MUTED) && it.endsWith(".keys") }.map { it.removeSuffix(".keys") }

    private fun liveNames() = live.mapTo(HashSet()) { "${MUTED}_$it" }

    fun pending(): Boolean =
        markerNames().any { it !in liveNames() } || (live.isEmpty() && prefs().getBoolean(CLIP_HELD, false))

    /** タスクが開始されます。まず、誰も完了していないレガシーを復元し、次にそれを実行中として登録します。 */
    suspend fun beginRun(shell: IMikaShell, runKey: String) = lock.withLock {
        withContext(Dispatchers.IO) { recoverWith(shell) }
        live += runKey
    }

    /** は、タスクの完了後に呼び出されます (独自のミュートが復元されました)。復元されなかったものは記録に残され、次回復元されます。 */
    fun endRun(runKey: String) {
        live -= runKey
    }

    /** 貼り付け期間中、クリップボードはミカに属します。プロセスはこの期間中に終了し、次回の起動時にシェルがユーザーの元のクリップボードを返すことができるようになります。 */
    fun markClipHeld(held: Boolean) {
        prefs().edit().putBoolean(CLIP_HELD, held).apply { if (!held) remove(CLIP_BACKUP) }.commit()
    }

    private fun clipBackup() = ClipBackup(
        { prefs().getString(CLIP_BACKUP, null) },
        { v -> prefs().edit().apply { if (v == null) remove(CLIP_BACKUP) else putString(CLIP_BACKUP, v) }.commit() },
        clipCipher,
    )

    /** は、クリップボードが正常に変更された後も最下位を維持します。暗号化が失敗した場合は保持されません (プロセスが強制終了された後に復元できないだけです)。 */
    fun saveClip(original: String, ours: String) {
        runCatching { clipBackup().save(original, ours) }.onFailure { Log.w(TAG, "clipboard backup not saved", it) }
    }

    /** がすでに [lock] を保持している場合に呼び出されます。 */
    fun recoverWith(shell: IMikaShell) {
        val records = markerNames().associateWith { PrefsMarker(prefs(), it) }
        if (!restoreRuns(records, ShellAudio(shell), liveNames())) Log.w(TAG, "muted apps not restored yet")
        if (live.isNotEmpty()) return
        // は、シェル プロセスがまだ存在している間に、記憶している元のクリップボードを復元します (ユーザーがクリップボードを置き換えても移動しません)。
        // シェルも強制終了されている場合は、[暗号化] を押して下部を残して復元します。どちらの手順でも、クリップボードがまだミカに属している場合にのみ、クリップボードを変更します。
        val inShell = runCatching { shell.restoreClip() }.isSuccess
        val fromBackup = restoreClip(clipBackup()) { o, u -> shell.restoreClipText(o, u) }
        if (inShell && fromBackup) markClipHeld(false)
    }

    /**
     * 起動時に呼び出されます: 変更が残っている場合は、組み込みアシスタントが接続されて復元されるまで待ちます。そうでない場合は、次回までそのままにしておきます。
     * は、新しいタスクの待機を避けるために、アシスタントを待機しているときにロックを占有しません。ロックを取得したら、再度ロックが残っているか確認してください。
     */
    suspend fun recover() {
        if (!pending()) return
        val connection = sharedShell.acquire()
        try {
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { connection.shell.filterNotNull().first() } ?: return
            lock.withLock { if (pending()) recoverWith(shell) }
        } finally {
            sharedShell.release()
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** はプロセス中のアプリケーションを示します。 */
    interface Owner {
        val taskRecovery: TaskRecovery
    }

    companion object {
        private const val TAG = "MikaTask"
        private const val PREFS = "task_recovery"
        private const val SHELL_WAIT_MS = 60_000L
        private const val CLIP_HELD = "clip_held"
        private const val CLIP_BACKUP = "clip_backup"
        private const val CLIP_KEY_ALIAS = "mika-clip-backup"
        /** 各タスクのミュート レコード名は muted_<タスク キー> です。古いバージョンのコピーはミュートという名前で 1 つだけあり、まだ復元できます。 */
        private const val MUTED = "muted"

        fun of(context: Context): TaskRecovery = (context.applicationContext as? Owner)?.taskRecovery
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement TaskRecovery.Owner to give the task recovery")

        /** は、[live] に属さない [records] (レコード名 → レコード) 内のすべてのミュートを復元します。すべてが復元され、true が返されます。 */
        fun restoreRuns(records: Map<String, MarkerStore>, port: AudioPort, live: Set<String>): Boolean =
            records.filterKeys { it !in live }.values.map { AudioMute(port, it).restoreAll() }.all { it }

        /** 保持ボタンを押して、ユーザーの元のクリップボードを返します ([restore] は、クリップボードがまだミカのものである場合にのみクリップボードを変更します)。復元された場合、または保持ボタンがない場合は true を返し、保持ボタンを削除します。 */
        fun restoreClip(backup: ClipBackup, restore: (original: String, ours: String) -> Unit): Boolean {
            val (original, ours) = backup.load() ?: return true.also { backup.clear() }
            return runCatching { restore(original, ours) }.onSuccess { backup.clear() }.isSuccess
        }
    }
}
