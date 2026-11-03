package com.wangzan101.mika.ui

import android.content.Context

/** 初めて始めるには 3 つのステップ。 */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * を初めて開くときに実行する手順 (純粋な機能、テストが簡単)。携帯電話のガイドと同じように、「見たか」を押して判断し、毎回再生しないでください。
 * - ようこそ、モデルを構成します: メイン モデルがなく、開始プロセスでモデルを構成していない場合は、ここに進む必要があります。すでにモデルを設定している人 (古いユーザーを含む) は、直接スキップできます。
 * - 携帯電話の操作: システムは十分に新しいですが、携帯電話の操作はまだ準備ができておらず、表示後に起動に失敗します。
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** システムは十分に新しく、携帯電話の操作はまだ準備ができていません。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        else -> null
    }

    /** [step] 完了後の次のステップ。それ以上ない場合は null です (ダイアログを入力してください)。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> if (i.phoneNeeded && !i.phoneGuideSeen) OnboardingStep.Phone else null
        OnboardingStep.Phone -> null
    }

    /** 下の改ページ: 3 つのステップのうちのどのステップ。 */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 3
}

/** の開始プロセスの「構成モデル」を完了しましたか?このマシン上に存在します。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
    }
}
