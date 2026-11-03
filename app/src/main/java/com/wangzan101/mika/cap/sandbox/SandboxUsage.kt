package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ConversationSummary
import java.nio.file.Files

/**
 * 会話が占めるワークスペースの量: [dirName] は、`work/` の下のディレクトリ名です。 [conversationId] および [title] は、それが属する会話です
 * (会話が削除されており認識できない場合は null)。 [busy] が true の場合、この会話にはタスクが実行中またはキューに入れられており、クリアできません。
 */
data class WorkspaceUsage(val dirName: String, val conversationId: String?, val title: String?, val bytes: Long, val busy: Boolean)

/** ファイルとコンピューティング環境によって占有されるスペース: 各ダイアログの実行環境、スキル、ワークスペース (大きい順)。 */
data class SandboxUsage(val runtime: Long, val skills: Long, val workspaces: List<WorkspaceUsage>) {
    val workspacesTotal: Long get() = workspaces.sumOf { it.bytes }
    val total: Long get() = runtime + skills + workspacesTotal
}

/**
 * 各会話のワークスペースを読み取り、クリアします。サイズはディレクトリ ツリー内の通常のファイルとして計算され、リンクは追跡されません (ワークスペース内のコマンドはリンクを作成できますが、どこを指しているかはカウントされません)。
 * がクリアされると、ファイル ゲートを介してハンドルによって削除され、リンクをたどらず、ツリー ロック ([SandboxDirs.exclusiveWrite]) の排他的アクセス許可を保持し、コマンドや他の書き込みとは相互に排他的になります。
 * タスクが実行中またはキューに登録されているとき ([active])、タスクで使用されているファイルが削除されないように、このダイアログは不明瞭です。動作環境とスキルはアプリに付属しているため、ここでは説明しません。
 */
class SandboxUsageReader(
    private val dirs: SandboxDirs,
    /** 保存された会話 (番号とタイトル)。 */
    private val conversations: () -> List<ConversationSummary>,
    /** 実行中またはキューに入れられたタスクとのダイアログ。 */
    private val active: () -> Collection<String>,
) {
    /** ディスクを読み取ります。ワークスペースには多数のファイルが存在する可能性があり、バックグラウンド スレッドで調整する必要があります。 */
    fun read(): SandboxUsage {
        val byDir = conversations().associateBy { dirs.workspace(it.id).name }
        val busy = active().map { dirs.workspace(it).name }.toSet()
        val workspaces = dirs.work.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }.map { dir ->
            val c = byDir[dir.name]
            WorkspaceUsage(dir.name, c?.id, c?.title, AndroidSandbox.treeBytes(dir.toPath()), dir.name in busy)
        }.sortedByDescending { it.bytes }
        return SandboxUsage(AndroidSandbox.treeBytes(dirs.rootfs.toPath()), AndroidSandbox.treeBytes(dirs.skills.toPath()), workspaces)
    }

    /** は、`work/` の下の [dirName] という名前のワークスペースをクリアします。そのダイアログには、実行時に不明瞭なタスクがあり、false を返します。 */
    suspend fun clear(dirName: String): Boolean = dirs.exclusiveWrite {
        // は、ツリーの許可を待機して IO に切り替えるときにハングする場合があります。取得後に確認し、待機する前にビジー スナップショットを使用しないでください。
        if (dirName in busyDirs()) return@exclusiveWrite false
        dirs.deleteWorkDir(dirName)
        true
    }

    /** 未使用のワークスペースをすべてクリアします。実行中のタスクがあるため、残りの数を返します。 */
    suspend fun clearAll(): Int = dirs.exclusiveWrite {
        val names = dirs.work.listFiles().orEmpty().map { it.name }
        var busy = 0
        for (name in names) {
            // 以前のワークスペースは長い間削除されていた可能性があります。削除する前に各ワークスペースをもう一度確認してください。
            if (name in busyDirs()) busy++
            else runCatching { dirs.deleteWorkDir(name) }
        }
        busy
    }

    private fun busyDirs(): Set<String> = active().map { dirs.workspace(it).name }.toSet()
}
