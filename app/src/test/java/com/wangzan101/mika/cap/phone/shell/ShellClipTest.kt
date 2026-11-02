package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.ShellClip.Now
import com.wangzan101.mika.cap.phone.shell.ShellClip.Read
import com.wangzan101.mika.cap.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

/** 入力をクリップボードに貼り付けてから復元します。読み取れない場合でも、空とは見なされません。クリップボードがミカのものでない場合、クリップボードは移動しません。 */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        // 取得されたものは空ですが、システムはコンテンツがあると表示します: 読み取れません、空ではありません
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        // ユーザーが途中で何か他のものをコピーしたか、現時点ではそれを読み取ることができません: 移動しません
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        // ミカのコンテンツは他の人によってクリアされています (たとえば、システムが定期的にクリアします)。コンテンツが含まれている場合は元に戻してください。過去に空であったとしても気にしません。
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}
