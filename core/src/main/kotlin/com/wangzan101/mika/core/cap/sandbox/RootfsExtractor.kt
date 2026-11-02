package com.wangzan101.mika.core.cap.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

data class ExtractReport(
    val files: Int,
    val dirs: Int,
    val symlinks: Int,
    val hardlinksCopied: Int,
    /** には最大でも [RootfsExtractor.MAX_SKIPPED_NAMES] の名前があります。合計数については、[skippedTotal] を参照してください。 */
    val skipped: List<String>,
    val skippedTotal: Int = skipped.size,
)

class UnsafeArchiveException(msg: String) : Exception(msg)

/**
 * ルート ファイル システム tar.gz を dest にストリーミングします。
 *
 * - シンボリック リンクのターゲットはそのまま残ります (絶対パスであっても、PRoot ではルート ファイル システムとして解釈されます)。
 * - Android 上のアプリはハード リンクを作成できず、代わりにハード リンクは解決されたターゲットをコピーします。
 * - デバイス ファイル、FIFO がスキップされ、スキップされたものとして記録されました。
 * - 正規化後に dest にないエントリ (およびハード リンク ターゲット)、または解決されたシンボリック リンクを通過すると、UnsafeArchiveException がスローされます。
 *   の抽出されたコンテンツは、呼び出し元によって [deleteTree] を使用して削除されます。File.deleteRecursively は使用できません。(絶対) シンボリック リンクに続く dest の外側で削除されます。
 * - エントリ数が maxEntries を超えている場合、または通常のファイルとハード リンク コピーの合計バイト数が maxBytes を超えている場合も、UnsafeArchiveException がスローされます。
 */
object RootfsExtractor {
    const val MAX_SKIPPED_NAMES = 1000

    fun extract(
        tarGz: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport = extractTar(GzipCompressorInputStream(BufferedInputStream(tarGz), true), dest, maxBytes, maxEntries)

    /** は [extract] と同じで、入力は解凍された tar ストリームです (呼び出し元が xz などを自分でデコードします)。 */
    fun extractTar(
        input: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        val skipped = mutableListOf<String>()
        var skippedTotal = 0
        var entries = 0
        var bytes = 0L
        fun skip(name: String) {
            skippedTotal++
            if (skipped.size < MAX_SKIPPED_NAMES) skipped += name
        }
        fun addBytes(n: Long, name: String) {
            bytes += n
            if (bytes > maxBytes) throw UnsafeArchiveException("archive exceeds $maxBytes bytes at: $name")
        }

        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (++entries > maxEntries) throw UnsafeArchiveException("archive has more than $maxEntries entries")
                val rel = relativeName(entry.name)
                if (rel.isEmpty()) continue // "./" 自体が dest です
                val target = resolveInside(root, rel, entry.name)
                when {
                    entry.isDirectory -> {
                        removeIfLinkOrFile(target, dirOk = true)
                        Files.createDirectories(target)
                        setMode(target, entry.mode, isDir = true)
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        Files.createSymbolicLink(target, linkTarget(target, entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val linkRel = relativeName(entry.linkName)
                        val source = resolveInside(root, linkRel, entry.linkName)
                        if (linkRel.isEmpty() || !Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                            skip(rel)
                            continue
                        }
                        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isRegularFile) addBytes(attrs.size(), rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // NOFOLLOW_LINKS: ターゲットがシンボリック リンクの場合、リンクが指すものではなくリンク自体をコピーします。 isFile の前の
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                        hardlinks++
                    }
                    // : Commons Compress はデバイス エントリの isFile にも true である可能性があります
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skip(rel)
                    entry.isFile -> {
                        // ライブラリは、エントリによって宣言されたバイト数のみを読み取るため、宣言されたサイズの上限で十分です。
                        addBytes(entry.size, rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // CREATE_NEW: パス上にシンボリック リンクが表示された場合は、過去をたどるよりも失敗するほうがよいでしょう。
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { tar.copyTo(it) }
                        setMode(target, entry.mode, isDir = false)
                        files++
                    }
                    else -> skip(rel) // その他の不明なタイプ
                }
            }
        }
        return ExtractReport(files, dirs, symlinks, hardlinks, skipped, skippedTotal)
    }

    /**
     * ツリー全体を削除し、決してシンボリック リンクをたどらないようにします (リンク自体は削除され、ポイントされたものは移動しません)。削除する前に、所有者が読み取り、書き込み、アクセスできるように各ディレクトリを変更してください。
     * そうしないと、読み取り専用ディレクトリの内容を削除できません。 dest が存在しない場合は何もしません。
     */
    fun deleteTree(dest: File) {
        val root = dest.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        // は FOLLOW_LINKS を渡しません: シンボリック リンク (ルート自体がリンクである場合を含む) は通常のファイルとして visitFile に渡されます。
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                perms += listOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(dir, perms)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun linkTarget(at: Path, linkName: String): Path =
        try {
            at.fileSystem.getPath(linkName)
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid symlink target: ${e.message}")
        }

    /** 先頭の「./」と末尾の「/」を削除します。絶対パスは直接拒否されます。 */
    private fun relativeName(name: String): String {
        if (name.startsWith("/")) throw UnsafeArchiveException("absolute path in archive: $name")
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        if (n == ".") n = ""
        return n.trimEnd('/')
    }

    private fun resolveInside(root: Path, rel: String, original: String): Path {
        val p = try {
            root.resolve(rel).normalize()
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid path in archive: ${e.message}")
        }
        if (!p.startsWith(root)) throw UnsafeArchiveException("path escapes destination: $original")
        // 中間レベルの 1 つが解決済みのシンボリック リンクである場合、それを書き込むと dest の範囲外になる可能性があります。
        var cur = root
        for (part in root.relativize(p).toList().dropLast(1)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw UnsafeArchiveException("path goes through a symlink: $original")
        }
        return p
    }

    private fun prepareParent(target: Path) {
        target.parent?.let { Files.createDirectories(it) }
    }

    private fun removeIfLinkOrFile(target: Path, dirOk: Boolean) {
        if (Files.isSymbolicLink(target)) {
            Files.delete(target)
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!dirOk) Files.delete(target) // 空ではないディレクトリは IOException をスローし、呼び出し元に渡します。
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target)
        }
    }

    /** は 0777 のみを受け取ります。所有者はいつでも読み書きでき、ディレクトリはいつでも入力できます。そうでない場合は、後で書き込んだり削除したりすることはできません。 */
    private fun setMode(target: Path, tarMode: Int, isDir: Boolean) {
        var m = (tarMode and 0x1ff) or 0x180
        if (isDir) m = m or 0x40
        Files.setPosixFilePermissions(target, toPerms(m))
    }

    private val permBits = listOf(
        0x100 to PosixFilePermission.OWNER_READ,
        0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE,
        0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ,
        0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun toPerms(mode: Int): Set<PosixFilePermission> =
        permBits.filter { (bit, _) -> mode and bit != 0 }.mapTo(mutableSetOf()) { it.second }
}
