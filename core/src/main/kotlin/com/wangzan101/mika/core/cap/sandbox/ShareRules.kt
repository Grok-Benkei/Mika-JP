package com.wangzan101.mika.core.cap.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths

/** を「Download/Mika」に保存するためのルール; share_file ツールはサンドボックス内の `mika share` と共有されており、どちらのパスも同様に厳密です。 */
object ShareRules {
    const val MAX_BYTES = 100L shl 20

    /**
     * [path] はサンドボックス パス (相対パスは /workspace に基づきます) であり、これはワークスペース内の [SandboxPort.hostPath] によって解決される必要があります (リンク エスケープは null)。
     * は通常のファイル (リンクをたどらない) で、100 MB を超えません。 [name] は、デフォルトでファイル自体の名前を使用します。これは、制御文字を含まない単一のファイル名である必要があります。
     * はホスト ファイルと保存名を返します。ルールに違反する場合は、[SandboxError] をスローします。
     */
    fun check(sandbox: SandboxPort, workspace: String, path: String, name: String?): Pair<File, String> {
        val p = SandboxPaths.normalize(path)?.takeIf(SandboxPaths::isWritable)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val f = sandbox.hostPath(workspace, p, forWrite = true)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared; links that lead outside are refused.", recoverable = true)
        val given = name?.trim() ?: f.name
        if (!plainName(given)) throw SandboxError("invalid_arguments", "name: must be a plain file name without control characters", recoverable = true)
        val saveAs = fitName(given)
        if (refusedType(saveAs) || refusedType(f.name)) {
            throw SandboxError("share_refused_type", "App installation packages (.apk, .apks, .xapk, .aab) cannot be shared.", recoverable = false)
        }
        val hp = f.toPath()
        if (!Files.exists(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("file_not_found", "$p does not exist.", recoverable = true)
        if (!Files.isRegularFile(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("not_a_file", "$p is not a regular file.", recoverable = true)
        val size = Files.size(hp)
        if (size > MAX_BYTES) throw SandboxError("file_too_large", "$p is ${size shr 20} MB; at most 100 MB can be shared.", recoverable = true)
        return f to saveAs
    }

    /** 単一ファイル名: パス区切り文字、制御文字、フォーマット文字 (方向の書き換え、幅ゼロなど。表示される名前と実際の名前が一致しない可能性があります)、および行/段落区切り文字は含まれません。 */
    fun plainName(n: String): Boolean =
        n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != ".." &&
            n.codePoints().noneMatch(::hiddenChar)

    /** はエクスポートを許可しません。インストール パッケージは「ダウンロード」に配置され、ユーザーはワンクリックでサンドボックスで作成されたアプリをインストールできます。 */
    private val REFUSED_TYPES = setOf("apk", "apks", "xapk", "aab")

    fun refusedType(name: String): Boolean = name.substringAfterLast('.', "").trim().lowercase() in REFUSED_TYPES

    /** ファイル名の上限は 255 バイト (UTF-8) であり、システムが「(n)」のシリアル番号を追加できる余地が残されています。 */
    const val NAME_MAX_BYTES = 240

    /** は、UTF-8 バイト (およびオプションの単語数) で切り捨てられ、拡張子は保持され (拡張子自体が長すぎる場合は保持されません)、文字は分割されません。 */
    fun fitName(name: String, maxBytes: Int = NAME_MAX_BYTES, maxChars: Int = Int.MAX_VALUE): String {
        fun bytes(t: String) = t.toByteArray(Charsets.UTF_8).size
        fun chars(t: String) = t.codePointCount(0, t.length)
        if (bytes(name) <= maxBytes && chars(name) <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keepExt = ext.isNotEmpty() && bytes(ext) <= 32 && chars(ext) < maxChars
        val base = if (keepExt) name.substring(0, dot) else name
        val roomBytes = maxBytes - if (keepExt) bytes(ext) else 0
        val roomChars = if (maxChars == Int.MAX_VALUE) Int.MAX_VALUE else maxChars - if (keepExt) chars(ext) else 0
        val sb = StringBuilder()
        var used = 0
        var count = 0
        var i = 0
        while (i < base.length && count < roomChars) {
            val cp = base.codePointAt(i)
            val n = bytes(String(Character.toChars(cp)))
            if (used + n > roomBytes) break
            sb.appendCodePoint(cp)
            used += n
            count++
            i += Character.charCount(cp)
        }
        return sb.toString() + if (keepExt) ext else ""
    }

    /** 制御文字、フォーマット文字 (Cf) および U+2028/U+2029。 */
    fun hiddenChar(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029
}
