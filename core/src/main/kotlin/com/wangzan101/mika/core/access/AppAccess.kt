package com.wangzan101.mika.core.access

/** アプリケーション分類はリストを整理するためにのみ使用され、実行権限を決定するものではありません。名前は、インターフェイス言語に従ってアプリ側のリソース (values/strings_appaccess.xml) に配置されます。 */
enum class AppCategory {
    Finance, Social, Game, Shopping, Productivity, Media, Other, Unknown;
}

data class AppClassification(val category: AppCategory, val reason: String = "", val manual: Boolean = false)

data class AppAccessState(
    val blocked: Set<String> = emptySet(),
    val shareAllowed: Set<String> = emptySet(),
    val classifications: Map<String, AppClassification> = emptyMap(),
    val available: Boolean = true,
    val revision: Long = 0,
) {
    fun denial(packageName: String?): AppAccessDenied? = when {
        !available -> AppAccessDenied("app_access_unavailable", null)
        packageName.isNullOrBlank() && blocked.isNotEmpty() -> AppAccessDenied("app_access_unverified", null)
        packageName in blocked -> AppAccessDenied("app_blocked", packageName)
        else -> null
    }

    fun canShare(packageName: String): Boolean = available && (packageName !in blocked || packageName in shareAllowed)
}

/** は毎回最新のステータスを読み取り、タスクの開始時に権限をフリーズしません。 */
fun interface AppAccessPolicy {
    fun current(): AppAccessState

    companion object {
        val ALLOW = AppAccessPolicy { AppAccessState() }
    }
}

class AppAccessDenied(val code: String, val packageName: String?) : RuntimeException(
    when (code) {
        "app_blocked" ->
            "アプリ「$packageName」はユーザーが「アプリのアクセス」で無効にしています。再試行・再読み取りや、シェル・別のアプリによる回避は行わないでください。" +
                "別のアプリ・画面・共有先・ウェブ経路でユーザーの目的を達成できる場合は、その方法で続けてください。" +
                "このアプリが必須なら停止し、「設定 › アプリのアクセス」で無効になっており、そこで許可できることをユーザーに説明してください。このエラーやコードをそのまま繰り返さないでください。"
        "app_access_protected" ->
            "この認証・画面共有の画面は、ユーザー本人だけが操作できます。停止して、この手順をユーザーに依頼してください。ここでタップや入力は行わないでください。"
        "app_access_unavailable" ->
            "アプリのアクセス設定を読み取れないため、権限を確認できません。スマートフォンの操作ツールは使わず、設定から「アプリのアクセス」を修復またはリセットするようユーザーへ案内してください。"
        else ->
            "前面のアプリを確認できないため、この操作は実行しておらず、権限も変更していません。画面を再度読み取ってアプリを確認し、引き続き不明な場合は停止してユーザーに尋ねてください。"
    },
) {
    companion object {
        /** アプリケーションのアクセス拒否は通常の操作の失敗ではありません。モデルに引き渡す必要があり、タスクの例外とみなされません。 */
        fun isAppAccessCode(code: String): Boolean = code == "app_blocked" || code.startsWith("app_access_")
    }
}
