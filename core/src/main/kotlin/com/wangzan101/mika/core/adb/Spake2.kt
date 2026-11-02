package com.wangzan101.mika.core.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 バリアント (edwards25519 + SHA-512)。デバイス上の実装とバイトごとに一貫性がある必要があります。
 * - 秘密キー = (64 バイトの乱数 mod L) × 8、補数を掛けます。
 * - パスワード スカラー = SHA-512 (パスワード) mod L、次に下 3 桁を押し、L、2L、4L を順に追加して 8 の倍数にします (デバイス側の履歴修正と一致します)。
 * - P* = 秘密鍵・B + パスワード・スカラー・(パーティ A は M を使用、パーティ B は N を使用) を送信します。
 * - 共有キー = SHA-512 (8 バイトのリトルエンディアン長プレフィックスを持つ各セグメント: 名前 A、名前 B、メッセージ A、メッセージ B、秘密キー · (Q* − パスワード スカラー · 相手側定数)、SHA-512 (パスワード))。
 * M と N は公開シード文字列から推定されます。[SEED_M]/[SEED_N] を参照してください。再導出してテストにチェックインします。
 */
class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    enum class Role { ALICE, BOB }

    private var privateKey: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null
    private var done = false

    fun generateMessage(password: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        generateMessage(password, ByteArray(64).also { random.nextBytes(it) })

    /** [seed] は 64 バイトの乱数です。テストは固定値でのみ合格します。 */
    internal fun generateMessage(password: ByteArray, seed: ByteArray): ByteArray {
        check(myMsg == null) { "message already generated" }
        require(seed.size == 64)
        val priv = EdPoint.fromLe(seed).mod(EdPoint.L).shiftLeft(3)
        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        var s = EdPoint.fromLe(hash).mod(EdPoint.L)
        if (s.testBit(0)) s += EdPoint.L
        if (s.testBit(1)) s += EdPoint.L.shiftLeft(1)
        if (s.testBit(2)) s += EdPoint.L.shiftLeft(2)
        val mask = (if (role == Role.ALICE) M else N).times(s)
        val msg = (EdPoint.BASE.times(priv) + mask).encode()
        privateKey = priv
        passwordScalar = s
        passwordHash = hash
        myMsg = msg
        return msg.copyOf()
    }

    /** は 64 バイトの共有キーを返します。相手のポイントがカーブ上にない場合は[IllegalArgumentException]を投げます。 */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        val msg = checkNotNull(myMsg) { "generateMessage first" }
        check(!done) { "already processed" }
        require(theirMsg.size == 32) { "peer message must be 32 bytes" }
        val qStar = requireNotNull(EdPoint.decode(theirMsg)) { "peer point not on curve" }
        val peerMask = (if (role == Role.ALICE) N else M).times(passwordScalar!!)
        val shared = (qStar - peerMask).times(privateKey!!).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            val len = ByteArray(8)
            var l = b.size.toLong()
            for (i in 0 until 8) { len[i] = (l and 0xff).toByte(); l = l ushr 8 }
            sha.update(len)
            sha.update(b)
        }
        if (role == Role.ALICE) {
            put(myName); put(theirName); put(msg); put(theirMsg)
        } else {
            put(theirName); put(myName); put(theirMsg); put(msg)
        }
        put(shared)
        put(passwordHash!!)
        done = true
        return sha.digest()
    }

    companion object {
        const val SEED_M = "edwards25519 point generation seed (M)"
        const val SEED_N = "edwards25519 point generation seed (N)"
        internal val M = requireNotNull(EdPoint.decode(EdPoint.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        internal val N = requireNotNull(EdPoint.decode(EdPoint.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))

        /** シード文字列から定数点: SHA-256 (シード) を推定します。ポイントを解決できない場合は、サマリーから曲線に該当するまで SHA-256 を使用します。 */
        internal fun derivePoint(seed: String): EdPoint {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
            while (true) {
                EdPoint.decode(v)?.let { return it }
                v = MessageDigest.getInstance("SHA-256").digest(v)
            }
        }
    }
}
