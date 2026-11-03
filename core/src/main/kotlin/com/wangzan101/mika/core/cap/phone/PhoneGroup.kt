package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.tools.ScreenTool
import com.wangzan101.mika.core.tool.AgentTool

/** 携帯電話の機能の ID は、携帯電話上のツール セットの名前のプレフィックスでもあります。 */
const val PHONE = "phone"

/** モバイル画面の読み取りの snapshotId は、それから始まります (ポートの乱数と安定した番号の両方)。番号を見れば、それが携帯電話のものであることがわかります。 */
const val PHONE_SNAPSHOT_PREFIX = "p"

/** 携帯電話用の画面ツールのセット: 体験ルートは携帯電話上のメソッドのみを記憶します。 */
internal val PHONE_FAMILY = ScreenFamily(PHONE, snapshotPrefix = PHONE_SNAPSHOT_PREFIX, routable = true, sandboxCallable = true, ownPrompts = setOf("observe", "back", "scroll", "type_text", "clear_text"))

/** 携帯電話専用ツール。 */
internal interface PhoneTool : AgentTool {
    override val capability: String get() = PHONE
    override val sandboxCallable: Boolean get() = true
}

/**
 * 携帯電話のセット: 一連の画面ツール (画面読み取り、画像表示、ポイント、スライド、入力、キーストローク、診断画面) に加えて、リスト表示、アプリとリンクの起動、
 * は、タスクの終了時にアプリをオンのままにするか閉じるかをマークし、システム標準インターフェイスを通じて目覚まし時計、タイマー、スケジュール、スクロール ホイールを設定します。モデルに発行された順序で。
 */
internal object PhoneGroup {
    val tools: List<AgentTool> = ScreenTool.of(PHONE_FAMILY) + listOf(
        PhoneQuery, PhoneFind, PhoneExtract, PhoneCollect, PhoneWait, PhoneEventTool,
        PhoneDialog, PhoneGesture, SetWheel, PhoneRun, PhoneCommand, ListApps, LaunchApp, AppAfterTask, ShareText, OpenLink,
        SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent,
    )
}
