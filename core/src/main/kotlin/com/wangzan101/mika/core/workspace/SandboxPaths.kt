package com.wangzan101.mika.core.workspace

import java.io.File
import java.nio.file.Files

/** モデルはサンドボックス内のパスのみを認識します。ここでは正規化され、範囲外と判断されます。 */
object SandboxPaths {
    const val WORKSPACE = "/workspace"
    const val SKILLS = "/opt/mika/skills"

    /** 相対パスは [cwd] を基準とする; 正規化した後に [WORKSPACE] または [SKILLS] の下になければ null を返す。 */
    fun normalize(path: String, cwd: String = WORKSPACE): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        val parts = ArrayDeque<String>()
        for (seg in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(seg)
        }
        val n = "/" + parts.joinToString("/")
        return n.takeIf { under(it, WORKSPACE) || under(it, SKILLS) }
    }

    /** 書き込みできるのはワークスペースだけ; スキルディレクトリは読み取り専用。 */
    fun isWritable(normalized: String): Boolean = under(normalized, WORKSPACE)

    /**
     * [root] 配下の [sandboxRel]; シンボリックリンクを段ごとに確認する: [root] の外を指すもの、対象が存在しないもの（書き込んだらどこへ行くか分からない）は一律 null、
     * 最終的な canonicalFile も [root] の下に残っていなければならない。
     */
    fun resolveHost(root: File, sandboxRel: String): File? {
        val base = root.canonicalFile
        var cur = base
        for (seg in sandboxRel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = File(cur, seg)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) {
                if (!Files.exists(p)) return null
                cur = cur.canonicalFile
                if (!inside(cur, base)) return null
            }
        }
        return cur.canonicalFile.takeIf { inside(it, base) }
    }

    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun inside(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)
}
