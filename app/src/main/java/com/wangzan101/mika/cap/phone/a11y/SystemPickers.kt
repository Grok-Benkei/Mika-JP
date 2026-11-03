package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * ターゲット アプリによって呼び出されるシステム インターフェイスが配置されているパッケージ (アクセス許可ボックス、セレクター、ファイル、写真セレクター): 前景に表示されるものは、削除されたものとしてカウントされません。
 * は標準のインテントに従って解析され、パッケージ名はハードコーディングされません。
 */
internal object SystemPickers {
    /** システムに付属のセレクター (ResolverActivity など) が配置されているパッケージ。 */
    const val SYSTEM_PACKAGE = "android"
    private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

    fun intents(): List<Intent> = buildList {
        add(Intent(ACTION_REQUEST_PERMISSIONS))
        add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
        add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Intent(MediaStore.ACTION_PICK_IMAGES))
    }

    fun settingsPackage(pm: PackageManager): String? =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName }.getOrNull()

    fun resolve(pm: PackageManager): Set<String> =
        intents().mapNotNull { runCatching { pm.resolveActivity(it, 0)?.activityInfo?.packageName }.getOrNull() }.toSet() + SYSTEM_PACKAGE
}
