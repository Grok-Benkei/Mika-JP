package com.wangzan101.mika.core.shell

/**
 * root済み端末でのsuの応答。[Granted] は要求したUIDで動作し、shellの場合はGIDも一致した状態。[Denied] は拒否または未実行での終了。
 * [NoAnswer] は制限時間まで無応答だった状態。[WrongUid] は要求したUID/GIDへの切替えを確認できない状態。
 */
enum class SuAnswer { Granted, Denied, NoAnswer, WrongUid }

/** suの探索と応答解析。ファイルの有無と実際のUID/GIDで判定し、root管理アプリの種類には依存しない。 */
object SuProbe {
    /** 標準PATHやroot管理環境でsuが置かれる主なディレクトリ。追加のPATHも探索する。 */
    val DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")

    /** 最初に見つかったsuの絶対パス。なければ null。[path] はPATH、[exists] は存在確認。 */
    fun find(path: String?, exists: (String) -> Boolean): String? =
        (path.orEmpty().split(':').map { it.trim().trimEnd('/') }.filter { it.startsWith("/") } + DIRS)
            .distinct().map { "$it/su" }.firstOrNull(exists)

    private val UID = Regex("""\buid=(\d+)""")
    private val GID = Regex("""\bgid=(\d+)""")

    /** idまたはid -uの出力からUIDを読む。suの案内行が混じる場合もあるため出力全体から探す。 */
    fun uid(output: String): Int? =
        UID.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    fun gid(output: String): Int? = GID.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * su経由でidを実行した応答。[output] はsuの出力で、出力なしの終了は空文字。
     * shellのUID/GID 2000を確認する。rootのUID0経路とは分け、値の欠落・不一致は [SuAnswer.WrongUid] にする。
     */
    fun answer(output: String, timedOut: Boolean, expectUid: Int): SuAnswer {
        val uid = uid(output)
        return when {
            uid == expectUid && expectUid == HelperHandoff.SHELL_UID && gid(output) != HelperHandoff.SHELL_UID -> SuAnswer.WrongUid
            uid == expectUid -> SuAnswer.Granted
            uid != null -> SuAnswer.WrongUid
            timedOut -> SuAnswer.NoAnswer
            else -> SuAnswer.Denied
        }
    }

    /** root実行用の引数。コマンドは一つの引数のままsu -cへ渡す。 */
    fun rootArgs(su: String, command: String): List<String> = listOf(su, "-c", command)

    /**
     * shell（UID2000）で起動する引数。ユーザー指定は-cより前へ置き、コマンドを分割しない。
     * suによっては-c以降をすべてコマンドと解釈するため、実際のUID/GIDで切替えを確認する（[answer]）。
     */
    fun shellArgs(su: String, command: String): List<String> = listOf(su, HelperHandoff.SHELL_UID.toString(), "-c", command)
}

/**
 * root経由のヘルパー起動状態。[Off] は未許可、[Ready] は明示許可されsuを利用できる状態。
 * 有効なのに使えない場合は原因を返す。[Gone] はsu不在、[Denied] はsuによる拒否。
 * [NoAnswer] は無応答、[NoShellUid] はshellのUID/GIDを確認できない状態。ユーザーの再試行まで再要求しない。
 */
enum class RootStart {
    Off, Ready, Gone, Denied, NoAnswer, NoShellUid;

    /** 有効なのに利用できない場合の失敗。[Off] と [Ready] は null。 */
    val failure: BuiltinShellFailure?
        get() = when (this) {
            Off, Ready -> null
            Gone -> BuiltinShellFailure.RootUnavailable
            Denied -> BuiltinShellFailure.RootDenied
            NoAnswer -> BuiltinShellFailure.RootNoAnswer
            NoShellUid -> BuiltinShellFailure.RootNoShellUid
        }

    companion object {
        /** [enabled] はユーザーのスイッチ、[suPresent] はsuの有無、[last] は前回の応答。初回と再試行後は null。 */
        fun of(enabled: Boolean, suPresent: Boolean, last: SuAnswer?): RootStart = when {
            !enabled -> Off
            !suPresent -> Gone
            last == SuAnswer.Denied -> Denied
            last == SuAnswer.NoAnswer -> NoAnswer
            last == SuAnswer.WrongUid -> NoShellUid
            else -> Ready
        }
    }
}

/**
 * 設定画面のroot許可状態。suがなければ非表示、要求中は [Pending]。
 * 応答後は [Granted] / [Denied] / [NoAnswer] / [NoShellUid]、未要求は [Unknown]。
 */
enum class RootGrant {
    Unknown, Pending, Granted, Denied, NoAnswer, NoShellUid;

    companion object {
        fun of(answer: SuAnswer?): RootGrant = when (answer) {
            null -> Unknown
            SuAnswer.Granted -> Granted
            SuAnswer.Denied -> Denied
            SuAnswer.NoAnswer -> NoAnswer
            SuAnswer.WrongUid -> NoShellUid
        }
    }
}
