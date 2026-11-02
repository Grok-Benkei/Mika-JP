package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** メイン画面占有率、Mika 他の画面占有率、およびコールド スタート タスク: 2 つのコピーがインストールされているアプリは「パッケージ名 + ユーザー」に従って個別に計算されますが、1 つのコピーのみがインストールされているアプリは同じままです。 */
class SameCopyTest {
    private val chat = "com.example.chat"

    @Test fun theUserOnTheMainCopyDoesNotBlockTheCloneAndViceVersa() {
        // ホーム画面はユーザー 0 の共有です
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf(chat), 0, chat, 128))
        // はホーム画面のクローンです
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, 128))
        assertFalse(ShellService.sameCopy(listOf(chat), 128, chat, 0))
    }

    @Test fun unknownTaskUserCountsAsTheSameCopy() {
        // タスクがどのユーザーに属しているかを読み取ることができません。むしろ、占有されているものとして扱います
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 128))
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 0))
    }

    @Test fun singleCopyAndPackageOnlyChecksAreUnchanged() {
        // はコピーを 1 つだけインストールしました。タスクはすべてユーザー 0 にあり、以前と同様にパッケージ名によって計算されます。
        assertTrue(ShellService.sameCopy(listOf("x.launcher", chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf("x.other"), 0, chat, 0))
        // どの部分 (シェア) であっても: どの部分でもカウントされます
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, null))
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, null))
    }
}
