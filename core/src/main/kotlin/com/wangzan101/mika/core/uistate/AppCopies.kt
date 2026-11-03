package com.wangzan101.mika.core.uistate

/**
 * 他のユーザーにインストールされているアプリ（クローンや作品情報などの情報）： [packageName] パッケージ名、[userId] ユーザー番号（開くときに指定します）、
 * [userName] システムによってユーザーに与えられた名前 (システム言語に依存し、ハードコーディングされていません。読み取られない場合は空です)。
 */
data class AppCopy(val packageName: String, val userId: Int, val userName: String) {
    /** モデルにリストされている名前: 「アプリ名 (別のコピー: ユーザーの名前)」。 */
    fun label(appLabel: String): String = "$appLabel (another copy: ${userName.ifBlank { "user $userId" }})"
}

/**
 * 他のユーザーのうち、ミカが開くことができるアプリとリストされたアイテム。純粋なロジック、組み込みアシスタント (ユーザーがどのコピーを使用できるかを決定)、およびミカ (リスト、チェック) が共有されます。
 *
 * ユーザーがそれを使用できるかどうかを判断します (Android のマルチユーザー概念に従って、ユーザー名を見ずに、UserInfo、UserManager によって与えられた UserProperties、およびシステム自体によってタグに与えられた名前を読み取ります)。
 * 1. 他に何も関係なく、最初に除外します。ユーザー タイプはプライベートです。システムタグ名は HIDDEN または PRIVATE です。システムは他のアプリから非表示としてマークされています
 *    (UserProperties の profileApiVisibility);Guest、Demo、Temporary、Disabled (タグ名 GUEST / DEMO / EPHEMERAL / DISABLED、
 *    タグ名が読めない場合は、元のタグビットを押してください。プライベート スペースおよびその他のカテゴリは、実行中で現在のユーザーに接続されている場合でも、表示されず、開かれません。
 * 2. 実行中でロックが解除されています。 Quiet モードではありません (作業データが一時停止されているときの Quiet モード)。
 * 3. 現在のユーザーに接続されています。プロファイル グループ (profileGroupId) が現在のユーザーのグループと同じであるか、システムによって報告された親ユーザー (parentId) が現在のユーザーです。
 * 4. そして、それがクローンまたは仕事用プロファイルであることがわかります。ユーザー タイプにクローンが含まれているか、システム タグ名に CLONE が含まれているか、UserProperties に上位ユーザーとメディアを共有していることが示されています。
 *    または元の仕事用プロファイル タイプ android.os.usertype.profile.MANAGED。
 * どの項目も読み取れない場合は、不満足とみなされます。ユーザーが他人に見せたくない情報に触れるよりは、1 つの項目を省略した方が良いでしょう。
 */
object AppCopies {
    // UserInfo.FLAG_* (元の値);システムタグ名が読み取れない場合にのみ使用されます。
    const val FLAG_GUEST = 0x4
    const val FLAG_DISABLED = 0x40
    const val FLAG_QUIET_MODE = 0x80
    const val FLAG_EPHEMERAL = 0x100
    const val FLAG_DEMO = 0x200
    private const val EXCLUDED_FLAGS = FLAG_GUEST or FLAG_DISABLED or FLAG_EPHEMERAL or FLAG_DEMO
    private val EXCLUDED_NAMES = setOf("GUEST", "DEMO", "EPHEMERAL", "DISABLED")

    /** UserInfo.NO_PROFILE_GROUP_ID (つまり、UserHandle.USER_NULL): どのデータ グループにも属していません。 */
    const val NO_PROFILE_GROUP = -10000
    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    /**
     * 組み込みアシスタントによって読み取られるユーザーのタグ: [flags] UserInfo、[flagNames] システムによってこれらのタグに付けられた名前 (UserInfo.flagsToString、システム定義のものを含む)。
     * は読み取られない場合は null)、[type] ユーザー タイプ (読み取られない場合は null)、[profileGroupId] データ グループ、システムによって報告された [parentId] 上位ユーザー (読み込まれない場合は null)、
     * [running] は実行中、[unlocked] はロック解除されています、[quiet] は静かモードです、[mediaShared] UserProperties.isMediaSharedWithParent、
     * [hidden] UserProperties は、他のアプリから非表示になることを示します。これら 2 つの項目が読み取れない場合は null になります。
     */
    data class User(
        val id: Int,
        val flags: Int,
        val type: String?,
        val profileGroupId: Int,
        val running: Boolean,
        val unlocked: Boolean = running,
        val quiet: Boolean = false,
        val mediaShared: Boolean? = null,
        val hidden: Boolean? = null,
        val flagNames: List<String>? = null,
        val parentId: Int? = null,
    )

    /** が使用できる、または使用できないのはなぜですか? [Copy]のみ使用可能です。 */
    enum class Verdict { Copy, Self, Private, Flags, Stopped, Locked, Quiet, NotLinked, NotCopy }

    fun verdict(u: User, current: Int, group: Int): Verdict {
        val type = u.type.orEmpty()
        val names = u.flagNames.orEmpty().map { it.uppercase() }
        return when {
            u.id == current -> Verdict.Self
            type.contains("private", ignoreCase = true) || u.hidden == true ||
                names.any { "HIDDEN" in it || "PRIVATE" in it } -> Verdict.Private
            (if (u.flagNames != null) names.any { it in EXCLUDED_NAMES } else u.flags and EXCLUDED_FLAGS != 0) -> Verdict.Flags
            !u.running -> Verdict.Stopped
            !u.unlocked -> Verdict.Locked
            u.quiet || u.flags and FLAG_QUIET_MODE != 0 || "QUIET_MODE" in names -> Verdict.Quiet
            !(u.profileGroupId == group && group != NO_PROFILE_GROUP) && u.parentId != current -> Verdict.NotLinked
            type.contains("clone", ignoreCase = true) || names.any { "CLONE" in it } || u.mediaShared == true || type == TYPE_MANAGED -> Verdict.Copy
            else -> Verdict.NotCopy
        }
    }

    /** は、現在のユーザー [current] が存在するデータ グループです。現在のユーザーがそれを読み取ることができない場合、またはグループに属していない場合は、それ自体がそのユーザーになります。 */
    fun groupOf(current: Int, users: List<User>): Int =
        users.firstOrNull { it.id == current }?.profileGroupId?.takeIf { it != NO_PROFILE_GROUP } ?: current

    /** 内部のアプリを開くことができるユーザーの数。 */
    fun usable(current: Int, users: List<User>): List<Int> {
        val group = groupOf(current, users)
        return users.filter { verdict(it, current, group) == Verdict.Copy }.map { it.id }
    }

    /** 診断情報の行: 各ユーザーの番号、タグ (およびシステムによって与えられたタグ名)、タイプ、データ グループ、上位、および判定。名前とインストールされている内容は含まれません。 */
    fun summary(current: Int, users: List<User>): String {
        val group = groupOf(current, users)
        val others = users.filter { it.id != current }
        val usable = others.count { verdict(it, current, group) == Verdict.Copy }
        if (others.isEmpty()) return "no other users"
        return "${others.size} other, $usable usable: " + others.joinToString("; ") { u ->
            "${u.id} flags=0x${Integer.toHexString(u.flags)}" + (u.flagNames?.let { " (${it.joinToString("|")})" } ?: "") +
                " type=${u.type ?: "?"} group=${u.profileGroupId}" + (u.parentId?.let { " parent=$it" } ?: "") +
                (if (!u.running) " stopped" else if (u.unlocked) " unlocked" else " locked") +
                " -> ${verdict(u, current, group).name.lowercase()}"
        }
    }

    /** `dumpsys user` のユーザーのいくつかの項目: システムによってマークに付けられた名前 (括弧内)、上位ユーザー (parentId)。 */
    data class Dumped(val flagNames: List<String>?, val parentId: Int?)

    private val DUMP_USER = Regex("""UserInfo\{(\d+):""")
    private val DUMP_PARENT = Regex("""parentId=(-?\d+)""")
    private val DUMP_FLAGS = Regex("""^\s*Flags:\s*-?\d+\s*\(([^)]*)\)""")

    /** `dumpsys user` 読み取り: 各ユーザーのタグ名と上位ユーザー (番号)。タグ名が読み取れない場合は null になります。リフレクションでタグ名を読み取れない場合に使用します。 */
    fun parseDumpsys(output: String): Map<Int, Dumped> {
        val out = linkedMapOf<Int, Dumped>()
        var id: Int? = null
        for (line in output.lineSequence()) {
            val user = DUMP_USER.find(line)
            if (user != null) {
                val u = user.groupValues[1].toInt()
                id = u
                out[u] = Dumped(null, DUMP_PARENT.find(line)?.groupValues?.get(1)?.toInt())
                continue
            }
            val u = id ?: continue
            val flags = DUMP_FLAGS.find(line) ?: continue
            out[u] = out.getValue(u).copy(flagNames = flags.groupValues[1].split('|').map(String::trim).filter(String::isNotEmpty))
        }
        return out
    }

    /**
     * によってリストされる別のコピー: [apps] 現在のユーザーが開くことができるアプリ (パッケージ名、表示名)、[users] が使用できる複数のユーザー (名前付き)、
     * [packages] 各ユーザーにインストールされるパッケージ。現在のユーザーがインストールしたアプリ (リスト内のアプリと一致するもの) のみが、[apps] の順に並べてリストされます。
     */
    fun copies(apps: List<Pair<String, String>>, users: List<UserProfile>, packages: Map<Int, Set<String>>): List<AppCopy> =
        apps.flatMap { (pkg, _) ->
            users.filter { pkg in packages[it.id].orEmpty() }.map { AppCopy(pkg, it.id, it.name) }
        }
}
