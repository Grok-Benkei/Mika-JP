package com.wangzan101.mika.core.tool

/**
 * 一連のアクションの特定のステップを完了した後、次のステップを実行するまで待つ必要があります。いくつかの項目を同時に確立する必要があります。
 * [appears] 画面上の要素の名前にこの文字が含まれています。 [gone] この文字を含む要素は画面上にありません。 [place] フロントエンド アプリのパッケージ名 (携帯電話) またはページ アドレス、サイト (Web ページ) にこの文字が含まれています。
 * [stable] インターフェースが停止し、変化しません。最も待機しているのは [timeoutMs] です。
 */
data class Until(
    val appears: String? = null,
    val gone: String? = null,
    val place: String? = null,
    val stable: Boolean = false,
    val timeoutMs: Long = DEFAULT_MS,
) {
    companion object {
        const val DEFAULT_MS = 8_000L
        const val MAX_MS = 20_000L
    }
}

/**
 * 待機終了：[met] 条件成立時；それが確立されていない場合、[stopped] は、もう待たない理由を説明します - [TIMEOUT] 制限時間に達したか、事故が事前に発生していることがわかります。
 * [POPUP] 同じアプリに追加のウィンドウ (ポップアップ ボックス) またはページ ポップアップ ボックスがあります。 [SYSTEM_DIALOG] 他のパッケージのウィンドウがカバーされます (権限、システム ダイアログ ボックス)。
 * [APP_SWITCHED] フロントデスクが別のアプリに変更され、[NEW_MESSAGE] ユーザーが中断しました。 [waitedMs] は待機時間です。
 */
data class UntilOutcome(val met: Boolean, val stopped: String?, val waitedMs: Long) {
    companion object {
        const val TIMEOUT = "timeout"
        const val POPUP = "popup"
        const val SYSTEM_DIALOG = "system_dialog"
        const val APP_SWITCHED = "app_switched"
        const val NEW_MESSAGE = "new_message"
    }
}
