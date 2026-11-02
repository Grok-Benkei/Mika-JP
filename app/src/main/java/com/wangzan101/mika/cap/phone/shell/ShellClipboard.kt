package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * シェル プロセスは、システム クリップボードに直接アクセスします。一部のシステムでは、ClipboardManager はクリップボードを読み取る前に設定を読み取ります。
 * ただし、app_processには通常のAppのプロセス登録がなく、ActivityThread経由でContentProviderを取得できません。
 * バインダーは引き続きパッケージ名、uid、ユーザー、デバイスごとに権限をチェックします。ここでは、Java ラッパー層の追加の設定クエリを回避しているだけです。
 * 認識されないインターフェイス (以前のバージョンの署名など​​) は、以前と同じ動作で ClipboardManager にフォールバックします。
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }
    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }
    private val callerLabel by lazy {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(ShellContext.SHELL_PACKAGE, 0)).toString()
        }.getOrDefault(ShellContext.SHELL_PACKAGE)
    }

    val primaryClip: ClipData?
        get() = getClip.let { if (it == null) manager.primaryClip else call(it) as ClipData? }
    val primaryClipDescription: ClipDescription?
        get() = getDescription.let { if (it == null) manager.primaryClipDescription else call(it) as ClipDescription? }
    fun hasPrimaryClip(): Boolean = hasClip.let { if (it == null) manager.hasPrimaryClip() else call(it) as Boolean }
    // の書き込みは、フレームワークの元の ClipData の準備とクリアのプロセスに従い、読み取りパスを追加の設定クエリに置き換えるだけです。
    fun setPrimaryClip(clip: ClipData) = manager.setPrimaryClip(clip)
    fun clearPrimaryClip() = manager.clearPrimaryClip()

    /** 機能に基づいてインターフェイスを検索します。最初に元の署名を特定し、次に既知の拡張署名を特定します。どちらも true でない場合は、null が返され、呼び出し元は ClipboardManager に戻ります。 */
    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            // Android 14+: パッケージ名、attributionTag、userId、deviceId
            arrayOf(s, s, i, i),
            // Android 12–13: パッケージ名、attributionTag、userId
            arrayOf(s, s, i),
            // 一部のシステムでは、読み取り値がパッケージ名、アプリケーション名、プライバシー スタック、attributionTag、userId、deviceId、ユーザーが操作しているかどうかに拡張されます。
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull { runCatching { type.getMethod(name, *it) }.getOrNull() }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            // バックグラウンドでの保存/復元では、ユーザーが「貼り付け」をクリックする必要がなく、ユーザー操作を偽造することもありません。プライバシー スタックは、フレームワークのデフォルト値として空です。
            7 -> arrayOf(ShellContext.SHELL_PACKAGE, callerLabel, "", null, userId, 0, false)
            // ShellContext は、デフォルト デバイスのシステム コンテキストであり、元の ClipboardManager によって使用されるデバイスと一致します。
            4 -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId, 0)
            else -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId)
        }
        return method.invoke(service, *args)
    }
}
