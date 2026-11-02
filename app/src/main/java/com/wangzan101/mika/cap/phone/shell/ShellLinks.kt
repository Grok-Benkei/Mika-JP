package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.tool.LinkPolicy

/** シェル サービスは、リンクを開く前に 2 つの判断を行います。 Android クラスに依存せず、単体テストを直接調整します。 リンクを開くことが許可されておらず、null にすることが許可されている場合、 */
internal object ShellLinks {
    /** は [ShellService.START_INVALID_LINK] を返します。販売代理店は確認済みです。ここでもう一度確認してください。 */
    fun refuse(action: String?, uri: String?, pkg: String?): Int? =
        if (LinkPolicy.check(action, uri, pkg) != null) ShellService.START_INVALID_LINK else null

    /** リンクを開くことができるアプリのうち、ホーム画面にタスクがあるアプリは使用中とみなされます。どのアプリも解析できない場合、そのアプリはまだ使用中であるとみなされます。 */
    fun inUse(candidates: List<String>, onMainScreen: Set<String>): Boolean =
        candidates.isEmpty() || candidates.any { it in onMainScreen }
}
