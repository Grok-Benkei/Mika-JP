package com.wangzan101.mika.cap.phone.setup

import com.wangzan101.mika.core.shell.SuAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RootShellTest {
    @Test fun theHelperHandoffRequiresBothShellIdentityValues() {
        assertEquals(SuAnswer.Granted, answer("su ready\nuid=2000(shell) gid=2000(shell) groups=1004(input)\n"))
        assertEquals(SuAnswer.WrongUid, answer("uid=2000(shell) gid=0(root)\n"))
        assertEquals(SuAnswer.WrongUid, answer("uid=0(root) gid=2000(shell)\n"))
        assertEquals(SuAnswer.WrongUid, answer("2000\n"))
        assertNull(answer("permission denied\n"))
    }

    private fun answer(output: String): SuAnswer? = RootShell.readShellAnswer(object : Process() {
        override fun getInputStream() = ByteArrayInputStream(output.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }, 1_000)
}
