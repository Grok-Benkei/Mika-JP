package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * タスクはアプリから取得されます。モデルの取得元、評価フック、表示されているタスクに現時点で画面イメージがあるかどうかがわかります。プロセス全体のコピーがアプリによって提供されます。
 * は、モデルのソースと内部のフックを置き換えるテストを行います。
 */
class TaskEnvironment {
    /** 表示されているタスクの仮想スクリーン番号。タスクがない場合、または画面がまだ作成されていない場合（システムコマンド、イベントツール、またはアプリを初めて開いたときに作成されます）の場合は-1です。 */
    val agentDisplayId = MutableStateFlow(-1)
    /** によって表示されているタスクは、仮想画面上ですでにアプリを開いています (画面の構築後はカウントされません。システム コマンドを実行しているだけの場合、画面は空です)。 */
    val agentAppShown = MutableStateFlow(false)
    /** 表示されているタスクは内蔵ブラウザを使用しています (ピクチャーインピクチャーで Web ページを表示する必要があります)。 */
    val webScreenOpen = MutableStateFlow(false)
    /** ピクチャーインピクチャーで表示する画像があります。仮想画面上でアプリが開かれているか、内蔵ブラウザーが使用されています。空白の画面を作成するだけのタスクはカウントされません。 */
    val previewOpen: Flow<Boolean> = combine(agentAppShown, webScreenOpen) { app, web -> app || web }

    // 3 つのファクトリの ModelSession は、この呼び出しが属する会話 (ModelSession.of(conversation id)) です。同じ会話で構築されたモデルはすべて同じものに与えられます。
    // サービスプロバイダーがセッション番号を必要とする場合は、それに応じてルーティングとキャッシュを行うだけです。

    /** のメイン モデルは、設定のモデル構成から取得されます。テストはスクリプト モデルに置き換えることができます。 */
    @Volatile var modelFactory: (Context, ModelSession) -> ChatModel? = defaultModelFactory
    /** 軽量モデル (成功基準などのリスト);空の場合、メイン モデルがモデルとして機能します。メインモデルをテストする場合は、設定で実際のモデルに合わせることを避けるために、メインモデルも一緒に置き換えてください。 */
    @Volatile var lightModelFactory: (Context, ModelSession) -> ChatModel? = defaultLightModelFactory
    /** ビジュアル モデル (チェックする際はスクリーンショットを参照してください)。設定で表示できるモデルがない場合は空です。 */
    @Volatile var visionModelFactory: (Context, ModelSession) -> ChatModel? = defaultVisionModelFactory

    /** 実行中のマシンを評価するために使用されるフック。すべての製品にはデフォルト値があります。 */
    val evalHooks = EvalHooks()

    /** はプロセス中のアプリケーションを示します。 */
    interface Owner {
        val tasks: TaskEnvironment
    }

    companion object {
        val defaultModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Main, s) }
        val defaultLightModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Light, s) }
        val defaultVisionModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Vision, s) }

        fun of(context: Context): TaskEnvironment = (context.applicationContext as Owner).tasks
    }
}
