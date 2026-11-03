package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** の実際のインストール (組み込みのオペレーティング環境のロックを解除し、インターネットに接続されていません);インストール後、サンドボックスで Python、Node、`mika` を実行します。 */
@RunWith(AndroidJUnit4::class)
class InstallDeviceTest {
    private val sb = AndroidSandbox(context, SandboxState.get(context))

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("install-test", listOf("/bin/sh", "-lc", command), null, 60_000) {} }

    @Test
    fun fullInstallThenPythonAndNode() {
        assertEquals(SandboxStatus.Ready(network = false), SandboxState.get(context).status.value)
        sh("python3 -c 'print(1+1)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("2", it.output.trim()) }
        sh("node -e 'console.log(2+2)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("4", it.output.trim()) }
        sh("mika info").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            Json.parseToJsonElement(r.output.trim()).jsonObject // は JSON オブジェクトです
        }
    }

    /** は独自の Alpine とともにインストールされます。パッケージはすべて apk データベース内にあり、データベースはファイルと一致しています (apk-tools 3 は PRoot ではデータベースに書き込むことができないため、apk-tools 2 の 3.22 が使用されます)。 */
    @Test
    fun pinnedReleaseAndPackagesRecorded() {
        sh("cat /etc/alpine-release").let { assertEquals(it.output, BundledRuntime.VERSION.substringBefore('-'), it.output.trim()) }
        sh("apk --version").let { assertEquals(it.output, 0, it.exitCode); assertTrue(it.output, it.output.startsWith("apk-tools 2.")) }
        sh("apk info -e ${SANDBOX_PACKAGES.joinToString(" ")} 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            assertEquals(r.output, SANDBOX_PACKAGES.toSet(), r.output.lines().filter(String::isNotBlank).toSet())
        }
        // インターネットがオフになっている場合、APK はソフトウェア ソース キャッシュを読み取れないという標準エラーを報告し、標準出力のみを読み取ります。
        // アンパッカーには拡張属性がない (これは設計です)。そのため、拡張属性のみを持つエントリ (コード x) はカウントされません。コンテンツ、権限、所有者の違いは引き続きカウントされます
        sh("apk audit --system 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            val drift = r.output.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x ") }
            assertEquals(r.output, emptyList<String>(), drift)
        }
    }

    @Test
    fun networkOffAfterInstall() {
        val r = sh("python3 -c 'import socket; socket.create_connection((\"1.1.1.1\", 443), timeout=5)'")
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("PermissionError") || r.output.contains("Permission denied"))
        assertEquals(false, r.network)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun install() {
            val state = SandboxState.get(context)
            state.networkAllowed = false
            val installer = RootfsInstaller.create(context)
            runBlocking {
                installer.reset()
                installer.install { _, _ -> }
            }
            assertEquals(InstallStep.Done, state.completedStep())
        }
    }
}
