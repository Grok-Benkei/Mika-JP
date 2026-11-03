package com.wangzan101.mika.core.cap.sandbox

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import com.wangzan101.mika.core.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.serialization.json.JsonObject

/** サンドボックス コマンドがモバイル ツールを要求すると、このタスクのアクション実行エントリが返されます。 */
fun interface PhoneCommandHandler {
    suspend fun call(tool: String, args: JsonObject): JsonObject
}

sealed interface SandboxStatus {
    data class Unsupported(val reason: String) : SandboxStatus
    data object NotInstalled : SandboxStatus
    data class Installing(val percent: Int, val step: String) : SandboxStatus
    data class Ready(val network: Boolean) : SandboxStatus
    data class Broken(val reason: String) : SandboxStatus
}

/** エージェントは、Linux サンドボックスへの唯一の入り口です。パスはすべてサンドボックス内のパス ([SandboxPaths]) です。 */
interface SandboxPort {
    /** サンドボックス ディレクトリのツリー ロック: ワークスペースに書き込まれるものはすべて、その権限に書き込まれます。 */
    val tree: SandboxTree

    fun status(): SandboxStatus

    /** ユーザーがサンドボックスのインターネットへの接続を許可するかどうか (実行環境の準備が整っていないこともわかります)。デフォルトでは、準備完了状態で を押します。 */
    fun networkAllowed(): Boolean = (status() as? SandboxStatus.Ready)?.network == true

    /** [cwd]: `/workspace` の下の相対パス、null は `/workspace` 自体です。 */
    suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult

    /**
     * は常駐コマンド (stdio MCP サーバー) を開始し、stdin/stdout/stderr がプロセスに直接接続されます。デフォルトではサポートされていません。
     * 実装ではプロセスをサンドボックスにロックする必要があり、アプリのプライベート ディレクトリを取得させることはできません。
     */
    suspend fun openProcess(workspace: String, argv: List<String>, cwd: String? = null): SandboxProcess =
        throw SandboxError("sandbox_stdio_unsupported", "This device cannot run stdio MCP servers.", recoverable = true)

    /** 現在のタスクがモバイル ツールの入り口を提供する場合、コマンドはブリッジ経由で呼び出すことができます。古い実装では依然として [run] のみを実装できます。 */
    suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult =
        run(workspace, argv, cwd, timeoutMs, onProgress)

    /** サンドボックス パスに対応するホスト ファイル。範囲外 (外部を指すシンボリック リンクを含む) または [forWrite] がワークスペース内にない場合は、null が返されます。判定のみに使用され、[files] の読み書きが可能です。 */
    fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File?

    /** このプロセスを通じて、ホスト側のサンドボックス ディレクトリ内のファイルの読み取りと書き込みを行います。一度開いて、ハンドルで確認してから、このハンドルで読み取りと書き込みを行います。 */
    val files: WorkspaceFileGate

    /** 正規化されたサンドボックス パス → (ホスト ルート ディレクトリ、ルート下の相対パス)、[files] に渡されます。スキル ディレクトリは読み取り専用で、範囲外は null です。ワークスペース ディレクトリが存在しない場合は作成します。 */
    fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>?

    /** は携帯電話の「Download/Mika」に保存され、「Download/Mika/<名前>」に戻ります。 */
    suspend fun share(file: File, name: String): String

    fun freeBytes(): Long
    fun workspaceBytes(workspace: String): Long

    /**
     * は、完全な出力を /workspace/.mika/out-<n>.txt に保存し、サンドボックスのパスを返します。 [files] によって作成されました (.mika がリンクに置き換えられると、例外がスローされ、何も書き込まれません)。
     */
    fun saveOutput(workspace: String, text: String): String

    /** [skillPath]: `/opt/mika/skills` 下のスキル ディレクトリ (相対パス)。アプリに同梱されているリストを読むと、空にはなりません。 */
    fun scriptManifest(skillPath: String): List<ScriptEntry>

    /**
     * 動作環境が準備できていない場合（初めて解凍を開始する場合、またはまだ開始していない場合）：準備を開始し、[timeoutMs] まで待ちます。待ってから状態に戻ります。
     * サポートされていないデバイスです。準備が失敗した場合はできるだけ早く返却してください。デフォルトの実装は等しくありません。
     */
    suspend fun awaitReady(timeoutMs: Long): SandboxStatus = status()

    /** `/opt/mika/skills` を App に同梱されたものと同じ状態に合わせる: 余分、不足、変更されたものはすべて再同期する。失敗しても記録するだけで、例外は投げない。 */
    suspend fun prepare() {}

    /** [skillPath] 配下の [file] が同梱物とバイト単位で同一かどうか; 異なれば再同期してから再確認し、それでも異なれば false。 */
    suspend fun verifyScript(skillPath: String, file: String): Boolean = true
}

/** [SandboxPort.openProcess] が返す常駐プロセス; 閉じるとプロセスツリー全体が終了する。 */
interface SandboxProcess : Closeable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
}
