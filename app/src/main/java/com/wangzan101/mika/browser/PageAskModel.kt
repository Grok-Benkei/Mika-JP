package com.wangzan101.mika.browser

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.task.TaskService.Companion.isActive

/** 現時点でブラウザ ページの下部にあるものは、通常の入力バー、半分の画面のダイアログ パネル、および折りたたまれた小さなカードです。 */
enum class PageAskMode { Bar, Panel, Card }

/**
 * は、その部分をブラウザ ページに表示する方法 (純粋なロジック) をミカに尋ねました。 [state] は、現時点でのこのページの会話におけるそのもののステータスです (null ではありません)。
 * [open] は、パネルが開いていることを意味します (ユーザーが質問するか、小さなカードをクリックするか、ミカが返信を求める場合に開きます。ユーザーがパネルを閉じるか、ページ上でアクションを実行すると閉じます)。
 * - パネルは開いたときのパネルです。
 * - 集めてください。それはまだ終わっていません。下部にある小さなカード (現時点で何をしているかを示しています)。ページで作業していて一時停止すると、カードには「一時停止中」と表示され、一番上のカードには「操作中です。ミカは一時停止して続行しました」と表示されます。
 * - 残り (タスクなし、完了してしまった): 通常の入力バー。
 */
object PageAskModel {
    fun mode(state: TaskState?, open: Boolean): PageAskMode = when {
        open -> PageAskMode.Panel
        state != null && state.isActive() -> PageAskMode.Card
        else -> PageAskMode.Bar
    }

    /** あなたは自分でページを操作し、ミカはあなたが「続行」をクリックするのを待ちます。 */
    fun pausedForUser(state: TaskState?): Boolean =
        state is TaskState.Held && state.reason.code == "paused" && state.reason.why == TaskGate.USER_ON_PAGE

    /** ページの周囲に明るい境界線が点灯します: ミカは作業中です (待機や一時停止ではありません)。 */
    fun glow(state: TaskState?): Boolean = state is TaskState.Running

    /** ミカはあなたに返信してほしいと思っています (答える、同意する、引き継ぐ、手を動かす): 他の州からここに入ると、パネルが自動的に開きます。 */
    fun needsYou(state: TaskState?): Boolean = when (state) {
        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> true
        is TaskState.Held -> state.reason.needsYou
        else -> false
    }

    /**
     * ステータスが [before] から [after] に変化するとき、パネルはどのように変化する必要がありますか。開く場合は true、閉じる場合は false、変更しない場合は null です。
     * は、ページ上で一時停止すると (ページの邪魔にならないように) 小さなカードに折りたたまれます。ミカが返信してほしいときに開きます。
     */
    fun follow(before: TaskState?, after: TaskState?): Boolean? = when {
        pausedForUser(after) && !pausedForUser(before) -> false
        needsYou(after) && !needsYou(before) -> true
        else -> null
    }
}
