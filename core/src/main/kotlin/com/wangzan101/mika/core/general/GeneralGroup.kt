package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.AgentTool

/** 一般的なツール (どの画面に属さない): 待つ、振り返る、ユーザーに尋ねる、確認する、ユーザーに渡す、障害に対処する、完了していない場合は終了、知識とスキルを確認する、ユーザーの要件に従ってスキルを書き留める、記憶する、この会話の前のラウンドの記録を確認する。モデルに発行された順序で。 */
internal object GeneralGroup {
    val tools: List<AgentTool> = listOf(
        Wait, ViewPastScreens, ViewUserScreen, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
        KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, ProfileEdit, HistoryRead, ScheduleTask, SpawnAgent,
        com.wangzan101.mika.core.settings.SettingsList, com.wangzan101.mika.core.settings.SettingsSet,
    )
}
