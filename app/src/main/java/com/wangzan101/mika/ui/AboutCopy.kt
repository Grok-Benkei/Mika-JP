package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.UpdateState

/** 概要ページ、「アップデートの確認」行、設定ホームページの「ミカについて」行、サイドバー入口のプロンプトのテキストを表示する方法。 */
object AboutCopy {
    val TITLE: String get() = tr(R.string.settings_about)
    val TAGLINE: String get() = tr(R.string.about_tagline)
    const val BETA = "Beta"

    val CHECK: String get() = tr(R.string.about_check)
    val CHECK_SUB: String get() = tr(R.string.about_check_sub)
    val CHECK_SUB_NEW: String get() = tr(R.string.about_check_sub_new)
    val UP_TO_DATE: String get() = tr(R.string.about_up_to_date)
    val UP_TO_DATE_TOAST: String get() = tr(R.string.about_up_to_date_toast)
    val OFFLINE: String get() = tr(R.string.about_offline)
    val OFFLINE_TOAST: String get() = tr(R.string.about_offline_toast)
    val CHANGELOG: String get() = tr(R.string.about_changelog)
    val CHANGELOG_SUB: String get() = tr(R.string.about_changelog_sub)
    val CHANGELOG_EMPTY: String get() = tr(R.string.about_changelog_empty)
    val CHANGELOG_ALL: String get() = tr(R.string.about_changelog_all)

    val FEEDBACK: String get() = tr(R.string.about_feedback)
    val FEEDBACK_SUB: String get() = tr(R.string.about_feedback_sub)
    val FEEDBACK_SHEET_SUB: String get() = tr(R.string.about_feedback_sheet_sub)
    val FEEDBACK_GITHUB: String get() = tr(R.string.about_feedback_github)
    val FEEDBACK_GITHUB_SUB: String get() = tr(R.string.about_feedback_github_sub)
    val FEEDBACK_GROUP: String get() = tr(R.string.about_feedback_group)

    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val DIAGNOSTICS_SUB: String get() = tr(R.string.diagnostics_copy_sub)
    val DIAGNOSTICS_TITLE: String get() = tr(R.string.diagnostics_title)
    val DIAGNOSTICS_NOTE: String get() = tr(R.string.diagnostics_note)
    val DIAGNOSTICS_COPY: String get() = tr(R.string.diagnostics_copy_button)
    val DIAGNOSTICS_SHARE: String get() = tr(R.string.diagnostics_share)
    val DIAGNOSTICS_COPIED: String get() = tr(R.string.diagnostics_copied)
    val DIAGNOSTICS_LOADING: String get() = tr(R.string.diagnostics_loading)

    val GROUP: String get() = tr(R.string.about_group)
    val GROUP_SUB: String get() = tr(R.string.about_group_sub)
    val GROUP_SHEET_TITLE: String get() = tr(R.string.about_group_sheet_title)
    val GROUP_SHEET_SUB: String get() = tr(R.string.about_group_sheet_sub)
    val GROUP_HINT: String get() = tr(R.string.about_group_hint)
    val SAVE_QR: String get() = tr(R.string.about_save_qr)
    val QR_SAVED: String get() = tr(R.string.about_qr_saved)
    val QR_SAVE_FAILED: String get() = tr(R.string.about_qr_save_failed)
    val GROUP_NO_WECHAT: String get() = tr(R.string.about_group_no_wechat)
    val GROUP_JOIN_X: String get() = tr(R.string.about_group_join_x)

    /** インターフェイスが英語の場合、コミュニケーション グループ パネルでは X グループ チャットが最初に配置され、次に WeChat グループ コードが配置されます。中国語のインターフェースでは、WeChat が最初に表示されます。システム言語に関係なく、アプリで実際に表示される言語 (リソースの ui_locale) に基づいて計算します。 */
    val X_GROUP_FIRST: Boolean get() = uiLocale().language != "zh"

    val FOLLOW: String get() = tr(R.string.about_follow)
    val FOLLOW_SUB: String get() = tr(R.string.about_follow_sub, MikaLinks.X_HANDLE)
    val REPO: String get() = tr(R.string.about_repo)
    val REPO_SUB: String get() = tr(R.string.about_repo_sub, MikaLinks.REPO_LABEL)
    val GUIDE: String get() = tr(R.string.about_guide)
    val GUIDE_SUB: String get() = tr(R.string.about_guide_sub)

    val DATA_NOTE: String get() = tr(R.string.about_data_note)
    val USAGE_TITLE: String get() = tr(R.string.about_usage_title)
    val USAGE_NOTE: String get() = tr(R.string.about_usage_note)
    val LICENSE_LINK: String get() = tr(R.string.about_license_link)
    val AUTHOR: String get() = tr(R.string.about_author)
    val LICENSE_TITLE: String get() = tr(R.string.about_license_title)

    val UPDATE_DOWNLOAD: String get() = tr(R.string.about_update_download)
    val UPDATE_LATER: String get() = tr(R.string.about_update_later)
    val NO_BROWSER: String get() = tr(R.string.about_no_browser)

    fun version(versionName: String): String = tr(R.string.about_version, versionName)

    fun buildLabel(build: String): String = tr(R.string.about_build, build)

    fun available(version: String): String = tr(R.string.about_available, version)
    fun updateTitle(version: String): String = tr(R.string.about_update_title, version)

    /** 「現在の 0.1.0 ベータ版 · 2026 年 11 月 5 日にリリースされました」;リリース日が不明な場合にのみ、現在のバージョンを書き込みます。 */
    fun updateSub(current: String, beta: Boolean, date: String?): String =
        tr(R.string.about_current, current + if (beta) " $BETA" else "") + (date?.let { tr(R.string.about_released_on, it) } ?: "")

    /** 「アップデートの確認」行の右側: 円を確認し、新しいバージョンがある場合は、赤い点でバージョン番号を書き込み、一度確認して結果を書き込みます。確認したことがない場合は、何も書き込まないでください。 */
    data class CheckValue(val text: String?, val dot: Boolean = false, val spinner: Boolean = false, val highlight: Boolean = false)

    fun checkValue(s: UpdateState): CheckValue = when {
        s.status == CheckStatus.Checking -> CheckValue(null, spinner = true)
        s.update != null -> CheckValue(available(s.update.version), dot = true, highlight = true)
        s.status == CheckStatus.Failed -> CheckValue(OFFLINE)
        s.latest != null -> CheckValue(UP_TO_DATE)
        else -> CheckValue(null)
    }

    /** ホームページの「ミカについて」の行の右側に、新しいバージョンがある場合は「×は更新可能」と赤い点で書き込み、そうでない場合は現在のバージョン番号を書き込みます。 */
    fun settingsValue(updateVersion: String?, versionName: String): CheckValue =
        if (updateVersion != null) CheckValue(available(updateVersion), dot = true, highlight = true)
        else CheckValue(versionName.takeIf { it.isNotEmpty() })

    /** 「ミカについて」サイドバーの赤い点。 下部パネルで「 */
    fun drawerDot(s: UpdateState): Boolean = s.hasUpdate
}
