package com.wangzan101.mika.cap.phone.shell

import android.content.Intent

/** テキスト共有: Android ACTION_SEND、タイプ text/plain、テキストは EXTRA_TEXT に配置され、1 つのパッケージにのみ配信されます。 */
object ShareIntent {
    const val TYPE = "text/plain"

    fun of(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND).setType(TYPE).putExtra(Intent.EXTRA_TEXT, text)
        .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * システムインターフェースが使用できない場合の`am start`のパラメータ（シェル解釈なしではテキストがそのままパラメータとして使用されます）。 [component] は、このパッケージに収集と共有用のページが 1 つしかない場合です。
     * 短縮名は直接指定されます。複数ある場合、パッケージのみが制限され、システムはどれを使用するかを尋ねます。
     */
    fun amArgs(displayId: Int, packageName: String, text: String, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", TYPE, "--es", Intent.EXTRA_TEXT, text) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))
}
