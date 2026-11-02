package com.wangzan101.mika.core.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) およびコールバック検証に使用されるランダム値。ログインするたびに新たに生成され、保存されません。
 * チェック文字列は、base64url を使用して 32 ランダム バイト (仕様で要求される 43 ～ 128 の範囲にある 43 文字、また文字は許容範囲内にあります) を書き込みます。
 */
object Pkce {
    private val random = SecureRandom()

    /** 新しい検証文字列 (code_verifier)。 */
    fun verifier(bytes: Int = 32): String = randomString(bytes)

    /** S256: チェック文字列の SHA-256、パディングなしのbase64url。 */
    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** コールバックで変更されずに戻されたランダムな文字列 (状態) は、これがログインであるかどうかを識別するために使用されます。 */
    fun state(): String = randomString(24)

    private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
