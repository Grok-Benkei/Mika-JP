package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import com.wangzan101.mika.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 2 つの画面読み取りの間隔は少なくともこの長さです。インターフェイス イベントは次々に発生するため、それぞれを読み取る必要はありません。 */
private const val MIN_GAP_MS = 200L
/** ポートがインターフェイス イベントを報告しない場合、そのポートは非​​常に長い間隔の後に再度読み取られます。これは、ユーザーが中断したかどうかを確認する 2 つのチェック間の最長間隔でもあります。 */
private const val POLL_MS = 500L
/** 「インターフェースが安定している」ときにポートにハンドオーバーする回数の上限です（ポートは独自の静止時間に基づいて判断されます）。 */
private const val STABLE_SLICE_MS = 1_500L
/** 予期せぬことに、カウントする前に連続して数回読み取る必要があります (ページが変更されると、古いウィンドウと新しいウィンドウが同時に短時間表示されます)。 */
private const val CONFIRM_READS = 2

/**
 * 一連のアクションの 1 つのステップを完了した後に待機中: [Until] を押して、(番号を登録せずに) 画面を 1 つずつ読み取って、リテラル比較を行います。携帯電話とWebページは同じで、[ScreenPort]と画面読み取り結果のみが使用されます。
 * は、ポート上でインターフェイス イベント ([ScreenPort.awaitChange]) がある場合に早期にウェイクアップします。それ以外の場合は、しばらくしてから再度読み取られます。予想外のことが起こりました（ポップアップボックスが表示されたり、別のバッグカバーが表示されたり、アプリが変更されたり）、
 * 制限時間に達すると、ユーザーは中断し、待機しなくなりました。モデルが判断するために返却されます。参照は、待機が開始される画面です。このステップ自体によって引き起こされるスイッチは、実行エントリによってさらにチェックされます。
 */
internal class UntilWait(
    private val screen: ScreenPort,
    private val read: suspend () -> ScreenSnapshot?,
    private val clock: () -> Long,
    private val interrupted: () -> Boolean,
) {
    suspend fun run(until: Until): UntilOutcome {
        val start = clock()
        val timeout = until.timeoutMs.coerceIn(0, Until.MAX_MS)
        // は、クロックが実行されない場合 (テスト、クロック エラー)、確実に終了します。各ラウンドには少なくとも MIN_GAP_MS 時間がかかります。
        val maxRounds = timeout / MIN_GAP_MS + 2
        var base: ScreenSnapshot? = null
        var stableNow = false
        var suspect: String? = null
        var seen = 0
        fun done(met: Boolean, stopped: String?) = UntilOutcome(met, stopped, (clock() - start).coerceAtLeast(0))
        repeat(maxRounds.toInt()) {
            val snap = read()
            if (snap != null) {
                val first = base ?: snap.also { base = it }
                // 事故が発生した場合、このラウンドは完了しません。ポップアップ ボックスの後ろに表示される文字は、ラウンドを続行できることを意味するものではありません。
                val odd = unexpected(first, snap, until)
                if (odd == null && (!until.stable || stableNow) && holds(until, snap)) return done(true, null)
                if (odd != null && odd == suspect) seen++ else { suspect = odd; seen = if (odd == null) 0 else 1 }
                // ページ ポップアップ ボックスがハングするとページが停止します。確認のためにもう一度読む必要はありません。
                if (suspect != null && (seen >= CONFIRM_READS || snap.web?.dialog != null)) return done(false, suspect)
            }
            if (interrupted()) return done(false, UntilOutcome.NEW_MESSAGE)
            val left = timeout - (clock() - start)
            if (left <= 0) return done(false, UntilOutcome.TIMEOUT)
            // 各待機期間は、ユーザーが中断するとすぐに終了し (waitOrSteer を参照)、次のラウンドまで待機しません。
            if (until.stable) {
                stableNow = waitOrSteer { waitStable(minOf(left, STABLE_SLICE_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)
            } else if (waitOrSteer { awaitChange(minOf(left, POLL_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)) {
                delay(MIN_GAP_MS)
            }
        }
        return done(false, UntilOutcome.TIMEOUT)
    }

    private suspend fun waitStable(ms: Long): Boolean =
        try { screen.waitStable(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    private suspend fun awaitChange(ms: Long): Boolean =
        try { screen.awaitChange(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    companion object {
        /** 現時点で条件が成立しているかどうか (指定された項目のみがチェックされます)。大文字と小文字に関係なく、要素名に基づいて、アクション結果の Expect をチェックするのと同じです。 */
        fun holds(until: Until, s: ScreenSnapshot): Boolean {
            val names = s.elements.map { it.name }
            until.appears?.trim()?.let { want -> if (names.none { it.contains(want, ignoreCase = true) }) return false }
            // 何も読み取られていない（保護されている、読み取れない）は「すでに消えた」とはカウントされません
            until.gone?.trim()?.let { gone -> if (names.isEmpty() || names.any { it.contains(gone, ignoreCase = true) }) return false }
            until.place?.trim()?.let { p -> if (!inPlace(s, p)) return false }
            return true
        }

        private fun inPlace(s: ScreenSnapshot, p: String) =
            s.packageName?.contains(p, ignoreCase = true) == true || s.web?.url?.contains(p, ignoreCase = true) == true

        /**
         * と待機を開始したときの画面 [base] を比較して、現時点での [now] に明らかな驚きはありますか。 no は null です。
         * ページのポップアップ ボックスがハングし、同じアプリに追加のアプリケーション ウィンドウ [UntilOutcome.POPUP] が表示されます。他のパッケージを含む追加のアプリケーションまたはシステム ウィンドウは [UntilOutcome.SYSTEM_DIALOG] です。
         * のフロントエンドは、別のアプリ ([until] が待っているアプリではありません) [UntilOutcome.APP_SWITCHED] に変更されます。 Web サイトの変更は通常のジャンプであり、アプリの変更とはみなされません。
         */
        fun unexpected(base: ScreenSnapshot, now: ScreenSnapshot, until: Until): String? {
            if (now.web?.dialog != null && base.web?.dialog == null) return UntilOutcome.POPUP
            val top = now.packageName
            val foreign = { s: ScreenSnapshot ->
                s.windows.filter { it.type in OVERLAY_TYPES && it.packageName != null && it.packageName != s.packageName }.map { it.id }.toSet()
            }
            if ((foreign(now) - foreign(base)).isNotEmpty()) return UntilOutcome.SYSTEM_DIALOG
            if (now.web == null && top != null && top != base.packageName && until.place?.let { inPlace(now, it.trim()) } != true) return UntilOutcome.APP_SWITCHED
            val own = { s: ScreenSnapshot -> s.windows.count { it.type == APPLICATION && it.packageName == top } }
            if (top == base.packageName && own(now) > own(base)) return UntilOutcome.POPUP
            return null
        }

        private const val APPLICATION = "application"
        private val OVERLAY_TYPES = setOf(APPLICATION, "system")
    }
}
