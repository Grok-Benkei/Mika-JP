package com.wangzan101.mika.i18n

import android.content.res.Resources
import com.wangzan101.mika.R
import java.util.Locale
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * インターフェイスのテキストはどこから来たのでしょうか?アプリでは、Android リソース ([ResourceTexts]) がアプリ言語に従ってフェッチされます。 JVM単体テストでは、リソースファイルを直接読み込む実装に置き換えられます。 stringResource が
 * Compose で使用できる場合は、引き続き使用されます。ここでは、インターフェイス内のテキスト (ステータス コピー、通知、サービス、および一時停止されたカプセルのデータ) を計算しないコードに使用されます。
 */
interface UiTexts {
    fun string(@StringRes id: Int, args: Array<out Any?>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/** プロセスで現在使用されている [UiTexts]:App は起動時にロードされます。 */
object UiText {
    @Volatile
    var source: UiTexts? = null

    internal fun require(): UiTexts = source ?: error("UiText.source is not installed")
}

/** はインターフェイス テキストを受け取ります。 [args] がある場合は、形式に従って記入します (Resources.getString と同じ)。 */
fun tr(@StringRes id: Int, vararg args: Any?): String = UiText.require().string(id, args)

/** は、数量に応じて変化するインターフェイス テキストを受け取ります。 [args] が指定されていない場合、[count] が唯一のパラメータとして入力されます。 */
fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = UiText.require().plural(id, count, args)

/** Android リソースの実装。 [resources] は毎回取得され、それに応じて言語が変更されます。 */
class ResourceTexts(private val resources: () -> Resources) : UiTexts {
    override fun string(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) resources().getString(id) else resources().getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        resources().getQuantityString(id, count, *(if (args.isEmpty()) arrayOf(count) else args))
}

/** インターフェース言語に対応するロケール: それに応じて週と月の名前を記述します。 */
fun uiLocale(): Locale = Locale.forLanguageTag(tr(R.string.ui_locale))
