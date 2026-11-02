package com.wangzan101.mika.cap.sandbox

import androidx.annotation.VisibleForTesting
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * は、アプリにパッケージ化されているスキル スクリプト (リスト + リストにリストされているファイル) を `files/sandbox/skills/` (サンドボックス内の `/opt/mika/skills`) に同期します。
 * このディレクトリはサンドボックスに書き込むことができる (PRoot のバインディングは書き込み可能) ため、ディスク上の実際のコンテンツに従って毎回チェックされます。パッケージ化されたものとまったく同じでない限り、ディレクトリは移動されません。
 * 余分なファイル、不足しているファイル、または変更されたファイルがある場合、またはリンクやその他の通常ではないファイルがある場合は、その隣に新しいファイルを書き込み、全体を置き換えます。
 *
 * 並列タスク: [lock] シリアルによって同期されます。ディレクトリを変更した時点で、別のタスクで開始されているスクリプトがファイルを見つけることができず、エラーを報告する (正直に戻る) 可能性があります。
 * すでに実行中のインタープリターはすでにスクリプトを読み取っているため、影響を受けません。 run_bundled_script は、実行前に再度バイトごとにチェックされます ([matches])。
 */
object SkillsSync {
    private val lock = Mutex()
    private val packaged by lazy { GuideCatalog.loadBundled().scriptFiles() }

    /** は実機テスト専用です。一時的にテストスキルを追加します。公式コードでは設定されていません。 */
    @VisibleForTesting
    @Volatile var extraForTests: Map<String, ByteArray> = emptyMap()

    val bundled: Map<String, ByteArray> get() = if (extraForTests.isEmpty()) packaged else packaged + extraForTests

    /**
     * は、インストールと [SandboxTree] によるリセットとは相互に排他的です (インストールのこのステップは、インストールが保持するライセンス内で実行されます)。 [proceed] は、ライセンスを取得した後、もう一度調べます。
     * ライセンス期間中に動作環境がリセットされた（準備ができなくなった）場合、新しく削除されたディレクトリに新しいスキル ディレクトリが作成されることを避けるために、環境は書き込まれません。
     */
    suspend fun sync(dirs: SandboxDirs, files: Map<String, ByteArray> = bundled, proceed: () -> Boolean = { true }) = dirs.tree.shared { lock.withLock {
        withContext(Dispatchers.IO) {
            if (!proceed()) return@withContext
            if (onDisk(dirs)?.let(::hashOf) == hashOf(files)) return@withContext
            val staging = File(dirs.root, "skills.new")
            RootfsExtractor.deleteTree(staging)
            staging.mkdirs()
            for ((rel, bytes) in files) {
                // パスはパッケージ化されたリストから取得されます。ルールに従って解析され、ディレクトリの外には書き込まれません。
                val f = SandboxPaths.resolveHost(staging, rel) ?: continue
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            // スキル ディレクトリ サンドボックスを書き込むことができます。ハンドルに従ってツリー全体を削除する必要があり、途中でリンクに変更されたディレクトリはたどられません。
            dirs.gate.deleteTree(dirs.root, dirs.skills.name)
            check(staging.renameTo(dirs.skills)) { "could not move synced skills into place" }
            // マークされたファイルによって古いバージョンが判断され、使用されなくなります。
            File(dirs.root, "skills.sha256").delete()
        }
    } }

    /** [rel] は、ディスク上のパックされた [expected] とバイトごとに同一です (リンクをたどらず、ゲートを介して読み取られます)。 */
    internal fun matches(dirs: SandboxDirs, rel: String, expected: ByteArray): Boolean = try {
        dirs.gate.openRead(dirs.skills, rel).use { g -> g.size == expected.size.toLong() && g.read(0, expected.size).contentEquals(expected) }
    } catch (e: SandboxError) {
        false
    } catch (e: IOException) {
        false
    }

    /** ディレクトリ内のすべてのファイル (相対パス → コンテンツ);リンクや特殊ファイルが大きすぎるか読み取れない場合、それらは null になります (矛盾しているものとして扱われます)。ディレクトリは名前を見つけるためだけにリストされ、内容はパスを通じて読み取られます。 */
    private fun onDisk(dirs: SandboxDirs): Map<String, ByteArray>? {
        val base = dirs.skills.toPath()
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null
        val out = HashMap<String, ByteArray>()
        var total = 0L
        return try {
            Files.walk(base).use { paths ->
                for (p in paths) {
                    if (p == base || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
                    val rel = base.relativize(p).joinToString("/")
                    out[rel] = dirs.gate.openRead(dirs.skills, rel).use { g ->
                        total += g.size
                        if (total > MAX_ON_DISK) return null
                        g.read(0, g.size.toInt())
                    }
                }
            }
            out
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: java.io.UncheckedIOException) {
            null
        }
    }

    /** によってパッケージ化されたスキル スクリプトは、これよりもはるかに小さいです。サンドボックスに詰め込まれた大きなファイルはメモリに読み取られず、直接不整合として扱われ、再同期されます。 */
    private const val MAX_ON_DISK = 64L shl 20

    internal fun hashOf(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((rel, bytes) in files.toSortedMap()) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(bytes.size.toString().toByteArray()); md.update(0)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
