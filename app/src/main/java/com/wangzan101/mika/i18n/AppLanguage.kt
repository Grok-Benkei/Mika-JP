package com.wangzan101.mika.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 日本語専用フォークの言語ポリシー。
 *
 * 旧版の列挙値とタグは、保存済み設定やテストとの互換のために残す。
 * ただし実行時の言語は常に日本語であり、保存値・システム言語・外部からの
 * ロケール指定で英語、中国語、その他へ切り替わることはない。
 * 設定画面でも日本語以外を選択肢として提示しない。
 */
enum class AppLanguage(val tag: String?, val speechTag: String?) {
    System(null, null),
    SimplifiedChinese("zh-Hans", "zh-CN"),
    TraditionalChinese("zh-Hant", "zh-TW"),
    English("en", "en-US"),
    Japanese("ja", "ja-JP"),
    Korean("ko", "ko-KR"),
    Spanish("es", "es-ES"),
    Portuguese("pt", "pt-BR"),
    Russian("ru", "ru-RU"),
    Indonesian("id", "id-ID");

    /** 互換用の言語コード。実行時ポリシーは常に日本語。 */
    val language: String? get() = tag?.let { Locale.forLanguageTag(it).language.let(::modernCode) }

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"
        private const val KEY_DEFAULT_INITIALIZED = "jp_default_initialized"

        /** このフォークで唯一使うロケール。 */
        val JAPANESE_LOCALE: Locale = Locale.forLanguageTag("ja-JP")
        private val JAPANESE_LOCALES: LocaleList get() = LocaleList.forLanguageTags("ja")

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /** 起動時に呼ぶ。以前の選択が何であっても日本語へ寄せ直す。 */
        fun initializeDefault(context: Context) {
            enforceJapanese(context)
        }

        /** 日本語をシステムのアプリ言語、保存値、プロセスの既定ロケールへ反映する。 */
        fun enforceJapanese(context: Context) {
            val app = context.applicationContext
            Locale.setDefault(JAPANESE_LOCALE)
            if (systemManaged) {
                runCatching {
                    app.getSystemService(LocaleManager::class.java).applicationLocales = JAPANESE_LOCALES
                }
            }
            runCatching {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TAG, Japanese.tag)
                    .putBoolean(KEY_DEFAULT_INITIALIZED, true)
                    .commit()
            }
        }

        /** 現在の言語は常に日本語。保存値やシステム言語は見ない。 */
        fun current(context: Context): AppLanguage = Japanese

        /** どのタグが来ても日本語。旧保存値の読み替え用に残す。 */
        fun fromTag(tag: String?): AppLanguage = Japanese

        /** 互換用オーバーロード。スクリプト推定は行わない。 */
        internal fun fromTag(tag: String?, likelyScript: (String) -> String): AppLanguage = Japanese

        internal fun modernCode(language: String): String = when (language) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> language
        }

        /**
         * 言語変更要求。日本語以外は受け付けず、常に日本語を適用する。
         * 旧呼び出し元が英語やシステム追従を渡しても表示は日本語になる。
         */
        fun set(context: Context, language: AppLanguage) {
            enforceJapanese(context)
            if (!systemManaged) context.activity()?.recreate()
        }

        /** 構成変更で更新されたプロセスの既定ロケールを日本語に戻す。 */
        fun applyDefaultLocale(context: Context) {
            Locale.setDefault(JAPANESE_LOCALE)
        }

        /** 日本語を固定し、Activity の構成変更とベース Context の独自設定を保持する。 */
        fun wrap(base: Context): Context {
            val original = Configuration(base.resources.configuration)
            if (systemManaged && original.locales[0]?.language == Japanese.tag) return base

            // Activity の表示設定は token 経由で継承される。全体コピーを常用すると、
            // 回転やマルチウィンドウの変更後にも起動時の表示設定を上書きしてしまう。
            val localeOnly = Configuration().apply { setLocales(JAPANESE_LOCALES) }
            val localized = base.createConfigurationContext(localeOnly)
            val expected = Configuration(original).apply { setLocales(JAPANESE_LOCALES) }
            if (localized.resources.configuration.diff(expected) == 0) return localized

            // createConfigurationContext は、すでに構成済みの Context の独自 override を
            // 引き継がない。その場合だけ元の表示設定も渡して欠落を防ぐ。
            return base.createConfigurationContext(expected)
        }

        /** UI 以外が文字を取るためのリソース。表示構成のスナップショットはキャッシュしない。 */
        fun resources(app: Context): Resources = wrap(app).resources

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
