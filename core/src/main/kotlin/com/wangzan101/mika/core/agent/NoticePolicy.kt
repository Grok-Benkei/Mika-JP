package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.guard.DIAGNOSTIC_VERBS
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb

/** ツールの結果が表示されたら、ツールが検索する参照マテリアルをクリックする必要があります。 */
internal sealed interface KnowledgeLookup {
    /** フロントエンド アプリ情報。 */
    data class App(val pkg: String) : KnowledgeLookup
    /** ウェブサイト情報; [host] は小文字になり、www になります。は削除されました。各サイトは初回アクセス時にのみ表示されます。 */
    data class Site(val host: String) : KnowledgeLookup
}

/**
 * ツールの結果に続くもの: 添付された画像にどの説明を付けるか、どこに行ってもどの参考資料を探すか。純粋な機能。
 */
internal object NoticePolicy {
    /** Web ページのインターフェイスを表示し、ページを読み、Web ページを開きます。サイトに到着しました。 */
    private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

    /** の結果には、画像の説明が付いています。 [offScreen] が true の場合、それは画面イメージではなく、画面を移動しないツールによって読み取られたイメージ ファイルであることを意味します。 */
    fun imageNote(verb: Verb?, offScreen: Boolean): String = when {
        // 再発行された写真を確認して、ここに置きます。トリミング用の新規画像です。
        verb == Verb.ViewPastScreens -> Feedback["recalled_note"]
        offScreen -> Feedback["file_image_note"]
        verb in DIAGNOSTIC_VERBS -> Feedback["screenshot_note"]
        // アクションが成功した後に画面が自動的に接続されます。最新のもののみです。以前の画面は、戻って表示できるプレースホルダーに置き換えられます。
        else -> Feedback["action_frame_note"]
    }

    /**
     * [place] に到着したら探す必要がある参考資料: アプリのインターフェイスを探し、このアプリを探します。インターフェイスを見て、ページを読み、Web ページを開いて、ドメイン名でこのサイトを探します。
     * 同じサイトが以前に見つかったかどうかは、発信者によって記録されます。 [ok] は、呼び出しが成功したかどうかを示します。
     */
    fun knowledgeAfter(verb: Verb?, place: Place?, ok: Boolean): List<KnowledgeLookup> = when {
        place == null -> emptyList()
        place.kind == PlaceKind.App && (verb == Verb.Observe || verb == Verb.ListElements) -> listOf(KnowledgeLookup.App(place.name))
        place.kind == PlaceKind.Site && verb in SITE_RESULT_VERBS && ok -> listOf(KnowledgeLookup.Site(place.name.lowercase().removePrefix("www.")))
        else -> emptyList()
    }
}
