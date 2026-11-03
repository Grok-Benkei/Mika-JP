package com.wangzan101.mika.core.selfcheck

import java.net.URLEncoder
import java.util.Locale

/**
 * 互換性診断を日本語のプレーンテキストにまとめる。版・端末情報に続き、各項目を「番号 表示名: 結果 — 事実」の1行で示す。
 * [DeviceFacts]と各項目の[CheckResult.detail]だけを記載し、アカウント、アプリ一覧、ユーザーの内容、シリアル番号は含めない。
 * 項目ごとの事実を1行に整えて長さを制限し、最後に全体を[sanitize]へ渡す。アプリでは診断情報のコピーと同じ匿名化処理を使う。
 */
object SelfCheckReport {
    const val HEADER = "Mika 互換性診断"
    private const val MAX_DETAIL = 200

    fun render(state: SelfCheckState, sanitize: (String) -> String = { it }): String = sanitize(buildString {
        val f = state.facts
        appendLine("$HEADER ${f?.appVersion ?: "?"}（ビルド ${f?.appBuild ?: "?"}）")
        if (f != null) {
            appendLine("端末: ${f.modelName}, Android ${f.androidRelease}（SDK ${f.sdk}）, ${f.abis.firstOrNull() ?: "ABI不明"}, RAM ${gb(f.ramBytes)}, ${f.screenWidth}x${f.screenHeight}")
            appendLine("ワイヤレスデバッグ: ${when (f.wirelessDebugging) { true -> "あり"; false -> "なし"; null -> "不明" }}")
            f.developerOptionsPage?.let { appendLine("開発者向けオプション: ${if (it) "あり" else "なし"}") }
            if (f.phoneControlUnavailable) appendLine("スマートフォン操作: この環境では利用できません")
        } else {
            appendLine("端末: 不明")
        }
        appendLine(summary(state))
        appendLine()
        for (r in state.results) appendLine(line(r))
    }.trimEnd() + "\n")

    /** One item per line: `8 stays alive: FAIL — frames stopped after 6 s`. */
    fun line(r: CheckResult): String {
        val detail = oneLine(r.detail)
        return "${r.check.number} ${r.check.displayName}: ${label(r.status)}" + if (detail.isEmpty()) "" else " — $detail"
    }

    fun label(s: CheckStatus): String = when (s) {
        CheckStatus.Pending -> "未実行"
        CheckStatus.Running -> "実行中"
        CheckStatus.Pass -> "正常"
        CheckStatus.Partial -> "一部利用可能"
        CheckStatus.Fail -> "失敗"
        CheckStatus.Skipped -> "スキップ"
    }

    private fun summary(s: SelfCheckState): String {
        val n = s.results.groupingBy { it.status }.eachCount()
        fun c(st: CheckStatus) = n[st] ?: 0
        val counts = "正常 ${c(CheckStatus.Pass)} 件、一部利用可能 ${c(CheckStatus.Partial)} 件、失敗 ${c(CheckStatus.Fail)} 件、スキップ ${c(CheckStatus.Skipped)} 件"
        val secs = (s.elapsedMs + 500) / 1000
        return if (s.phase == SelfCheckState.Phase.Cancelled) "結果: $secs 秒でキャンセルしました。$counts" else "結果: $counts（$secs 秒）"
    }

    private fun oneLine(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_DETAIL) flat else flat.take(MAX_DETAIL - 1) + "…"
    }

    private fun gb(bytes: Long): String =
        if (bytes <= 0) "不明" else String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    /** フィードバックの質問のタイトルは `[互換性] Android <バージョン> <モデル名>` で、モデルはシステムによって指定されたものです (メーカー名は追加されません)。 */
    fun issueTitle(f: DeviceFacts?): String =
        if (f == null) "[互換性]" else "[互換性] Android ${f.androidRelease} ${f.model.trim()}".trim()

    /**
     * 新しい質問アドレスを作成します。デバイス フィードバック テンプレートを選択し、タイトルのみを事前入力します。レポートは長すぎるため、アドレスに入力されず (アドレスには長さの上限があります)、ユーザーによって貼り付けられます。
     * [newIssue] は、ウェアハウスの新しい問題アドレスです。
     */
    fun issueUrl(newIssue: String, f: DeviceFacts?): String =
        "$newIssue?template=$ISSUE_TEMPLATE&title=${encode(issueTitle(f))}"

    const val ISSUE_TEMPLATE = "device-report.yml"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
