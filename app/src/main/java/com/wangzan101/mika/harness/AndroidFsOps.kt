package com.wangzan101.mika.harness

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.harness.tar.FileKind
import com.wangzan101.mika.harness.tar.FsOps
import java.io.File
import java.io.IOException

/** Port of DSH Mobile's MIT-licensed AndroidFsOps for safe tar extraction. */
internal object AndroidFsOps : FsOps {
    override fun lstat(file: File): FileKind {
        val stat = try { Os.lstat(file.path) } catch (e: ErrnoException) { return FileKind.NONE }
        return when {
            OsConstants.S_ISLNK(stat.st_mode) -> FileKind.SYMLINK
            OsConstants.S_ISDIR(stat.st_mode) -> FileKind.DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) -> FileKind.FILE
            else -> FileKind.OTHER
        }
    }

    override fun symlink(target: String, link: File) {
        try { Os.symlink(target, link.path) }
        catch (e: ErrnoException) { throw IOException("symlink failed", e) }
    }

    override fun chmod(file: File, mode: Int) {
        try { Os.chmod(file.path, mode) }
        catch (e: ErrnoException) { throw IOException("chmod failed", e) }
    }

    override fun readlink(file: File): String? = try { Os.readlink(file.path) }
        catch (e: ErrnoException) { null }
}
