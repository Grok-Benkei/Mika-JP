package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.security.MessageDigest
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * サンドボックスのディレクトリ: `files/sandbox/` の下のルート ファイル システム、ワークスペース、スキル、/proc アバター。 PRoot の一時ディレクトリはキャッシュ内にあります。
 * 実行可能ファイルは、[nativeDir] にのみあります (Android 10 以降、データ ディレクトリ内のファイルは実行できません)。
 */
class SandboxDirs internal constructor(
    val root: File, val tmp: File, val nativeDir: File,
    /** は、ホスト側でこれらのディレクトリ (サンドボックスによってアクセス可能) の読み取りと書き込みに使用できます。携帯電話では、これは [AndroidFdSys.gate]、JVM テスト転送パスのコピーです。 */
    val gate: WorkspaceFileGate,
    /** このツリーに書き込む前に、まずその許可を取得する必要があります。携帯電話では、処理中のコピー ([SandboxOwner]) です。テストするときはコピーを自分に渡してください。デフォルトなし: プライベート コピーを他の人にロックすることはできません。 */
    val tree: SandboxTree,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "sandbox"), File(context.cacheDir, "proot-tmp"), File(context.applicationInfo.nativeLibraryDir),
        AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree,
    )

    val rootfs: File get() = File(root, "rootfs")
    val work: File get() = File(root, "work")
    val skills: File get() = File(root, "skills")
    val procStandIns: File get() = File(root, "proc")

    /** 1 つの会話と 1 つのワークスペース: 単純名の ID は c-<ID> で、残りは h-<ハッシュ> (ID に他のパスを指定することは許可されず、2 つのカテゴリは衝突しません)。 サンドボックス内の */
    fun workspace(conversationId: String): File = File(work, dirName(conversationId))

    fun lib(name: String): File = File(nativeDir, name)

    /**
     * 削除および置換コマンドによって書き込まれたディレクトリ全体 (ワークスペース、実行環境) はここを通過します。 [tree] の独占ライセンスを使用して IO スレッドで実行します。
     * は、実行中のコマンドやその他の書き込みが完了するまで待ってから開始します。新しいコマンドとインポートも、削除と誰かによる書き込みを同時に避けるために、完了する前に待機します。
     * コスト: 大きなディレクトリを削除するのにかかる時間、およびすべての会話のサンドボックス コマンドを待つのにかかる時間。許可を待っている間に状況が変化した可能性があります (たとえば、ダイアログに別のタスクがあるなど)。
     * は[block]でライセンス取得後に条件を確認する必要があります。
     */
    suspend fun <T> exclusiveWrite(block: suspend () -> T): T = tree.exclusive { withContext(Dispatchers.IO) { block() } }

    /** は、会話 [conversationId] のワークスペース全体を削除します (ハンドルによって削除します。リンクをたどらないでください。[exclusiveWrite] を参照)。 */
    suspend fun deleteWorkspace(conversationId: String) = deleteWorkDir(workspace(conversationId).name)

    /** は、`work/` の下の [dirName] という名前のワークスペースを削除します。 */
    suspend fun deleteWorkDir(dirName: String) = exclusiveWrite { gate.deleteTree(work, dirName) }

    /**
     * バインドする前に、バインドするすべてのディレクトリを作成します。ソース ディレクトリが存在しない場合、PRoot は標準エラーに警告を書き込みます。
     * と標準エラーはコマンド出力にマージされ、モデルはそれを確認します。スキルディレクトリが同期されていない場合、空のディレクトリとなります。
     */
    fun ensureBindSources(workspace: File) {
        for (d in listOf(workspace, skills, tmp)) d.mkdirs()
    }

    private fun dirName(id: String): String =
        if (SAFE_ID.matches(id)) "c-$id"
        else "h-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
