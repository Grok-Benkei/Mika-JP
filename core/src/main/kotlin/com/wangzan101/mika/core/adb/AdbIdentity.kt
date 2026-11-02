package com.wangzan101.mika.core.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ミカ自身の adb ID: RSA-2048 キー + 自己署名証明書。ペアリング時にデバイスに[adbPublicKey]を与え、
 * の後、TLS 接続ごとに [certificate] が表示されます (デバイスは証明書内の公開キーのみを比較します)。
 */
class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /**
     * デバイス キーストアの行:base64 (524 バイトの公開キー構造) + スペース + 名前。
     * 名前に空白を含めることはできません。システム設定により、この行は空白に応じて分割されます。名前にスペースが含まれている場合は、「ペアリングされたデバイス」から削除できません。
     */
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(AdbPublicKey.encode(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        /** 新しいキーの公開インデックス: F4 (65537)。キーを読み戻すときにインデックスを取得できない場合は、キーを押してインデックスを入力し、それが正しいことを確認します。 */
        val PUBLIC_EXPONENT: BigInteger = RSAKeyGenParameterSpec.F4

        fun generate(name: String): AdbIdentity {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, PUBLIC_EXPONENT))
            return AdbIdentity(g.generateKeyPair(), name)
        }

        /**
         * 保存された PKCS#8 から ID を読み取ります。プラットフォームは、CRT パラメーターを含む秘密キーを提供する必要はありません。RSA 秘密キーである限り、モジュラスを取得するだけです。
         * 公開インデックスは [publicExponent] によって見つかりました。スロー [AdbKeyUnreadableException] を読み取れません。
         */
        fun fromPkcs8(pkcs8: ByteArray, name: String): AdbIdentity {
            val kf = KeyFactory.getInstance("RSA")
            val priv = try {
                kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw AdbKeyUnreadableException("stored key is not valid PKCS#8", e)
            }
            val rsa = priv as? RSAPrivateKey ?: throw AdbKeyUnreadableException("stored key is not RSA (${priv.javaClass.simpleName})")
            val pub = kf.generatePublic(RSAPublicKeySpec(rsa.modulus, publicExponent(rsa, pkcs8))) as RSAPublicKey
            return AdbIdentity(KeyPair(pub, rsa), name)
        }

        /**
         * 秘密キーに対応する公開インデックス: プラットフォームが CRT 形式を提供する場合は直接使用します。それ以外の場合は、PKCS#8 の RSAPrivateKey 構造体から読み取ります。 構造内に
         * がない場合 (0 として書き込まれます)、[PUBLIC_EXPONENT] を押して埋めます。入力する前に、署名と検証を 1 回行ってください。合わなかったら捨ててください。接続に間違った公開キーを使用しないでください。
         */
        internal fun publicExponent(key: RSAPrivateKey, pkcs8: ByteArray): BigInteger {
            (key as? RSAPrivateCrtKey)?.publicExponent?.takeIf { it.signum() > 0 }?.let { return it }
            Pkcs8Rsa.publicExponent(pkcs8)?.takeIf { it.signum() > 0 }?.let { return it }
            if (!signsWith(key, PUBLIC_EXPONENT)) throw AdbKeyUnreadableException("cannot recover the public exponent of the stored key")
            return PUBLIC_EXPONENT
        }

        private fun signsWith(key: RSAPrivateKey, e: BigInteger): Boolean = runCatching {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, e))
            val data = "mika-adb-key-check".toByteArray()
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data); sign() }
            Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(data); verify(sig) }
        }.getOrDefault(false)
    }
}

/** 保存されたキーは存在しますが、読み取ることができません (ロック解除または解析できません)。静かに新しいものと交換することはできません。デバイスはペアリングされたもののみを認識します。 */
class AdbKeyUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** キーの現在のステータス (診断情報用)。 */
enum class AdbKeyState {
    /** キーはこのプロセスでは使用されていません。 */
    NotLoaded,
    /** は保存したものを読み戻します。 以前は */
    Loaded,
    /** がなかったので（もしくはペアリング時に読み取れなくなったものを差し替えた）、新しく作成して保存しました。 */
    Created,
    /** 持っていますが、読み取れません。再ペアリングする必要があります。 */
    Unreadable,
}

/**
 * プロセスには adb ID が 1 つだけあります。ペアリングと接続は同じキーを取得する必要があり、特定の読み取り失敗のために異なるキーを使用することはありません。
 * ペアリング ([forPairing]) のみが、保存されたキーを読み取れない場合に新しいキーに置き換えます (ペアリングは元々デバイスに新しいキーを与えます)。
 * 接続 ([forConnect]) は、読み取れない場合に [AdbKeyUnreadableException] をスローし、それに応じてインターフェイスはユーザーに再ペアリングを要求します。
 */
class AdbIdentitySource(
    private val store: AdbKeyStore,
    private val name: () -> String,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private var cached: AdbIdentity? = null

    @Volatile var state: AdbKeyState = AdbKeyState.NotLoaded
        private set

    /** 最新を読み取れない理由 (診断情報用)。読み取れる場合は null です。 */
    @Volatile var problem: String? = null
        private set

    /** 接続に使用: 保存されているもの。存在しない場合は、新しいものを作成して保存します。保存しても読み取れない場合は、上書きせずに破棄してください。 ペアリング用の */
    @Synchronized
    fun forConnect(): AdbIdentity = cached ?: load()

    /** : [forConnect]と同じもの。保存したものだけが読み取れない場合は、新しいものに置き換えてここに保存します（その後のペアリングでデバイスに保存されます）。 */
    @Synchronized
    fun forPairing(): AdbIdentity = cached ?: try {
        load()
    } catch (e: AdbKeyUnreadableException) {
        log("replacing the unreadable adb key for a new pairing", e)
        store.clear()
        create()
    }

    /** ペアリング解除: キーとキャッシュは一緒に削除され、次のペアリングには新しいキーが使用されます。 */
    @Synchronized
    fun forget() {
        cached = null
        store.clear()
        state = AdbKeyState.NotLoaded
        problem = null
    }

    private fun load(): AdbIdentity {
        val bytes = try {
            store.read()
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        } ?: return create()
        val id = try {
            AdbIdentity.fromPkcs8(bytes, name())
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        }
        state = AdbKeyState.Loaded
        problem = null
        return id.also { cached = it }
    }

    private fun create(): AdbIdentity {
        val id = AdbIdentity.generate(name())
        store.write(id.keyPair.private.encoded)
        state = AdbKeyState.Created
        problem = null
        return id.also { cached = it }
    }

    private fun unreadable(e: AdbKeyUnreadableException): AdbKeyUnreadableException {
        state = AdbKeyState.Unreadable
        problem = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: it.javaClass.simpleName }.take(200)
        log("stored adb key is unreadable; pair again to replace it", e)
        return e
    }
}

/**
 * キー (PKCS#8) へのアクセス。アプリは Android キーストアを使用して、暗号化されたストレージのレイヤーをラップします。
 * [read] が保存されていない場合は、null が返されます。保存されていても読み取れない場合は、[AdbKeyUnreadableException] がスローされます (2 つは分離する必要があり、後者は存在しないものとして扱うことはできません)。
 */
interface AdbKeyStore {
    fun read(): ByteArray?
    fun write(pkcs8: ByteArray)
    fun clear()
}

/**
 * は、ファイルに保存されているキーを暗号化します: ファイルの内容 = 1 バイトの IV 長 + IV + AES-GCM 暗号文。
 * IV は Cipher 自体によって生成されます (Android キーストアのキーでは呼び出し元が IV を指定することはできません)。
 */
class WrappedKeyFile(private val file: File, private val wrappingKey: () -> SecretKey) : AdbKeyStore {
    override fun read(): ByteArray? {
        if (!file.isFile) return null
        return try {
            val b = file.readBytes()
            require(b.isNotEmpty()) { "key file is empty" }
            val ivLen = b[0].toInt() and 0xff
            require(b.size > 1 + ivLen) { "key file is truncated" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        } catch (e: Exception) {
            throw AdbKeyUnreadableException("cannot unwrap ${file.name}", e)
        }
    }

    override fun write(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(pkcs8)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "cannot write ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * PKCS#8:PrivateKeyInfo から RSA 公開インデックスを読み取ります::= SEQUENCE { バージョン、アルゴリズム、privateKey OCTET STRING }、
 * privateKey は RSAPrivateKey ::= SEQUENCE { バージョン、モジュラス、publicExponent、... } です。構造が正しくない場合は null を返します。
 */
internal object Pkcs8Rsa {
    fun publicExponent(pkcs8: ByteArray): BigInteger? = runCatching {
        val info = Der(pkcs8).read(0x30)
        val inner = Der(info).apply { read(0x02); read(0x30) }.read(0x04)
        val rsa = Der(Der(inner).read(0x30))
        rsa.read(0x02)
        rsa.read(0x02)
        BigInteger(rsa.read(0x02))
    }.getOrNull()

    /** は、DER の上位層を読み取るのにのみ十分です。つまり、指定されたタグの内容を順番に取得します。 */
    private class Der(private val b: ByteArray) {
        private var pos = 0

        fun read(tag: Int): ByteArray {
            require((b[pos++].toInt() and 0xff) == tag) { "unexpected tag" }
            var len = b[pos++].toInt() and 0xff
            if (len >= 0x80) {
                val n = len and 0x7f
                require(n in 1..3) { "unsupported length" }
                len = 0
                repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xff) }
            }
            require(pos + len <= b.size) { "truncated" }
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}

/**
 * adb の RSA 公開キー バイナリ形式 (すべてリトル エンディアン、合計 524 バイト):
 * モジュロ桁 (64)、n0inv = −1/n mod 2^32、モジュラス n、rr = (2^2048)^2 mod n、公開指数。
 */
object AdbPublicKey {
    private const val WORDS = 64
    const val SIZE = 4 + 4 + WORDS * 4 * 2 + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(EdPoint.toLe(n, 256))
        buf.put(EdPoint.toLe(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}

/** 最小限の自己署名 X.509 v3 証明書 (拡張子なし): CN=Mika、1970-01-01 ～ 2099-12-31、SHA256withRSA。 */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(keyPair: KeyPair): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "Mika".toByteArray()))))
        val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            algId, name, validity, name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
