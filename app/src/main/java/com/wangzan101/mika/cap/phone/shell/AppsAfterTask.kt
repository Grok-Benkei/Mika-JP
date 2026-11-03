package com.wangzan101.mika.cap.phone.shell

/**
 * タスク終了時にMika画面上のアプリをどうするか（シェル側のルール、システムインターフェースに触れない、単体テストで直接テスト）。
 *
 * はデフォルトのままです。ホーム画面に移動して最後にランク付けされます。ユーザーは、ミカが最近のタスクで完了したページで停止しているのを確認できます。次回ミカが開いたときも、このページで続きが表​​示されます。
 * は、タスクが終了してミュートが復元されたときに突然音が鳴らないように、デフォルトでサウンド (オーディオとビデオの再生) をオフにしています。モデルはユーザーの言葉に従ってマークされています ([keep] / [close])
 * はデフォルトより優先されます。ミカ自身のページや通常のアプリページ以外のページ（デスクトップ、最近のタスクなど）は画面とともに閉じられます。
 */
object AppsAfterTask {
    /** なぜこのようにタスクの運命が決まるのでしょうか? [wire] は結果と実行記録を書き込みます。 モデル */
    enum class Why(val wire: String) {
        /** がマークされています。 */
        Marked("marked"),
        /** が現在声を上げています。 */
        Playing("playing"),
        /** は音が出ているかどうかを読み取ることができず、Mika によって消音されています。ミュートに戻すと突然ビープ音が鳴ることがありますが、音が出ているものとしてカウントされます。 */
        PlaybackUnknown("playback_unknown"),
        /** デフォルト: そのままにします。 */
        Default("default"),
        /** ミカさんのページです。 */
        Own("mika"),
        /** は通常のアプリのページではありません。 */
        NotApp("not_app"),
        /** このシステムはタスクを移動できず、画面がオフのままです。 */
        MoveUnsupported("move_not_supported"),
        /** ホーム画面に移動した後、タイムアウト後も画面が表示されます。ユーザーの目の前に留まることはできません。それをミカの画面に戻してオフにします。 */
        StillInFront("still_in_front"),
    }

    /**
     * 画面上のタスク: [keys] は、そのページが属するパッケージによって置き換えられるキーです ([keyOf] を参照)。 [playing] は、読み取れない場合は null です。
     * [muted] は、現在ミカによって静か化されているということです（最後には復元されます）。
     */
    data class Task(val id: Int, val keys: List<String>, val standard: Boolean, val own: Boolean, val playing: Boolean?, val muted: Boolean = false)

    data class Fate(val task: Task, val keep: Boolean, val why: Why) {
        val key: String get() = task.keys.firstOrNull().orEmpty()
    }

    /** キー: 元のコピー (Mika が存在するユーザー) はパッケージ名で、もう一方のコピーはパッケージ名@ユーザーで、サイレント レコード ([AppAudioKey]) と同じ方法で書き込まれます。 */
    fun keyOf(packageName: String, userId: Int, myUser: Int): String =
        AppAudioKey.of(packageName, userId.takeIf { it != myUser && it >= 0 })

    /** モデルのターゲット キーが同じ書き込み方法に変更されます。Mika のユーザーとして書き込まれたもう 1 つのコピーは、元のコピーと同等です。フォーマットに準拠していないものは破棄されます。 */
    fun normalize(keys: Array<String>?, myUser: Int): Set<String> = keys.orEmpty().mapNotNull { k ->
        AppAudioKey.parse(k)?.let { (pkg, user) -> AppAudioKey.of(pkg, user?.takeIf { it != myUser }) }
    }.toSet()

    fun decide(tasks: List<Task>, keep: Set<String>, close: Set<String>): List<Fate> = tasks.map { t ->
        when {
            t.own -> Fate(t, false, Why.Own)
            !t.standard -> Fate(t, false, Why.NotApp)
            t.keys.any { it in close } -> Fate(t, false, Why.Marked)
            t.keys.any { it in keep } -> Fate(t, true, Why.Marked)
            t.playing == true -> Fate(t, false, Why.Playing)
            // は音が出ているかどうかを読み取ることができません。静かに放置されていません（音を出したい場合はすでに音が出ており、エンディングは何も変わりません）。復元時に突然音がしないように静かに押した場合にカウントされます。
            t.playing == null && t.muted -> Fate(t, false, Why.PlaybackUnknown)
            else -> Fate(t, true, Why.Default)
        }
    }

    /**
     * 移動後、再度確認してください。keep マークが付いていません。ノイズが発生し始めたら、オフに変更してください (ミュートが復元されるとすぐに音が鳴ります)。 「キープ」とマークされた写真は保持されます。 現在
     */
    fun recheck(fate: Fate, playingNow: Boolean?): Fate =
        if (fate.keep && fate.why != Why.Marked && playingNow == true) fate.copy(keep = false, why = Why.Playing) else fate

    /**
     * `dumpsys audio` でプレイしている UID: プレーヤーごとに 1 行 `AudioPlaybackConfiguration piid:.. u/pid:<uid>/<pid> state:started ..`。
     * は、オーディオ サービスの出力が認識されない (再生および録音されないセグメント) 場合には null となり、呼び出し側はそれを「読み取れない」ものとして扱います。
     */
    fun playingUids(dumpsys: String): Set<Int>? {
        if (!dumpsys.contains(PLAYBACK_SECTION)) return null
        return dumpsys.lineSequence().filter { "AudioPlaybackConfiguration" in it }
            .mapNotNull { PLAYER.find(it) }
            .filter { it.groupValues[2] == "started" }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 0 }
            .toSet()
    }

    /** 結果: キー: 理由で項目がオフになりました。 */
    fun closedEntry(f: Fate): String = "${f.key}:${f.why.wire}"

    /**
     * 診断情報の行: 数をカウントするだけで、パッケージ名はリストされません (診断情報には、インストールまたは開かれた内容は含まれません)。
     * 「キープ 1、クローズ 1 (プレイ 1)」「クローズ 2 (移動はサポートされていない)」「なし」など。
     */
    fun diagnosticsLine(kept: Int, closed: List<String>, note: String?): String {
        if (kept == 0 && closed.isEmpty()) return note?.let { "none ($it)" } ?: "none"
        val reasons = closed.map { it.substringAfterLast(':') }.groupingBy { it }.eachCount()
            .entries.sortedBy { it.key }.joinToString(", ") { "${it.key.replace('_', ' ')} ${it.value}" }
        val parts = listOfNotNull(
            "kept $kept".takeIf { kept > 0 },
            if (closed.isEmpty()) null else "closed ${closed.size}" + (if (reasons.isEmpty()) "" else " ($reasons)"),
        )
        return parts.joinToString(", ") + (note?.let { "; $it" } ?: "")
    }

    // フィニッシュディスプレイのバンドル
    const val KEY_KEPT = "kept"
    const val KEY_CLOSED = "closed"
    const val KEY_NOTE = "note"

    /** 移動できないときはメモを書きます。 */
    const val NOTE_MOVE_UNSUPPORTED = "move not supported"
    const val NOTE_PLAYBACK_UNKNOWN = "playback unknown"
    /** はタスクが移動されて以来、タスクを表示できるようになりましたが、残されていません。 */
    const val NOTE_STILL_IN_FRONT = "still visible on main, closed"
    /** 先頭に数字: 移動しましたが、再表示できませんでした。最近のタスクでは見られません。次回同じコピーを開いたときに、元に戻して埋め合わせます。 */
    const val NOTE_LEFT_HIDDEN = "left hidden on main"
    /** ホーム画面のタスク一覧が読めず、残ったタスクが一番後ろに移動しているか確認していません。 */
    const val NOTE_SETTLE_UNKNOWN = "behind check unavailable"

    // AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    const val PLAYER_STATE_STARTED = 2
    private const val PLAYBACK_SECTION = "PlaybackActivityMonitor"
    private val PLAYER = Regex("""u/pid:(-?\d+)/-?\d+\s+state:(\w+)""")
}
