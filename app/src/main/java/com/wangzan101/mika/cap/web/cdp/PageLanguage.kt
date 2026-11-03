package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Web ページがリクエストされる言語。ユーザーが Mega でインターフェース言語を選択すると、Web ページもこの要件に従います (Accept-Language リクエスト ヘッダー、
 * カーネルでは、navigator. language もそれに従うことができます)。システムに従っているときは移動せず、カーネルはシステムによって指定された文字列を送信します。
 * は、`Emulation.setUserAgentOverride` の acceptLanguage によって設定されます。デスクトップ バージョンはデスクトップ ID ([withLanguage]) に追加されます。
 * 携帯電話バージョンは、デフォルトの携帯電話 ID をコピーし、言語範囲のみを追加するバージョン ([mobileOverride]) に置き換える必要があります。 UA と UA-CH は変更されません。
 * JS の Intl 形式は変更されず (`Emulation.setLocaleOverride` はレンダリング プロセス全体のコピーであり、各ラベルは急いで設定され、ラベルは切断されると削除されます)、システムは引き続き動作します。
 */
object PageLanguage {
    /**
     * 言語タグに対応する言語リスト (en-Latn-US などの完全版): 地域は「言語-地域、言語」と書かれています。
     * 書記体系が書かれていません (多くの Web サイトは「言語地域」のみを認識します)。地域がない場合は、言語のみが記述されます。他の言語は後で追加されません。
     * はリストのみを提供し、q 値を書き込みません。カーネルはこのリストに従って navigator.langages を設定し、q 値をリクエスト ヘッダーに 1 つずつ追加します。書かれてしまうと繰り返してしまいます。
     * インドネシア語およびその他の言語の Android のロケールは古いコード (in) ですが、Web サイトは現在のコード (id) を認識するため、書き込む前に変更してください。
     */
    fun acceptLanguage(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = AppLanguage.modernCode(l.language.ifEmpty { return tag })
        return if (l.country.isEmpty()) lang else "$lang-${l.country},$lang"
    }

    /** は UA の対象範囲に文言を追加します。 [acceptLanguage] は、null の場合は変更されずに返されます (システムに従って)。 */
    fun withLanguage(override: JsonObject, acceptLanguage: String?): JsonObject =
        if (acceptLanguage == null) override else JsonObject(override + ("acceptLanguage" to JsonPrimitive(acceptLanguage)))

    /**
     * モバイル バージョンの `Emulation.setUserAgentOverride` パラメーター。システムに従う場合、UA は空です。つまり、オーバーライドがキャンセルされ、アイデンティティは WebView 設定のデフォルトのアイデンティティに戻ります。
     * は、言語が選択されたときにデフォルトの ID をコピーします。[defaultUa] は WebView のデフォルトの UA で、[metadata] は設定を変更する前に取得されたデフォルトの UA-CH (CDP など) です。
     * システムがデフォルトの UA-CH (旧 WebView) を取得できない場合は、UA のみが提供されます。このとき、ページ上で読み取られた UA-CH が不完全である可能性があります。
     */
    fun mobileOverride(defaultUa: String, metadata: JsonObject?, acceptLanguage: String?): JsonObject = buildJsonObject {
        if (acceptLanguage == null || defaultUa.isEmpty()) {
            put("userAgent", "")
            return@buildJsonObject
        }
        put("userAgent", defaultUa)
        put("acceptLanguage", acceptLanguage)
        metadata?.let { put("userAgentMetadata", it) }
    }
}
