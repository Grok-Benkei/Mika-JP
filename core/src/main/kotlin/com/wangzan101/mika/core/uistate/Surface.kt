package com.wangzan101.mika.core.uistate


/**
 * は、画面 (携帯電話、Web ページなど) 用の同じ名前の動詞ツールのセットです。名前は `<id>_<動詞>` で、パラメータ構造は同じで、命令と許可される値は個別に指定されます。
 * は、画面機能ごとに 1 つのコピーを提供します。ポートが接続されていない場合でも、ツールは登録されていますが (名前で見つけることができます)、提供されません。
 */
class ScreenFamily(
    /** 機能の ID は、このツール セットの名前のプレフィックスでもあります。 */
    val id: String,
    /** この画面の画面番号のプレフィックス。番号がどの画面に属しているかを識別できない場合は、その番号を押してください。 */
    val snapshotPrefix: String,
    /** 「Where」フィールドは画面読み取り結果で報告されます。 */
    val placeKey: String = APP_PLACE,
    /** press_key keys that can be pressed; null 制限なし。 */
    val keys: Set<Int>? = null,
    /** [keys] 許可されていない場合のモデルの指示。 */
    val keysHint: String? = null,
    /** 体験ルートはそのアクションを記録し、再生します。 */
    val routable: Boolean = false,
    /** このセットの結果はテキスト ツールであり、サンドボックス内の `mika phone` を調整できます。 */
    val sandboxCallable: Boolean = false,
    /** これらの動詞の説明では、この画面の独自のコピー (prompts/tools/<id>_<verb>.md) が使用され、残りは共有テンプレートを使用します。 */
    val ownPrompts: Set<String> = emptySet(),
    /** この画面で何も開いていない場合のモデル（フィードバックボタン）の説明。 */
    val emptyHint: String = "empty_screen",
) {
    /** は、[base] アクションを実行するこのセット内のツールの名前です。 */
    fun tool(base: String) = "${id}_$base"

    companion object {
        const val APP_PLACE = "currentApp"
        const val SITE_PLACE = "currentSite"
    }
}

/** スクリーン機能によって提供される、ポートに接続されたスクリーン。 */
class Surface(
    val family: ScreenFamily,
    /** この画面の画面読み取りと動作はこの画面を経由します。 */
    val screen: ScreenPort,
    /** この画面で宣言されたアプリ フィールドの書き込み方法を確認します。そうでない場合は ArgError をスローし、はいの場合は標準の書き込みメソッドを返します。 */
    val declaredTarget: (String) -> String,
    /** この画面の宣言アプリ欄（ツールパラメータの説明で使用）に記載されている内容を確認します。 */
    val targetNote: String,
    /** 実行ステータスに書き込まれたこの画面の現在のバージョン (Web ページのモバイル バージョンとコンピューター バージョンなど)。そうでない場合は null です。 */
    val mode: (suspend () -> String?)? = null,
) {
    val id: String get() = family.id
}

/**
 * が動作している画面: 通話中は、その画面で動作します ([enter] ～ [leave])。進行中の通話がない場合は、最後に正常に操作された画面であり、タスクが開始される場合は最初の画面です。
 * は、画面に名前が付けられていないデフォルト値を取得するためにのみ使用されます (確認、引き継ぎ、証拠の検証など、インターフェイスの安定性)。電話は転送されません。呼び出しはネストできます (ページ スクリプト内のアクション)。
 */
class SurfaceFocus(val surfaces: List<Surface>) {
    init {
        require(surfaces.isNotEmpty()) { "need at least one surface" }
    }

    /** 最近正常に操作できた画面。 */
    @Volatile private var settled: Surface = surfaces.first()
    private val acting = ArrayDeque<Surface>()

    val current: Surface get() = synchronized(acting) { acting.lastOrNull() } ?: settled

    operator fun get(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** は通話で開始され、[id] 画面で動作します。それが画面でない場合、または提供されていない場合は、現在の画面が使用されます。 */
    fun enter(id: String?) {
        val s = id?.let(::get) ?: current
        synchronized(acting) { acting.addLast(s) }
    }

    /** 通話は終了します。 [succeeded]の場合、その画面は最後に操作した画面となり、失敗はカウントされません。 Android 標準インターフェイスを介した */
    fun leave(succeeded: Boolean) {
        val s = synchronized(acting) { acting.removeLastOrNull() } ?: return
        if (succeeded) settled = s
    }
}
