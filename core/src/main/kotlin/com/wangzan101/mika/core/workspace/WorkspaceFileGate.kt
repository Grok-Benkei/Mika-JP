package com.wangzan101.mika.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

/**
 * ホスト側のサンドボックス ディレクトリ (ワークスペース、スキル ディレクトリ、ルート ファイル システム) に出入りするすべてのファイルは、このチャネルを通過します。
 *
 * サンドボックスのコマンドは、Mika と同じユーザーです。ファイルやディレクトリはいつでもシンボリックリンク、ハードリンク、FIFOに変更できるので、「まずパスに従って確認し、その後パスに従って使用する」ようにしてください。
 * を停止できません。ここでの各セクションは、前のセクションで開かれたディレクトリ ハンドルに関連して開かれ、リンクをたどりません。最後のファイルは 1 回だけ開かれ、ハンドル上で検証されます。
 * (実際の場所は依然としてルート ディレクトリ内にあり、通常のファイルであり、ハード リンクが 1 つだけあります)、その後の読み取りと書き込みはすべてこの同じハンドル上で行われます。削除もこの方法で確認されます。
 *
 * [rel] は、ルート ディレクトリの下の相対パスです (`/` で区切られています)。 `..` および null バイトは範囲外とみなされます。エラーはすべて [SandboxError] としてスローされ、メッセージにはホスト パスが含まれません。
 */
class WorkspaceFileGate(private val sys: FdSys) {

    /** の読み取り: 通常のファイル、ハード リンクが 1 つだけ。 [under] が指定された場合、ファイルは `<root>/<under>/` に直接存在する必要があります。 */
    fun openRead(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.READ, createDirs = false)

    /** 新規作成 (同じ名前のレポート ファイルが既に存在します。上書きされません)。不足しているディレクトリはセクションごとに作成されます。 */
    fun createNew(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.CREATE_NEW, createDirs = true)

    /** 書き込み: 存在しない場合は作成します。検証後に既存のファイルを切り捨てる ([append] の場合は書き込みを続ける) ため、他のリンクされたファイルは切り詰められません。 */
    fun openWrite(root: File, rel: String, append: Boolean): GatedFile {
        val g = open(root, rel, null, if (append) OpenHow.APPEND else OpenHow.WRITE, createDirs = true)
        if (!append) try { g.truncate() } catch (e: IOException) { g.close(); throw e }
        return g
    }

    /** `<root>/<dir>/` に新しい [name] を作成します。名前が同じ場合は、「name (2).ext」... (最大でも [max]) を試してください。実際の名前とハンドルを返します。 */
    fun createNumbered(root: File, dir: String, name: String, max: Int = 999): Pair<String, GatedFile> {
        for (n in 1..max) {
            val candidate = numbered(name, n)
            try {
                return candidate to createNew(root, "$dir/$candidate", under = dir)
            } catch (e: SandboxError) {
                if (e.code != FILE_EXISTS) throw e
            }
        }
        throw SandboxError(FILE_EXISTS, "Too many files with this name.", recoverable = true)
    }

    /** 通常のファイルであることを確認してから削除してください。 */
    fun delete(root: File, rel: String, under: String? = null) {
        open(root, rel, under, OpenHow.READ, createDirs = false).use { it.delete() }
    }

    /** ディレクトリを作成します (リンクをたどらずにセクションごとに)。 */
    fun mkdirs(root: File, rel: String) {
        anchored(root, segments(rel), create = true) { _, _ -> }
    }

    /** ディレクトリ内の名前。ディレクトリが存在しない場合は空になります。 */
    fun list(root: File, rel: String): List<String> = try {
        anchored(root, segments(rel), create = false) { _, d -> sys.list(d) }
    } catch (e: SandboxError) {
        if (e.code == FILE_NOT_FOUND) emptyList() else throw e
    }

    /**
     * [rel] 全体を置き換えます。同じディレクトリに新しいものを書き込み、名前を変更します。リンク、FIFO、またはハードリンクであることが判明した項目はどれも置き換えられるだけで、それが何を指しているのかは書き込まれません。
     */
    fun replace(root: File, rel: String, bytes: ByteArray, mode: Int = 420 /* 0644 */) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { rootReal, dir ->
            val tmp = ".mika-new-" + UUID.randomUUID()
            val f = file(dir, tmp, OpenHow.CREATE_NEW)
            try {
                f.use {
                    verify(it, rootReal, dir, tmp)
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) it.channel().write(buf)
                    sys.chmod(it, mode)
                }
                sys.rename(dir, tmp, segs.last())
            } catch (e: Exception) {
                runCatching { sys.remove(dir, tmp) }
                throw e
            }
        }
    }

    /** は、[rel] を [target] を指すシンボリック リンクにします (既に存在する場合は移動しません)。元の商品を完全に交換してください。 */
    fun symlink(root: File, rel: String, target: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { _, dir ->
            if (sys.readlink(dir, segs.last()) == target) return@anchored
            val tmp = ".mika-link-" + UUID.randomUUID()
            sys.symlink(dir, tmp, target)
            try { sys.rename(dir, tmp, segs.last()) } catch (e: Exception) { runCatching { sys.remove(dir, tmp) }; throw e }
        }
    }

    /**
     * `<root>/<rel>` ツリー全体を削除します。各レイヤーは、前のレイヤーのハンドルを基準にして開かれ、リンクに従いません。リンクはリンク自体を削除するだけです。
     * 削除処理中にリンクに変更されたディレクトリは追跡されません。まず、読み取り専用ディレクトリを書き込み可能にします。
     */
    fun deleteTree(root: File, rel: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        try {
            anchored(root, segs.dropLast(1), create = false) { rootReal, dir -> removeEntry(dir, segs.last(), rootReal, 0) }
        } catch (e: SandboxError) {
            if (e.code != FILE_NOT_FOUND) throw e
        }
    }

    private fun removeEntry(dir: OpenFd, name: String, rootReal: String, depth: Int) {
        val st = sys.lstat(dir, name) ?: return
        if (st.kind == FdStat.Kind.DIR) {
            if (depth > MAX_DEPTH) throw IOException("directory tree too deep to delete")
            val sub = try { sys.openDir(dir, name, create = false) } catch (e: FdSysException) { null }
            if (sub != null) sub.use {
                // は、削除プロセス中にルート ディレクトリの外に移動されたため、削除されません。
                if (!inside(it.realPath(), rootReal)) return
                runCatching { sys.chmod(it, 448 /* 0700 */) }
                for (child in sys.list(it)) removeEntry(it, child, rootReal, depth + 1)
            }
        }
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    private fun open(root: File, rel: String, under: String?, how: OpenHow, createDirs: Boolean): GatedFile {
        val segs = segments(rel)
        if (segs.isEmpty()) throw notAFile()
        if (under != null && segs.dropLast(1) != segments(under)) throw outside()
        val rootFd = openRoot(root)
        val rootReal: String
        val dir: OpenFd
        try {
            rootReal = rootFd.realPath()
            dir = walk(rootFd, segs.dropLast(1), createDirs)
        } catch (e: Exception) {
            rootFd.close()
            throw e
        }
        // の後、dir は GatedFile に戻ります。 dir がルート ディレクトリそのものではない場合、ルート ディレクトリ ハンドルはここで閉じられます。
        if (dir !== rootFd) rootFd.close()
        try {
            if (under != null && dir.realPath() != rootReal.trimEnd('/') + "/" + segments(under).joinToString("/")) throw outside()
            val f = file(dir, segs.last(), how)
            try {
                return GatedFile(sys, dir, f, segs.last(), verify(f, rootReal, dir, segs.last()))
            } catch (e: Exception) {
                f.close()
                throw e
            }
        } catch (e: Exception) {
            dir.close()
            throw e
        }
    }

    /** ルート ディレクトリ ハンドル → [segs] が指すディレクトリまでセグメントごとに開きます。中間ハンドルは使い切ると閉じられます。 */
    private fun walk(rootFd: OpenFd, segs: List<String>, create: Boolean): OpenFd {
        var cur = rootFd
        for (s in segs) {
            val next = try { sys.openDir(cur, s, create) } catch (e: FdSysException) {
                if (cur !== rootFd) cur.close()
                throw map(e, dirStep = true)
            }
            if (cur !== rootFd) cur.close()
            cur = next
        }
        return cur
    }

    private fun <T> anchored(root: File, segs: List<String>, create: Boolean, block: (String, OpenFd) -> T): T {
        val rootFd = openRoot(root)
        try {
            val rootReal = rootFd.realPath()
            val dir = walk(rootFd, segs, create)
            try {
                if (!inside(dir.realPath(), rootReal) && dir !== rootFd) throw outside()
                return block(rootReal, dir)
            } finally {
                if (dir !== rootFd) dir.close()
            }
        } finally {
            rootFd.close()
        }
    }

    private fun openRoot(root: File): OpenFd = try { sys.openRoot(root) } catch (e: FdSysException) { throw map(e, dirStep = true) }

    private fun file(dir: OpenFd, name: String, how: OpenHow): OpenFd =
        try { sys.openFile(dir, name, how) } catch (e: FdSysException) { throw map(e, dirStep = false) }

    /** ハンドルで検証済み: 実際の場所は、ルート ディレクトリ内の開かれたディレクトリ内の名前です。通常のファイル。ハードリンクは 1 つだけです。 ルート ディレクトリ内の */
    private fun verify(f: OpenFd, rootReal: String, dir: OpenFd, name: String): FdStat {
        val real = f.realPath()
        if (!inside(real, rootReal) || real != dir.realPath().trimEnd('/') + "/" + name) throw outside()
        val st = f.stat()
        if (st.kind != FdStat.Kind.FILE) throw notAFile()
        if (st.nlink != 1L) throw SandboxError(LINKED_FILE, "The file has more than one hard link, so Mika does not use it; copy it (cp) and use the copy.", recoverable = true)
        return st
    }

    private fun map(e: FdSysException, dirStep: Boolean): SandboxError = when (e.kind) {
        FdSysException.Kind.NOT_FOUND -> SandboxError(FILE_NOT_FOUND, "The file does not exist.", recoverable = true)
        FdSysException.Kind.EXISTS -> SandboxError(FILE_EXISTS, "A file with this name already exists.", recoverable = true)
        FdSysException.Kind.NOT_DIR -> SandboxError(FILE_NOT_FOUND, "A folder on this path is not a folder.", recoverable = true)
        FdSysException.Kind.LINK ->
            if (dirStep) SandboxError(NOT_A_FILE, "A folder on this path is a link; Mika's file tools do not follow links. Use the real path.", recoverable = true)
            else notAFile()
        FdSysException.Kind.NOT_FILE -> notAFile()
    }

    private fun outside() = SandboxError(OUTSIDE, "The file is outside the allowed folder.", recoverable = true)
    private fun notAFile() = SandboxError(NOT_A_FILE, "Not a regular file (a directory, link or special file).", recoverable = true)

    companion object {
        const val FILE_NOT_FOUND = "file_not_found"
        const val FILE_EXISTS = "file_exists"
        const val NOT_A_FILE = "not_a_file"
        const val LINKED_FILE = "linked_file"
        const val OUTSIDE = "path_outside_workspace"
        private const val MAX_DEPTH = 256

        /** セグメント。 `..` および null バイトは範囲外です。 */
        fun segments(rel: String): List<String> {
            if ('\u0000' in rel) throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            return rel.split('/').filter { it.isNotEmpty() && it != "." }.onEach {
                if (it == "..") throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            }
        }

        /** "a.csv" → "a (2).csv";拡張子のないものを最後に直接追加します。 */
        fun numbered(name: String, n: Int): String {
            if (n <= 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }

        internal fun inside(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    }
}

/** は、[WorkspaceFileGate] によって検証されたファイルです。読み取りと書き込みの両方がこのハンドル上で行われます。 [stat] はリアルタイムであることが検証されています。 */
class GatedFile internal constructor(
    private val sys: FdSys,
    private val dir: OpenFd,
    private val fd: OpenFd,
    val name: String,
    val stat: FdStat,
) : Closeable {
    val size: Long get() = stat.size

    fun channel() = fd.channel()

    /** 先頭から (または現在の位置から) 読み取ります。ストリームは閉じますが、ハンドルは閉じません。 */
    fun input(): InputStream = object : java.io.FilterInputStream(Channels.newInputStream(fd.channel())) { override fun close() = Unit }

    fun output(): OutputStream = object : java.io.FilterOutputStream(Channels.newOutputStream(fd.channel())) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** は、[offset] からファイルの終わりまで最大 [len] バイトを読み取ります。 */
    fun read(offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = fd.channel().read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    fun write(bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) fd.channel().write(buf)
    }

    fun currentSize(): Long = fd.channel().size()

    internal fun truncate() = fd.truncate(0)

    /** このファイルを削除します: ディレクトリ内の名前が同じファイル (デバイス番号 + i ノード) である場合にのみ削除します。他のものに変更した場合は削除されません。 */
    fun delete() {
        val now = sys.lstat(dir, name) ?: return
        if (now.dev != stat.dev || now.ino != stat.ino || now.kind != FdStat.Kind.FILE) return
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    override fun close() {
        runCatching { fd.close() }
        runCatching { dir.close() }
    }
}
