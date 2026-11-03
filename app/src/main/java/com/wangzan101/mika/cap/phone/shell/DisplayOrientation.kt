package com.wangzan101.mika.cap.phone.shell

/** 背景画面がメイン画面のフォーカスを奪わない場合、システムは水平画面のアクティビティを垂直画面の上部に縮小することがあります。システムによって報告されたアプリケーション ウィンドウの比率に従って、画面の向きを修正します。 */
internal object DisplayOrientation {
    fun rotation(bufferWidth: Int, bufferHeight: Int, current: Int, appWidth: Int, appHeight: Int): Int? {
        if (appWidth <= 0 || appHeight <= 0 || appWidth == appHeight || bufferWidth == bufferHeight) return null
        val naturalLandscape = bufferWidth > bufferHeight
        val currentLandscape = if (current % 2 == 0) naturalLandscape else !naturalLandscape
        val appLandscape = appWidth > appHeight
        if (currentLandscape == appLandscape) return null
        return if (naturalLandscape == appLandscape) 0 else 1
    }
}
