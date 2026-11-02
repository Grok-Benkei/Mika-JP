package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.record.Redactor
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/**
 * メモリとスキルドラフトの機密情報レベル: ヒットは保存されません。
 * 携帯電話番号、住所、誕生日、家族の名前はここには含まれていません。これらは、物事を成し遂げるために一般的に使用される事実です。
 */
object SensitiveFilter {
    private const val KEYWORDS =
        "支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
            "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|security code|security question|one-time code)(?![A-Za-z])"
    // これら 2 つの単語は短すぎるため、通常の英語 (ピン留め、ホットポット) でよく使われます。後に数字が続く場合にのみカウントされます。 中国語文内の数字の誤判断を避けるため、
    private const val DIGIT_KEYWORDS = "(?<![A-Za-z])(?:pin|otp)(?![A-Za-z])"
    private const val GAP = "[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?"
    private const val OWNER = "\\s*(?:就是|是|为)(?:我的|我|用户的)?\\s*"

    // キーワードと 4 桁を超える数字/文字列の間には短い接続 (「is」、「:」、「is」) のみが許可されます。
    private val KEYWORD_RULES = listOf(
        Regex("(?:$KEYWORDS)$GAP[A-Za-z0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{4,}$OWNER(?:$KEYWORDS)", RegexOption.IGNORE_CASE),
        Regex("$DIGIT_KEYWORDS$GAP[0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[0-9]{4,}$OWNER$DIGIT_KEYWORDS", RegexOption.IGNORE_CASE),
    )
    private val ID_NUMBER = Regex("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])")
    // 番号の間には 1 つのスペースまたはハイフンを使用できます (カード番号はグループで書かれることがよくあります)
    private val CARD_LIKE = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    private val SPACES = Regex("\\s+")
    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    /** はカテゴリ名 (`api_key`|`id_number`|`card_number`|`password`) にヒットします。 null を見逃します。 */
    fun hit(text: String): String? {
        // 全角英数字と互換文字を最初に統一する必要があります。キーワードルールを入力し、空白を削除したテキスト (「検証コード 1 2 3 4」) をチェックします。
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val compact = SPACES.replace(t, "")
        return when {
            Redactor.text(t) != t -> "api_key"
            ID_NUMBER.findAll(t).any { validId(it.value) } -> "id_number"
            CARD_LIKE.findAll(t).any { luhn(it.value.filter(Char::isDigit)) } -> "card_number"
            KEYWORD_RULES.any { it.containsMatchIn(t) || it.containsMatchIn(compact) } -> "password"
            else -> null
        }
    }

    /** 生年月日セグメントは実際の日付で、最後の桁は ISO 7064 MOD 11-2 に準拠しています。 */
    private fun validId(id: String): Boolean {
        val birth = try {
            LocalDate.of(id.substring(6, 10).toInt(), id.substring(10, 12).toInt(), id.substring(12, 14).toInt())
        } catch (e: DateTimeException) {
            return false
        }
        if (birth.year < 1900) return false
        val sum = (0 until 17).sumOf { (id[it] - '0') * ID_WEIGHTS[it] }
        return ID_CHECK[sum % 11] == id[17].uppercaseChar()
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}
