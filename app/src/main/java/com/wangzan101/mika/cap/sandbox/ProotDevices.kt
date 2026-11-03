package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File

/**
 * PROot に表示されます。 /dev:`/dev/socket/` 全体をバインドしないでください (システムがサンドボックスのドメイン名解決を実行できるようにする dnsproxyd など)
 * およびバインダー デバイス (`/dev/binder`、`/dev/hwbinder`、`/dev/vndbinder`、システム サービスを通じてデータを持ち出すことができます)
 * はネットワーク切断をバイパスできるため、次のキャラクター デバイスのみがバインドされます。 `/dev/fd`、`/dev/stdin` などは、ルート ファイル システムの /proc を指すシンボリック リンクを作成します。
 */
object ProotDevices {
    /** は、ホスト上に存在する場合にのみバインドされます。同じパスはサンドボックス内の同じ場所にバインドされます。 */
    val DEVICE_BINDS = listOf(
        "/dev/null",
        "/dev/zero",
        "/dev/random",
        "/dev/urandom",
        "/dev/tty",
        "/dev/ptmx",
        "/dev/pts",
    )

    /** ルート ファイル システム /dev のシンボリック リンク: 名前 → ターゲット。 */
    val DEV_SYMLINKS = linkedMapOf(
        "fd" to "/proc/self/fd",
        "stdin" to "/proc/self/fd/0",
        "stdout" to "/proc/self/fd/1",
        "stderr" to "/proc/self/fd/2",
    )

    /** PRoot の `-b` パラメータ (ホスト上に存在するデバイスのみが含まれます)。 */
    fun bindArgs(exists: (String) -> Boolean = { File(it).exists() }): List<String> =
        DEVICE_BINDS.filter(exists).flatMap { listOf("-b", it) }

    /** /dev ディレクトリと [DEV_SYMLINKS] を作成するのは正しいです。ルートファイルシステム内。 link:dev を置き換えると、外部にはビルドされません。 */
    fun prepareRootfsDev(rootfs: File, gate: WorkspaceFileGate) {
        for ((name, target) in DEV_SYMLINKS) gate.symlink(rootfs, "dev/$name", target)
    }
}
