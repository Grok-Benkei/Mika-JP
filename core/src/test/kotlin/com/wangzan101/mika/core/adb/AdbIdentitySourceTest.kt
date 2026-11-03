package com.wangzan101.mika.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPrivateKeySpec

class AdbIdentitySourceTest {
    private class MemKeyStore(var bytes: ByteArray? = null) : AdbKeyStore {
        var writes = 0
        var unreadable = false
        override fun read(): ByteArray? {
            if (unreadable) throw AdbKeyUnreadableException("cannot unwrap")
            return bytes?.copyOf()
        }
        override fun write(pkcs8: ByteArray) { bytes = pkcs8.copyOf(); writes++ }
        override fun clear() { bytes = null; unreadable = false }
    }

    private val crt = TestTls.client.keyPair.private as RSAPrivateCrtKey

    /** は同じキーですが、プラットフォームは CRT パラメーターなしで RSA 秘密キーを提供します (エンコーディングの公開指数は 0 として書き込まれます)。 */
    private fun nonCrt(key: RSAPrivateCrtKey = crt): RSAPrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(key.modulus, key.privateExponent)) as RSAPrivateKey

    @Test
    fun generatedKeyRoundTripsToTheIdenticalPublicKey() {
        // アップグレード前に保存されるのは、生成された秘密鍵コードです (): 同じ公開鍵で読み戻される場合、ペアになっていれば再構成する必要はありません。
        repeat(3) {
            val id = AdbIdentity.generate("mika@x")
            val back = AdbIdentity.fromPkcs8(id.keyPair.private.encoded, "mika@x")
            assertArrayEquals(id.publicKey.encoded, back.publicKey.encoded)
            assertEquals(id.adbPublicKey, back.adbPublicKey)
            assertArrayEquals(id.certificate.publicKey.encoded, back.certificate.publicKey.encoded)
        }
    }

    @Test
    fun nonCrtPrivateKeyStillParsesToTheSamePublicKey() {
        val key = nonCrt()
        assertFalse(key is RSAPrivateCrtKey)
        val id = AdbIdentity.fromPkcs8(key.encoded, "mika@x")
        assertEquals(TestTls.client.publicKey, id.publicKey)
        assertEquals(BigInteger.valueOf(65537), id.publicKey.publicExponent)
        id.certificate.verify(TestTls.client.publicKey)
    }

    @Test
    fun publicExponentComesFromTheEncodingWhenTheKeyObjectLacksIt() {
        assertEquals(BigInteger.valueOf(65537), Pkcs8Rsa.publicExponent(crt.encoded))
        // CRT のないコード内のインデックスは 0 です: 不明として扱われます
        assertEquals(BigInteger.ZERO, Pkcs8Rsa.publicExponent(nonCrt().encoded))
        assertNull(Pkcs8Rsa.publicExponent(byteArrayOf(0x30, 0x05, 1)))
        // プラットフォームによって提供される秘密キーには CRT が含まれていませんが、エンコードに指数があります。エンコード内の指数を使用してください。
        assertEquals(BigInteger.valueOf(65537), AdbIdentity.publicExponent(nonCrt(), crt.encoded))
    }

    @Test
    fun exponentThatIsNotF4AndNotStoredIsRejectedNotGuessed() {
        val g = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, BigInteger.valueOf(65539))) }
        val odd = g.generateKeyPair().private as RSAPrivateCrtKey
        val stripped = nonCrt(odd)
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentity.fromPkcs8(stripped.encoded, "mika@x") }
        // コードは通常どおり読み取られます。
        assertEquals(BigInteger.valueOf(65539), AdbIdentity.fromPkcs8(odd.encoded, "mika@x").publicKey.publicExponent)
    }

    @Test
    fun garbageIsUnreadable() {
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentity.fromPkcs8(byteArrayOf(1, 2, 3), "mika@x") }
    }

    @Test
    fun noKeyStoredCreatesAndStoresOne() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        assertEquals(AdbKeyState.NotLoaded, src.state)
        val id = src.forConnect()
        assertEquals(AdbKeyState.Created, src.state)
        assertEquals(1, store.writes)
        assertArrayEquals(id.keyPair.private.encoded, store.bytes)
        val again = AdbIdentitySource(store, { "mika@x" })
        assertEquals(id.publicKey, again.forConnect().publicKey)
        assertEquals(AdbKeyState.Loaded, again.state)
        assertEquals(1, store.writes)
    }

    @Test
    fun unreadableStoredKeyIsReportedAndNeverOverwrittenByConnect() {
        val garbage = byteArrayOf(0x30, 0x03, 2, 1, 0)
        val store = MemKeyStore(garbage)
        val logged = mutableListOf<String>()
        val src = AdbIdentitySource(store, { "mika@x" }) { msg, _ -> logged += msg }
        assertThrows(AdbKeyUnreadableException::class.java) { src.forConnect() }
        assertThrows(AdbKeyUnreadableException::class.java) { src.forConnect() }
        assertEquals(AdbKeyState.Unreadable, src.state)
        assertNotNull(src.problem)
        assertTrue(logged.isNotEmpty())
        assertArrayEquals(garbage, store.bytes)
        assertEquals(0, store.writes)

        // のロックが解除できない（パッケージキーがなくなっている）、同じこと
        val locked = MemKeyStore(TestTls.client.keyPair.private.encoded).apply { unreadable = true }
        assertThrows(AdbKeyUnreadableException::class.java) { AdbIdentitySource(locked, { "mika@x" }).forConnect() }
        assertEquals(0, locked.writes)
    }

    @Test
    fun pairingReplacesAnUnreadableKeyAndConnectThenUsesTheNewOne() {
        val store = MemKeyStore(byteArrayOf(9, 9, 9))
        val src = AdbIdentitySource(store, { "mika@x" })
        val paired = src.forPairing()
        assertEquals(AdbKeyState.Created, src.state)
        assertNull(src.problem)
        assertArrayEquals(paired.keyPair.private.encoded, store.bytes)
        assertSame(paired, src.forConnect())
    }

    @Test
    fun pairingReusesAReadableKey() {
        val stored = TestTls.client.keyPair.private.encoded
        val store = MemKeyStore(stored)
        val src = AdbIdentitySource(store, { "mika@x" })
        assertEquals(TestTls.client.publicKey, src.forPairing().publicKey)
        assertEquals(0, store.writes)
    }

    @Test
    fun pairAndConnectInOneProcessShareOneIdentityEvenIfTheStoreFailsLater() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        val forPair = src.forPairing()
        // 保存したものは後で壊れました。ペアリングに使用したものはこのプロセスでも引き続き使用されます。
        store.unreadable = true
        assertSame(forPair, src.forConnect())
        assertSame(forPair, src.forPairing())
    }

    @Test
    fun forgetClearsTheCacheAndTheStore() {
        val store = MemKeyStore()
        val src = AdbIdentitySource(store, { "mika@x" })
        val first = src.forConnect()
        src.forget()
        assertNull(store.bytes)
        assertEquals(AdbKeyState.NotLoaded, src.state)
        val second = src.forPairing()
        assertNotEquals(first.publicKey, second.publicKey)
        assertArrayEquals(second.keyPair.private.encoded, store.bytes)
    }
}
