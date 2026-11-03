package com.wangzan101.mika.core.cap.root

/**
 * rootコマンドを読み取りと変更に分ける。確認モードでは変更前に本人へ確認し、不明な書式は変更として扱う。
 * 未知のコマンド、変数展開、ファイルへの出力、here-doc、別のシェルや処理系への委譲は変更扱いにする。
 * shの引用・エスケープ・演算子・改行・括弧に従って分割し、すべての部分が読み取りの場合だけReadOnlyとする。
 */
object RootCommandClass {
    enum class Kind { ReadOnly, Changes }

    fun classify(command: String): Kind {
        val commands = split(command) ?: return Kind.Changes
        if (commands.isEmpty()) return Kind.Changes
        return if (commands.all(::readOnly)) Kind.ReadOnly else Kind.Changes
    }

    /** 既知の読み取りコマンド。動作を変えるオプションを持つものは個別に判定する。 */
    private val READERS = setOf(
        "ls", "cat", "head", "tail", "wc", "stat", "du", "df", "grep", "egrep", "fgrep", "id", "whoami", "uname", "getprop",
        "ps", "pwd", "echo", "printf", "file", "md5sum", "sha1sum", "sha256sum", "sha512sum", "readlink", "realpath",
        "basename", "dirname", "test", "[", "true", "false", "which", "uptime", "free", "cut", "tr", "strings",
        "od", "hexdump", "cmp", "diff", "base64", "getenforce", "lsof", "netstat", "nproc", "printenv", "cd",
        "sleep", "lsattr", "nl", "column", "comm", "expr", "seq", "rev", "fold", "tac",
    )

    private val SYSTEM_BIN_DIRS = setOf("/system/bin", "/system/xbin", "/system_ext/bin", "/product/bin", "/vendor/bin", "/odm/bin")
    private val LOCALE_ASSIGNMENT = Regex("(?:LANG|LC_[A-Z_]+|TZ)=[A-Za-z0-9_./:@+\\-]*")

    /** pmとcmd packageの読み取り専用サブコマンド。 */
    private val PM_READ = setOf(
        "list", "path", "dump", "resolve-activity", "query-activities", "query-services", "query-receivers",
        "get-install-location", "get-max-users", "get-max-running-users", "has-feature", "get-app-links", "get-privapp-permissions",
    )

    /** 代入を伴わない、読み取り専用のSQLite PRAGMA。 */
    private val PRAGMA_READ = setOf(
        "table_info", "table_xinfo", "table_list", "index_list", "index_info", "index_xinfo", "foreign_key_list", "database_list",
        "user_version", "schema_version", "application_id", "page_count", "page_size", "integrity_check", "quick_check",
        "compile_options", "encoding", "journal_mode", "function_list", "collation_list", "freelist_count",
    )

    /** SQLiteの読み取り用ドットコマンド。 */
    private val SQLITE_DOT_READ = setOf(".tables", ".schema", ".indexes", ".indices", ".headers", ".mode", ".dump", ".databases", ".fullschema", ".show", ".width", ".nullvalue", ".separator")

    private val SQLITE_FLAGS = setOf(
        "-header", "-noheader", "-csv", "-json", "-line", "-list", "-column", "-html", "-box", "-table", "-markdown", "-readonly",
        "-batch", "-bail", "-echo", "-quote", "-tabs", "-ascii",
    )

    private fun readOnly(words: List<String>): Boolean {
        // 先頭の環境変数代入は、実行先やコードを差し替えない限定的なロケール設定だけを許可する。
        val assignments = words.takeWhile { ASSIGNMENT.matches(it) }
        if (assignments.any { !LOCALE_ASSIGNMENT.matches(it) }) return false
        val rest = words.drop(assignments.size)
        if (rest.isEmpty()) return true
        var name = rest[0]
        var args = rest.drop(1)
        // 変数をコマンド名にするなど、実行先が確定できない場合は直接実行しない。
        if ('$' in name) return false
        if ('/' in name && name.substringBeforeLast('/') !in SYSTEM_BIN_DIRS) return false
        name = name.substringAfterLast('/')
        if ((name == "toybox" || name == "busybox") && args.isNotEmpty()) {
            if ('/' in args[0]) return false
            name = args[0]
            args = args.drop(1)
        }
        return when (name) {
            "printf" -> args.none { it.startsWith("-v") || it.startsWith("--variable") }
            "file" -> args.none { (it.startsWith("-") && !it.startsWith("--") && 'C' in it) || it.startsWith("--compile") }
            in READERS -> true
            "dumpsys" -> args.isEmpty() || args.size == 1 && (args[0] in setOf("-l", "--list") || !args[0].startsWith("-"))
            // dateの時刻設定、sortの出力先、uniqの出力ファイルを読み取り扱いにしない。
            "date" -> args.all { it in DATE_READ_FLAGS || it.startsWith("+") }
            "sort" -> args.none { (it.startsWith("-") && !it.startsWith("--") && 'o' in it) || it.startsWith("--output") || it.startsWith("--compress-program") }
            "uniq" -> args.size <= 1
            "find" -> args.none { it in FIND_WRITES }
            "sed" -> sedReadOnly(args)
            "logcat" -> logcatReadOnly(args)
            "dmesg" -> args.all { it in DMESG_READ_FLAGS }
            "mount" -> args.isEmpty()
            "env" -> args.isEmpty()
            "pm" -> args.firstOrNull() in PM_READ
            "cmd" -> args.firstOrNull() == "package" && args.getOrNull(1) in PM_READ
            "settings" -> args.dropWhile { it.startsWith("--") || it.all(Char::isDigit) }.firstOrNull().let { it == "get" || it == "list" }
            "sqlite3" -> sqliteReadOnly(args)
            else -> false
        }
    }

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val FIND_WRITES = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
    private val DATE_READ_FLAGS = setOf("-u", "--utc", "--universal", "-R", "--rfc-2822", "-I", "--iso-8601")
    private val DMESG_READ_FLAGS = setOf("-r", "-T", "-t", "-H", "--raw", "--ctime", "--notime", "--human", "--decode", "--kernel", "--userspace")
    private val SED_PRINT = Regex("(?:[0-9]+|\\$)(?:,(?:[0-9]+|\\$))?p|p")

    /** sedは既知の行表示だけを直接実行する。外部スクリプトや未知のオプションは変更扱いにする。 */
    private fun sedReadOnly(args: List<String>): Boolean {
        if (args.firstOrNull() != "-n") return false
        val scriptAt = if (args.getOrNull(1) == "-e") 2 else 1
        val script = args.getOrNull(scriptAt) ?: return false
        return SED_PRINT.matches(script) && args.drop(scriptAt + 1).none { it.startsWith("-") }
    }

    /** 書込みフラグの短縮・連結形を取りこぼさないよう、読み取り用オプションだけを許可する。 */
    private fun logcatReadOnly(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in setOf("-d", "-g", "-L", "-B", "-s", "--silent", "--last", "--binary") -> Unit
                a in setOf("-v", "-t", "-T", "-b", "--format", "--pid", "--uid", "--buffer", "--max-count") -> {
                    val value = args.getOrNull(++i) ?: return false
                    if (value.startsWith("-")) return false
                }
                a.startsWith("-t") && a.drop(2).toIntOrNull() != null -> Unit
                a.startsWith("--pid=") && a.substringAfter('=').toIntOrNull() != null -> Unit
                a.startsWith("-") -> return false
            }
            i++
        }
        return true
    }

    /** SQLiteはreadonly接続で、SQLの全ステートメントが読み取りである場合だけ直接実行する。SQL省略や未知のオプションは変更扱い。 */
    private fun sqliteReadOnly(args: List<String>): Boolean {
        // DB名より前の既知・値なしオプションとして指定されたreadonlyだけを認める。
        // 値消費オプションや--は、readonlyが値やファイル名になる可能性を避けて変更扱いにする。
        val flags = args.takeWhile { it.startsWith("-") }
        if ("-readonly" !in flags || flags.any { it !in SQLITE_FLAGS }) return false
        val rest = args.drop(flags.size)
        if (rest.size < 2 || rest[0].isBlank()) return false
        val sql = rest.drop(1)
        val statements = sql.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }
        return statements.isNotEmpty() && statements.all(::sqlReadOnly)
    }

    private fun sqlReadOnly(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith(".")) return lower.split(Regex("\\s+")).first() in SQLITE_DOT_READ
        val first = lower.split(Regex("[\\s(]+")).first()
        return when (first) {
            "select", "explain" -> WRITE_SQL.none { Regex("\\b$it\\b").containsMatchIn(lower) }
            "pragma" -> '=' !in lower && lower.removePrefix("pragma").trim().substringBefore('(').trim().substringAfter('.') in PRAGMA_READ
            else -> false
        }
    }

    /** SELECTに含まれていても変更に当たる語。入れ子の書込みや拡張の読込みも対象にする。 */
    private val WRITE_SQL = listOf("insert", "update", "delete", "replace", "drop", "create", "alter", "attach", "detach", "vacuum", "reindex", "load_extension", "writefile", "edit")

    /**
     * shの単純コマンドへ分ける。安全に解釈できない書式は null を返し、変更扱いにする。
     * 変数・プロセス置換、here-doc、読書き両用、/dev/null以外への出力、閉じていない引用は直接実行しない。
     */
    private fun split(command: String): List<List<String>>? {
        // シェルが除去する行継続は独自に解釈せず、引用内も含めて変更扱いにする。
        if ("\\\n" in command || "\\\r\n" in command) return null
        val commands = mutableListOf<List<String>>()
        var words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var single = false
        var double = false
        var i = 0
        fun endWord() {
            if (inWord) words += word.toString()
            word.clear()
            inWord = false
        }
        fun endCommand() {
            endWord()
            if (words.isNotEmpty()) commands += words
            words = mutableListOf()
        }
        while (i < command.length) {
            val c = command[i]
            val next = command.getOrNull(i + 1)
            when {
                single -> if (c == '\'') single = false else word.append(c)
                c == '\\' && next != null -> { word.append(next); inWord = true; i++ }
                c == '`' -> return null
                c == '$' -> return null
                double -> when (c) {
                    '"' -> double = false
                    else -> word.append(c)
                }
                c == '\'' -> { single = true; inWord = true }
                c == '"' -> { double = true; inWord = true }
                // 未引用の語頭#はシェルコメントになるため、後続行を引用内のデータとして扱わない。
                c == '#' && !inWord -> return null
                // 未引用のglobは、展開後のファイル名が変更オプションになる可能性がある。
                c in "*?[]" -> return null
                c == '>' || (c == '&' && next == '>') -> {
                    // 直前の数字はファイル記述子（2>...）。
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    i += if (c == '&') 2 else 1
                    if (command.getOrNull(i) == '>' || command.getOrNull(i) == '|') i++
                    // >&2や>&-は記述子の複製・クローズで、ファイル書込みではない。
                    if (command.getOrNull(i) == '&') {
                        i++
                        val fd = command.drop(i).takeWhile { it.isDigit() || it == '-' }
                        if (fd.isEmpty()) return null
                        i += fd.length
                        continue
                    }
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target != "/dev/null") return null
                    i += target.length
                    continue
                }
                c == '<' -> {
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    // here-doc、読書き両用、プロセス置換は直接実行しない。
                    if (next == '<' || next == '>' || next == '(') return null
                    i++
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target.isEmpty() || '$' in target) return null
                    i += target.length
                    continue
                }
                c in ";&|\n()" -> endCommand()
                c.isWhitespace() -> endWord()
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (single || double) return null
        endCommand()
        // { と } はコマンドのグループ化として扱う。
        return commands.map { w -> w.filter { it != "{" && it != "}" } }.filter { it.isNotEmpty() }
    }
}
