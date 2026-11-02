package com.wangzan101.mika.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** ハンドル (fstat/lstat) が指すファイルのタイプと ID。 */
data class FdStat(val kind: Kind, val nlink: Long, val size: Long, val dev: Long, val ino: Long) {
    enum class Kind { FILE, DIR, LINK, OTHER }
}

/** は、一度取得したハンドルを開きます。実際のファイルを決定するためにハンドル自体 (Android では /proc/self/fd/N を読み取ります) のみが使用され、パスを再トレースする必要はありません。 */
interface OpenFd : Closeable {
    fun realPath(): String
    fun stat(): FdStat
    /** ファイル ハンドルのチャネル (開く方法に応じて読み取りまたは書き込み)。ディレクトリにはありません。 */
    fun channel(): FileChannel
    fun truncate(size: Long)
}

enum class OpenHow { READ, CREATE_NEW, WRITE, APPEND }

/** [FdSys] によって報告されたシステム コール エラーは、[WorkspaceFileGate] のツール層のエラー コードに置き換えられます。 */
class FdSysException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NOT_FOUND, EXISTS, LINK, NOT_DIR, NOT_FILE }
}

/**
 * システムコール層。 dir を使用した呼び出しは、開かれたディレクトリに基づきます。 [name] は単一セグメント名であり、最後のセグメントはリンクに続きません。
 * は Android のアプリに実装されています (O_NOFOLLOW + /proc/self/fd/N を開きます)。 [NioFdSys] は JVM 上のパスの近似値であり、JVM テストにのみ使用されます。
 */
interface FdSys {
    /** ルート ディレクトリ自体 (呼び出し元によって指定され、サンドボックスからは到達できないパス)。最後の段落はリンクです。 */
    fun openRoot(path: File): OpenFd
    fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd
    fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd
    /** リンクを辿らない; 存在しなければ null。 */
    fun lstat(dir: OpenFd, name: String): FdStat?
    fun readlink(dir: OpenFd, name: String): String?
    fun list(dir: OpenFd): List<String>
    /** その項目自体を削除する（ファイル、リンク、または空のディレクトリ）。リンクは辿らない。 */
    fun remove(dir: OpenFd, name: String)
    /** 同じディレクトリの中で名前を変える。[to] が既にある場合（ファイルかリンク）は丸ごと置き換える。 */
    fun rename(dir: OpenFd, from: String, to: String)
    fun symlink(dir: OpenFd, name: String, target: String)
    fun chmod(fd: OpenFd, mode: Int)
}

/**
 * JVM 上の近似実装: パスで解決するため、確認と使用の間に隙間がある。JVM 単体テスト専用（macOS には /proc/self/fd がない）;
 * 携帯電話はすべて、アプリに Android を使用して実装されています。
 */
object NioFdSys : FdSys {
    private class Fd(val path: Path, private val ch: FileChannel?) : OpenFd {
        private val real = path.toRealPath().toString()
        override fun realPath() = real
        override fun stat(): FdStat = statOf(path) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        override fun channel(): FileChannel = ch ?: throw FdSysException(FdSysException.Kind.NOT_FILE)
        override fun truncate(size: Long) { channel().truncate(size) }
        override fun close() { ch?.close() }
    }

    private fun fd(dir: OpenFd) = dir as Fd

    private fun attrs(p: Path): BasicFileAttributes? =
        try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { null }

    private fun statOf(p: Path): FdStat? {
        val a = try { Files.readAttributes(p, "unix:mode,nlink,size,dev,ino", LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { return null }
        val mode = (a["mode"] as Int) and 0xF000
        val kind = when (mode) { 0x8000 -> FdStat.Kind.FILE; 0x4000 -> FdStat.Kind.DIR; 0xA000 -> FdStat.Kind.LINK; else -> FdStat.Kind.OTHER }
        return FdStat(kind, (a["nlink"] as Number).toLong(), (a["size"] as Number).toLong(), (a["dev"] as Number).toLong(), (a["ino"] as Number).toLong())
    }

    private fun dirAt(p: Path): Fd {
        val a = attrs(p) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        if (a.isSymbolicLink) throw FdSysException(FdSysException.Kind.LINK)
        if (!a.isDirectory) throw FdSysException(FdSysException.Kind.NOT_DIR)
        return Fd(p, null)
    }

    override fun openRoot(path: File): OpenFd = dirAt(path.toPath().toAbsolutePath())

    override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
        val p = fd(dir).path.resolve(name)
        if (create && attrs(p) == null) runCatching { Files.createDirectory(p) }
        return dirAt(p)
    }

    override fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd {
        val p = fd(dir).path.resolve(name)
        val a = attrs(p)
        if (a?.isSymbolicLink == true) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.LINK)
        // NIO を開けません。 O_NONBLOCK: FIFO などは、読み取りおよび書き込み中のスタックを避けるために、タイプによって最初に拒否されます。
        if (a != null && !a.isRegularFile) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.NOT_FILE)
        val opts = when (how) {
            OpenHow.READ -> setOf(StandardOpenOption.READ)
            OpenHow.CREATE_NEW -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            OpenHow.WRITE -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            OpenHow.APPEND -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        val ch = try {
            FileChannel.open(p, opts + LinkOption.NOFOLLOW_LINKS)
        } catch (e: FileAlreadyExistsException) {
            throw FdSysException(FdSysException.Kind.EXISTS, e)
        } catch (e: NoSuchFileException) {
            throw FdSysException(FdSysException.Kind.NOT_FOUND, e)
        }
        return Fd(p, ch)
    }

    override fun lstat(dir: OpenFd, name: String): FdStat? = statOf(fd(dir).path.resolve(name))

    override fun readlink(dir: OpenFd, name: String): String? =
        runCatching { Files.readSymbolicLink(fd(dir).path.resolve(name)).toString() }.getOrNull()

    override fun list(dir: OpenFd): List<String> = Files.list(fd(dir).path).use { s -> s.map { it.fileName.toString() }.toList() }

    override fun remove(dir: OpenFd, name: String) {
        try { Files.delete(fd(dir).path.resolve(name)) } catch (e: NoSuchFileException) { throw FdSysException(FdSysException.Kind.NOT_FOUND, e) }
    }

    override fun rename(dir: OpenFd, from: String, to: String) {
        val d = fd(dir).path
        Files.move(d.resolve(from), d.resolve(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun symlink(dir: OpenFd, name: String, target: String) {
        Files.createSymbolicLink(fd(dir).path.resolve(name), java.nio.file.Paths.get(target))
    }

    override fun chmod(fd: OpenFd, mode: Int) {
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString(
            (0..8).joinToString("") { i -> if (mode and (1 shl (8 - i)) != 0) "rwx"[i % 3].toString() else "-" },
        )
        Files.setPosixFilePermissions(fd(fd).path, perms)
    }
}
