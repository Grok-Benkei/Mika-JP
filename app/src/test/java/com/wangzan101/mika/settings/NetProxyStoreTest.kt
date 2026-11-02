package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 設定 › ネットワーク プロキシ: アカウントとパスワードを持つことができ、それらは暗号化され、クリア テキストなしのファイルとして保存されます。保存する前にアドレスがチェックされます。人々が読めるように書かれた方法には、アカウントとパスワードは含まれていません。古いバージョンでは、コンテナーの使用時にのみコピーが保存されます。 */
class NetProxyStoreTest {
    private val dir: File = Files.createTempDirectory("proxy").toFile()

    /** テスト用の「暗号化」: バイトごとに反転し、ファイルがオリジナルのテキストではないことを確認するのに十分です。 */
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { plain[it].toInt().inv().toByte() }
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun storedEncryptedAndReadBack() {
        val proxy = "http://alice:s3cret@10.0.0.2:8080"
        NetProxyStore(dir, cipher).set(proxy)
        val file = File(dir, "net-proxy.bin")
        assertTrue(file.isFile)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("ファイルには平文で保存しない", raw.contains("s3cret") || raw.contains("alice"))
        // 新しいコピーを開くと (再起動後など)、引き続き読み取ることができます
        assertEquals(proxy, NetProxyStore(dir, cipher).get())
    }

    @Test fun emptyRemovesTheFile() {
        val store = NetProxyStore(dir, cipher)
        store.set("socks5://127.0.0.1:1080")
        store.set("  ")
        assertEquals("", store.get())
        assertFalse(File(dir, "net-proxy.bin").exists())
        assertEquals("", NetProxyStore(dir, cipher).get())
    }

    /** のロックを解除できない (キーがなくなっている) 場合は、未設定として扱われ、コマンドが文字化けしたコードで接続されることはありません。 */
    @Test fun unreadableCountsAsNotSet() {
        File(dir, "net-proxy.bin").writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, broken).get())
    }

    @Test fun onlyProxyAddressesAreAccepted() {
        for (ok in listOf("", "http://127.0.0.1:7890", "https://proxy.example.com:443", "socks5h://u:p@[::1]:1080", "HTTP://host:80/")) {
            assertTrue(ok, NetProxyStore.valid(ok))
        }
        for (bad in listOf("127.0.0.1:7890", "ftp://h:21", "http://host", "http://host:0", "http://host:70000", "http://h:80/path", "http://h :80", "http://h:80?x=1")) {
            assertFalse(bad, NetProxyStore.valid(bad))
        }
    }

    @Test fun displayLeavesOutCredentials() {
        assertEquals("http://10.0.0.2:8080", NetProxyStore.display("http://alice:s3cret@10.0.0.2:8080"))
        assertEquals("socks5://127.0.0.1:1080", NetProxyStore.display("socks5://127.0.0.1:1080"))
    }

    /** 別の「キー」: バイトごとに追加します。これは、[cipher] では解決できません。 */
    private val legacyCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it] + 1).toByte() }
        override fun decrypt(data: ByteArray) = ByteArray(data.size) { (data[it] - 1).toByte() }
    }

    /** 古いバージョンはコンテナー用のプロキシのみを提供します。アップグレード後も引き続き読み取ることができ、新しいファイルに変更し、古いファイルを削除すると、新しいファイルのみが認識されます。 */
    @Test fun containerOnlySettingIsCarriedOver() {
        val old = File(dir, "container-proxy.bin")
        old.writeBytes(legacyCipher.encrypt("socks5://bob:pw@10.0.0.9:1080".toByteArray()))
        var changes = 0
        val store = NetProxyStore(dir, cipher, legacyCipher) { changes++ }
        assertEquals("socks5://bob:pw@10.0.0.9:1080", store.get())
        assertFalse(old.exists())
        assertTrue(File(dir, "net-proxy.bin").isFile)
        assertEquals("読み取りは設定変更として扱わない", 0, changes)
        // 再起動後（古いファイルはもうありません）、依然として同じです
        assertEquals("socks5://bob:pw@10.0.0.9:1080", NetProxyStore(dir, cipher, legacyCipher).get())
        store.set("http://127.0.0.1:7890")
        assertEquals(1, changes)
    }

    /** 新しいファイルはすでに存在します。古いファイルはそれを上書きしません。 */
    @Test fun newSettingWinsOverLeftoverLegacyFile() {
        NetProxyStore(dir, cipher).set("http://10.0.0.2:8080")
        File(dir, "container-proxy.bin").writeBytes(legacyCipher.encrypt("http://old:1".toByteArray()))
        assertEquals("http://10.0.0.2:8080", NetProxyStore(dir, cipher, legacyCipher).get())
    }

    /** 古いファイルを開けません。設定されていない場合、エラーは報告されません。 */
    @Test fun unreadableLegacyFileCountsAsNotSet() {
        File(dir, "container-proxy.bin").writeBytes(byteArrayOf(9, 9))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, cipher, broken).get())
    }
}
