package com.wangzan101.mika.core.capability

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.Surface
import com.wangzan101.mika.core.guard.OffScreenGuard
import com.wangzan101.mika.core.knowledge.BundledScripts
import com.wangzan101.mika.core.observation.Look
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject

/** の能力は使用できますか? */
sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    /** も使用可能ですが、いくつかの欠点（動作環境準備中、一部機能がご利用いただけません）がございます。 */
    data class Degraded(val reason: String) : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}

/**
 * システム プロンプト ワードを入力するときに段落の各グループに表示される内容: このタスクでモデルに送信されるツール名とスイッチ。
 * [screens] このタスクでモデルに送信されるツールには、いくつかの画面 (携帯電話、Web ページ...、収集されたものは入り口のみに与えられます) があります。
 */
class PromptEnv(
    val offered: Set<String>, val inlineScreen: Boolean, val screens: Int = 1,
    /** 拡張された能力 (クローズされていないものを含む); null はすべてが展開されることを意味します。収集する能力は自分の段落には与えられません。 */
    val open: Set<String>? = null,
    /** [screens] の展開画面。 */
    val openScreens: Int = screens,
) {
    /** 機能 [id] は拡張されました (または閉じられていません)。 */
    fun isOpen(id: String): Boolean = open == null || id in open

    /**
     * は、特定のツールが提供されている場合にのみ有効な段落内の行です。行の最初の行は `<!-- needs: 工具名, … -->` です。これらのツールはこのタスクのツール リストに残ります (マークを削除します)。
     * それ以外の場合、行全体は必要ありません。マークのない行はそのまま残されます。
     */
    fun gate(text: String): String = text.lines().mapNotNull { line ->
        val m = NEEDS.find(line) ?: return@mapNotNull line
        val tools = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tools.all { it in offered }) m.groupValues[1] + m.groupValues[3] else null
    }.joinToString("\n")

    private companion object {
        val NEEDS = Regex("^(\\s*)<!-- needs: ([a-z0-9_, ]+) -->\\s?(.*)$")
    }
}

/** システム プロンプト ワード内の一連の段落。能力ごとに 1 つのグループとコア自体。 */
internal fun interface PromptSections {
    fun sections(env: PromptEnv): List<String>
}

/**
 * 一種の機能 (携帯電話、Web ページ、サンドボックスなど): 独自のツール、システム プロンプト ワードの段落、画面、ガード。
 * コアは、このインターフェイスを介した機能のみを認識します。タスクごとに 1 つのインスタンスがあり、ポートは構築中に指定されます。
 */
internal interface Capability : PromptSections {
    val id: String

    fun status(): CapabilityStatus

    /** このツール セットは使用後にのみ拡張されます ([com.wangzan101.mika.core.tool.ToolGroups] を参照)。最初は入口ツールのみがあります。収集されていないツールはすべて常にそこにあります。 */
    val folds: Boolean get() = false

    /** 今回 ([task]) をクリックすると、このグループが最初に展開されます (必要な場合に使用する必要があります)。デフォルトでは展開されません。 */
    fun preload(task: String): Boolean = false

    /** ユーザーの文章 ([text]) は、この機能に関連する説明 (ブラウザ ページ上のページ説明など) を削除した後、ユーザー自身が入力したものです。記載がない場合はそのまま返却されます。 */
    fun userWords(text: String): String = text

    /** この機能が提供するすべてのツール。このタスクを使用できるかどうかは、各ツールのコンテキストによって異なります。 */
    fun tools(): List<AgentTool>

    /** には、一連の画面ツールを提供する画面機能があります (ポートに接続されていない場合も)。 */
    val family: ScreenFamily? get() = null

    /** 接続されているポートの画面。そうでない場合は null。この機能の画面ツールのセットは、この機能でのみ機能します。 */
    val surface: Surface? get() = null

    /** はスキルスクリプトを実行できる場合に付与されます。 */
    val scripts: BundledScripts? get() = null

    /** このタスクを開始します。 ツールによって使用されるポートとステータスをツール コンテキストに入力します。 */
    fun onTaskStart(ctx: ToolContext) {}

    /** ユーザーはタスク内の質問に回答しました (フォローアップの質問に回答し、引き継ぎを引き継ぎました)。 */
    fun onUserAnswered(ctx: ToolContext) {}

    /**
     * は、この機能の画面で外部アクション (送信、送信、[com.wangzan101.mika.core.action.OutwardPacing] を参照) を実行する必要があります。 [site] は、それが配置されている Web サイトまたはアプリです。
     * が完了したら、その後に [onOutwardEnd] ([done]: 完了) を続ける必要があります。
     */
    fun onOutwardStart(ctx: ToolContext, site: String) {}

    fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) {}

    /**
     * このアビリティの画面には、画面を切り替えるアクション（[verb]）があります：このアビリティのリズムに合わせてしばらく停止します（[com.wangzan101.mika.core.action.ActionExecutor]を参照、
     * 単一ステップ、バッチ内のすべてのステップがここを通過します)。 [outward]: このステップは外部アクション (送信、送信) です。デフォルトはノンストップです。
     */
    suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced = Paced.NONE

    /** アクションは完了しました ([result] はその結果です)。 [beforeWrite] 停止しましたが、完了していません。 */
    fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {}

    /** 画面を移動しないツールのガード (タスクごとに 1 つ)。そうでない場合は null です。 */
    fun guard(): OffScreenGuard? = null

    /**
     * 確認する前に、現時点でこの能力がどのように見えるかを見てください (モデルの手に何も変更を加えずに)。 [withImage] と写真のフレーム。何も表示されない場合は null。
     */
    suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = null
}

/**
 * 画面動作変更前にアビリティのリズムに合わせて停止した結果：[waitMs]は停止（この呼び出し待ちにカウント）、[idleScrolls]は滞在中に軽く転がす（走行記録のみ入力）。
 * [stop] が null でない場合、このアクションは実行されません。これは、この呼び出しの結果です (画面数の上限に達し、ユーザーが滞在中に中断しました)。
 */
internal class Paced(val waitMs: Long = 0, val idleScrolls: Int = 0, val stop: ToolExecution? = null) {
    companion object {
        val NONE = Paced()
    }
}

/** ツール コンテキストに入れることができる何かへのキー。コアはその中に何が入っているのか知りません。 */
class CapabilityKey<T : Any>(val name: String)
