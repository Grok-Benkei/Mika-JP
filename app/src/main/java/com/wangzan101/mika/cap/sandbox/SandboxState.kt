package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class InstallStep { Check, Extract, Configure, Skills, Done }

/**
 * サンドボックスのステータス。インストールの進行状況は `files/sandbox/state.json` に記録されます (リセット中にディレクトリ全体が削除されます)。
 * ネットワーク スイッチはユーザー設定であり、`files/sandbox-settings.json` に記録され、リセット後も保持されます。
 *
 * は、レコードに「Complete to Done」があり、ルート ファイル システムに /bin/sh がある場合にのみ準備完了とみなされます。インストールの途中 (インストール中に強制終了されるプロセスを含む) は常に壊れています。
 * 次回は記録されたステップから続行します。
 */
class SandboxState internal constructor(
    private val dirs: SandboxDirs,
    private val settingsFile: File,
    private val abis: () -> List<String>,
) {
    constructor(context: Context) : this(
        SandboxDirs(context), File(context.filesDir, "sandbox-settings.json"), { Build.SUPPORTED_ABIS.toList() },
    )

    private val recordFile get() = File(dirs.root, "state.json")
    private var installing: SandboxStatus.Installing? = null
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** 「サンドボックス ネットワークを許可する」はデフォルトではオフになっています。 */
    var networkAllowed: Boolean
        get() = settings()["networkAllowed"]?.jsonPrimitive?.booleanOrNull ?: false
        set(value) { putSetting("networkAllowed", JsonPrimitive(value)); refresh() }

    /** ネットワーク切断フィルターをインストールできない理由 (スターターセルフテストから取得)。インストールはブロックされませんが、ネットワークが切断されるとコマンドは拒否されます。 */
    val isolationIssue: String? get() = record()["isolationIssue"]?.jsonPrimitive?.contentOrNull

    val installedVersion: String? get() = record()["version"]?.jsonPrimitive?.contentOrNull

    @Synchronized
    fun completedStep(): InstallStep? =
        record()["completedStep"]?.jsonPrimitive?.contentOrNull?.let { n -> InstallStep.entries.firstOrNull { it.name == n } }

    /** 「[s] まで完了」を書き留め、最後の失敗理由をクリアします。完了したら、バージョンと完了時間を書き込みます。 */
    @Synchronized
    fun markStep(s: InstallStep) = markStep(s, null)

    @Synchronized
    internal fun markStep(s: InstallStep, version: String?) {
        val r = record().toMutableMap()
        r["completedStep"] = JsonPrimitive(s.name)
        r.remove("broken")
        if (s == InstallStep.Done) {
            version?.let { r["version"] = JsonPrimitive(it) }
            r["doneAt"] = JsonPrimitive(System.currentTimeMillis())
            installing = null
        }
        writeJson(recordFile, JsonObject(r))
        refresh()
    }

    @Synchronized
    internal fun installing(step: String, percent: Int) {
        installing = SandboxStatus.Installing(percent, step)
        refresh()
    }

    @Synchronized
    internal fun markBroken(reason: String) {
        installing = null
        writeJson(recordFile, JsonObject(record() + ("broken" to JsonPrimitive(reason))))
        refresh()
    }

    @Synchronized
    internal fun recordIsolation(issue: String?) {
        val r = record().toMutableMap()
        if (issue == null) r.remove("isolationIssue") else r["isolationIssue"] = JsonPrimitive(issue)
        writeJson(recordFile, JsonObject(r))
    }

    /** リセットが開始されます。まずインストール レコードを削除し、「リセット」を表示します。途中で削除すると準備完了と表示されなくなります。 */
    @Synchronized
    internal fun resetting() {
        Files.deleteIfExists(recordFile.toPath())
        installing(tr(R.string.sandbox_resetting), 0)
    }

    /** インストールまたはリセットが完了しました (成功または失敗に関係なく): 進行中のステータスをキャンセルし、記録に従って再計算します。 */
    @Synchronized
    internal fun cleared() {
        installing = null
        refresh()
    }

    @Synchronized
    fun refresh() { _status.value = read() }

    private fun read(): SandboxStatus {
        if ("arm64-v8a" !in abis()) return SandboxStatus.Unsupported(tr(R.string.install_unsupported))
        installing?.let { return it }
        val r = record()
        val step = r["completedStep"]?.jsonPrimitive?.contentOrNull
        val broken = r["broken"]?.jsonPrimitive?.contentOrNull
        // Alpine の /bin/sh は /bin/busybox への絶対リンクであり、ホスト上では解析できません。リンク自体を見てください。
        val hasShell = Files.exists(File(dirs.rootfs, "bin/sh").toPath(), LinkOption.NOFOLLOW_LINKS)
        return when {
            step == InstallStep.Done.name && hasShell -> SandboxStatus.Ready(network = networkAllowed)
            step == InstallStep.Done.name -> SandboxStatus.Broken(tr(R.string.sandbox_incomplete))
            broken != null -> SandboxStatus.Broken(broken)
            step != null -> SandboxStatus.Broken(tr(R.string.sandbox_not_finished))
            else -> SandboxStatus.NotInstalled
        }
    }

    private fun record(): Map<String, JsonElement> = readJson(recordFile)
    private fun settings(): Map<String, JsonElement> = readJson(settingsFile)

    @Synchronized
    private fun putSetting(key: String, value: JsonPrimitive) = writeJson(settingsFile, JsonObject(settings() + (key to value)))

    companion object {
        /** プロセス内のコピー (アプリによって提供される): インストール サービスの更新のステータス。設定ページとエージェントには同じコピーが表示されます。 */
        fun get(context: Context): SandboxState = SandboxOwner.of(context).sandboxState

        private fun readJson(f: File): Map<String, JsonElement> =
            runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: emptyMap()

        /** 最初に一時ファイルを書き込んでから、名前を変更します。書き込みの途中で強制終了しても、不良ファイルは残りません。 */
        private fun writeJson(f: File, o: JsonObject) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            // は、ディスクに配置された後に名前が変更されます。電源が切断されるか切断されると、古いレコードまたは新しいレコードになります。
            java.io.FileOutputStream(tmp).use { out -> out.write(o.toString().toByteArray()); out.fd.sync() }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
