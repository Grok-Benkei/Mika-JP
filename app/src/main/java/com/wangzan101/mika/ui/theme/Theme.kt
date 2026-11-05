package com.wangzan101.mika.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeMode

/** アニメーション: 長さとカーブ。システムがアニメーション ([MikaTheme.reduceMotion]) をオフにすると、アニメーションは直接配置されます。 */
object MikaMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    /** 減速曲線: 速いスタート、遅い着陸。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    fun <T> page(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(SWITCH_MS, easing = Decelerate)
}

/** これは、システムが「アニメーションを削除する」(アニメーションの継続時間が 0 にスケールされる) 場合に当てはまります。 */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object MikaTheme {
    val colors: MikaColors
        @Composable @ReadOnlyComposable get() = LocalMikaColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * 設定の外観：システムをフォローするときにシステムを見て、常に明るい/暗いときに設定します。一度設定を変更すると（設定ページまたはタスクで変更）、インターフェイスを再構築することなく、その場で変更できます。
 * したがって、ページ、入力、スクロール位置は失われません。
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** ステータス バーとナビゲーション バーのアイコンの深さはインターフェイスと同じです。インターフェイスが強制的に明るくされ、システムが暗い場合、アイコンも表示されるように暗くする必要があります。 */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // 古いシステムのナビゲーション バーは完全に透明にできないため、半透明の底部を埋め込む必要があります。この値は、enableEdgeToEdge のデフォルト値と一致します。
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

/** 暗い色と明るい色は設定 ([appearanceDark]) の外観に従います。同時に、マテリアル コンポーネント (ポップアップ ウィンドウ、入力ボックス、下部パネル) の色と一致させます。 */
@Composable
fun MikaTheme(dark: Boolean = appearanceDark(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) DarkScheme else LightScheme
    val layoutDirection = if (LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMikaColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private fun scheme(c: MikaColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        error = c.bad, onError = c.onAccent,
    )
}

private val LightScheme = scheme(LightColors)
private val DarkScheme = scheme(DarkColors)
