package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** 日本語版の外部リンクと更新APIを一元管理する。 */
object MikaLinks {
    const val REPO = "https://github.com/yebisu0529-ship-it/Mika-JP"
    const val REPO_LABEL = "github.com/yebisu0529-ship-it/Mika-JP"
    const val UPSTREAM = "https://github.com/wangzan101/Mika"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/studio_yebisu"
    const val X_HANDLE = "@studio_yebisu"

    /** フォークの問い合わせ先。既存の呼び出し元と互換のため定数名を保持する。 */
    const val X_GROUP = "$REPO/issues"

    /** GitHub リリース ページでのみ更新を確認してください (ログイン不要、1 時間あたりの回数に制限があります)。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/yebisu0529-ship-it/Mika-JP/releases/latest"

    /** フィードバックを提供するときに、問題テンプレートのデバイスとバージョン情報を事前に入力します。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 事前入力された質問テキスト: モデル、Android バージョン、Mika バージョンの 3 行。ユーザーが書き込めるように、以下にいくつかの空白の段落を残しておきます。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新しい質問のアドレスを作成し、タイトルを空白のままにし、テキストを事前に入力します。パラメータは URL エンコードされています (スペースは %20 として書き込まれます)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
