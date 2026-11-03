package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.tool.LinkPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/** はインデックス内のエントリです。アプリ (APP.md)、サイト (SITE.md)、またはスキル (SKILL.md) のヘッダーです。 [files] は、同じディレクトリ内の添付ファイル (相対パス) です。 */
@Serializable
data class IndexEntry(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val packages: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
    val open: String? = null,
    val mode: String? = null,
    /** アプリまたはサイトの閲覧速度要件 (`careful`: 閲覧速度の制限)。書かれていない場合はnull。 */
    val pace: String? = null,
    val links: List<String> = emptyList(),
    val packageAliases: Map<String, String> = emptyMap(),
    val files: List<String> = emptyList(),
    /** サイト コマンド (`scripts/<name>.js` のヘッダー)、サイトのみにあります。 */
    val commands: List<SiteCommandDecl> = emptyList(),
)

/**
 * 組み込みナレッジのディレクトリ規則と構築期間インデックス: `knowledge/apps/<id>/APP.md`、`knowledge/sites/<域名>/SITE.md`、`skills/<id>/SKILL.md`。
 * は実行時にリソース ディレクトリを一覧表示できないため、ビルド時にヘッダーをスキャンして検証し、それを [RESOURCE] として書き込みます。検証は失敗し、ビルドは失敗します。
 */
object KnowledgeIndex {
    const val RESOURCE = "knowledge/index.json"
    const val BODY_LIMIT = 2500
    const val DESCRIPTION_LIMIT = 60
    const val APP = "app"
    const val SITE = "site"
    const val SKILL = "skill"
    val KINDS = listOf(APP, SITE, SKILL)

    /** リソース内のディレクトリ。 */
    fun dirOf(kind: String) = when (kind) { APP -> "knowledge/apps"; SITE -> "knowledge/sites"; else -> "skills" }
    fun fileOf(kind: String) = when (kind) { APP -> "APP.md"; SITE -> "SITE.md"; else -> "SKILL.md" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true; prettyPrintIndent = " " }
    private val DOMAIN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+")
    private val REF = Regex("references/[A-Za-z0-9_./-]*[A-Za-z0-9_/-]")
    /** ヘッドによって認識されるキー。間違って書き込まれたキー (アシスタントのスペルミスなど) は構築に失敗し、静かに破棄されません。 */
    val KEYS = setOf(
        "name", "display", "description", "packages", "domains", "aliases", "terms", "apps", "sites", "open", "mode", "pace",
        "links", "package_aliases", "assistant", "assistant_entries", "assistant_wait_ms",
    )
    /** アプリとサイトのヘッダーペースに値を書き込むことができます。 */
    val PACES = setOf("careful")
    private val ENTRY_REF = Regex("\\b(app|site|skill):([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)")

    fun encode(entries: List<IndexEntry>): String = json.encodeToString(entries) + "\n"
    fun decode(text: String): List<IndexEntry> = json.decodeFromString(text)

    class Result(val entries: List<IndexEntry>, val errors: List<String>)

    /** は、Sankeshu の下の [root] (リソース ルート ディレクトリ) をスキャンします。ライセンス ファイル LICENSE.md はローカル パスをチェックしません。 */
    fun build(root: File): Result {
        val errors = mutableListOf<String>()
        val entries = mutableListOf<IndexEntry>()
        val bodies = mutableMapOf<IndexEntry, String>()
        for (kind in KINDS) {
            val dir = File(root, dirOf(kind))
            for (sub in dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
                val where = "${dirOf(kind)}/${sub.name}"
                val f = File(sub, fileOf(kind))
                if (!f.isFile) { errors += "$where: ${fileOf(kind)} がありません"; continue }
                val (meta, body) = FrontMatter.parse(f.readText())
                val e = parse(kind, sub, meta, body) { errors += "$where/${fileOf(kind)}: $it" } ?: continue
                entries += e
                bodies[e] = body
            }
        }
        crossCheck(root, entries, bodies) { errors += it }
        for (kind in KINDS) {
            File(root, dirOf(kind)).listFiles { f -> f.isDirectory }.orEmpty().forEach { sub ->
                sub.walkTopDown().filter { it.isFile && it.name != "LICENSE.md" }.forEach { file ->
                    val text = file.readText()
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (text.contains("/Users/")) errors += "$rel: ローカル端末の絶対パスが含まれています"
                }
            }
        }
        return Result(entries.sortedWith(compareBy({ KINDS.indexOf(it.kind) }, { it.name })), errors)
    }

    private fun parse(kind: String, dir: File, m: Map<String, Any>, body: String, err: (String) -> Unit): IndexEntry? {
        if (m.isEmpty()) { err("ヘッダー（--- で囲んだメタデータ）がありません"); return null }
        var bad = 0
        fun e(msg: String) { bad++; err(msg) }
        m.keys.filter { it !in KEYS }.forEach { e("ヘッダーに未知のキーがあります: $it") }
        if (kind != SKILL && "terms" in m) e("termsはスキル専用です。アプリとウェブサイトにはaliasesを使ってください")
        fun one(k: String): String? = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun many(k: String): List<String> = when (val v = m[k]) {
            is List<*> -> v.filterIsInstance<String>().map(String::trim).filter(String::isNotEmpty)
            is String -> listOf(v.trim()).filter(String::isNotEmpty)
            else -> emptyList()
        }
        val name = one("name") ?: run { e("nameがありません"); "" }
        if (name != dir.name) e("name($name)はディレクトリ名(${dir.name})と一致させてください")
        if (kind == SITE) {
            if (!DOMAIN.matches(name)) e("サイトのディレクトリ名には主ドメインを小文字で指定してください")
        } else if (!PersonalSkills.NAME.matches(name)) e("名前に使えるのは英小文字、数字、ハイフンです")
        val description = one("description") ?: run { e("descriptionがありません"); "" }
        if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) e("descriptionは$DESCRIPTION_LIMIT 文字以内にしてください")
        if (one("display") == null) e("displayがありません")
        if (body.isBlank()) e("本文が空です")
        if (kind != SKILL && body.length > BODY_LIMIT) e("本文は${body.length}文字で、上限$BODY_LIMIT 文字を超えています。長い資料は参照ファイルに分けてください")
        val packages = many("packages")
        val domains = many("domains").map { it.lowercase().removePrefix("www.") }
        when (kind) {
            APP -> {
                if (packages.isEmpty()) e("packagesがありません")
                if (one("open") == null) e("openがありません")
            }
            SITE -> {
                if (domains.isEmpty()) e("domainsがありません")
                if (name !in domains) e("ディレクトリ名($name)をdomainsに含めてください")
                if (one("mode") !in setOf("desktop", "mobile")) e("modeにはdesktopまたはmobileを指定してください")
            }
        }
        if ("pace" in m) {
            if (kind == SKILL) e("paceを指定できるのはアプリとウェブサイトです")
            else if (one("pace") !in PACES) e("paceに指定できる値: ${PACES.joinToString("/")}")
        }
        val links = many("links")
        links.forEach { raw ->
            val p = raw.split('|').map(String::trim)
            if (p.size !in 2..3 || p.any(String::isEmpty)) e("linksの各項目は「名前 | uri」または「名前 | uri | パッケージ名」の形式で指定してください:$raw")
            else LinkPolicy.check(null, p[1], p.getOrNull(2))?.let { e("linksの${p[1]}は使用できません:$it") }
        }
        val packageAliases = many("package_aliases").mapNotNull { raw ->
            val p = raw.split('|').map(String::trim)
            if (p.size != 2 || p[1] !in packages) { e("package_aliasesの各項目は「別名 | パッケージ名」の形式にし、packagesにあるパッケージ名を指定してください:$raw"); null } else p[0] to p[1]
        }.toMap()
        val commands = commands(kind, dir, ::e)
        if (bad > 0) return null
        val files = dir.walkTopDown().filter { it.isFile && it.name != fileOf(kind) }
            .map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
        return IndexEntry(
            kind = kind, name = name, display = one("display")!!, description = description,
            packages = packages, domains = domains, aliases = many("aliases"), terms = many("terms"),
            apps = many("apps"), sites = many("sites"), open = one("open"), mode = one("mode"), pace = one("pace"),
            links = links, packageAliases = packageAliases, files = files,
            commands = commands,
        )
    }

    /**
     * `scripts/` の下の .js: scripts/manifest.json にリストされているものはサンドボックス スクリプトです。残りは、サイト ナレッジ内のサイト コマンド ([SiteCommandFile.MARK] ヘッダー付き) である必要があります。
     * 头部校验不过构建失败。サイトコマンドはサイトのナレッジ内でのみ定義できます。
     */
    private fun commands(kind: String, dir: File, err: (String) -> Unit): List<SiteCommandDecl> {
        val scripts = File(dir, "scripts")
        val sandbox = File(scripts, "manifest.json").takeIf { it.isFile }?.let { f -> ScriptManifest.parse(f.readText()).map { it.file }.toSet() }.orEmpty()
        return scripts.listFiles { f -> f.isFile && f.name.endsWith(".js") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val rel = "scripts/${f.name}"
            val text = f.readText()
            val command = SiteCommandFile.isCommand(text)
            when {
                rel in sandbox -> { if (command) err("$rel がscripts/manifest.jsonとサイトコマンドのヘッダーで重複しています"); null }
                !command -> { if (kind == SITE) err("$rel がscripts/manifest.jsonに含まれず、サイトコマンドのヘッダーもありません(${SiteCommandFile.MARK})"); null }
                kind != SITE -> { err("$rel: サイトコマンドはサイトのナレッジ内でのみ定義できます"); null }
                else -> SiteCommandFile.parse(rel, text).let { p -> p.errors.forEach { err("$rel: $it") }; p.decl }
            }
        }.also { list -> list.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { err("サイトコマンド$it が重複しています") } }
    }

    private fun crossCheck(root: File, entries: List<IndexEntry>, bodies: Map<IndexEntry, String>, err: (String) -> Unit) {
        fun where(e: IndexEntry) = "${dirOf(e.kind)}/${e.name}"
        entries.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (n, same) -> err("名前$n が重複しています:${same.joinToString { where(it) }}") }
        val apps = entries.filter { it.kind == APP }.associateBy { it.name }
        val sites = entries.filter { it.kind == SITE }.associateBy { it.name }
        apps.values.flatMap { a -> a.packages.map { it to a } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }.forEach { (p, owners) -> err("パッケージ名$p が複数のアプリで定義されています:${owners.joinToString { where(it) }}") }
        sites.values.flatMap { s -> s.domains.map { it to s } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }.forEach { (d, owners) -> err("ドメイン$d が複数のサイトで定義されています:${owners.joinToString { where(it) }}") }
        for (e in entries) {
            e.sites.filter { it !in sites }.forEach { err("${where(e)}: sitesに指定された$it がありません") }
            e.apps.filter { it !in apps }.forEach { err("${where(e)}: appsに指定された$it がありません") }
            // アプリとサイトは相互に関連しています。一方に書く場合は、もう一方にも書く必要があります
            if (e.kind == APP) e.sites.mapNotNull(sites::get).filter { e.name !in it.apps }.forEach { err("${where(it)}: appsに${e.name}がありません（${where(e)}から参照されています）") }
            if (e.kind == SITE) e.apps.mapNotNull(apps::get).filter { e.name !in it.sites }.forEach { err("${where(it)}: sitesに${e.name}がありません（${where(e)}から参照されています）") }
        }
        val byKey = entries.associateBy { it.kind to it.name }
        for (e in entries) {
            val dir = File(root, where(e))
            val texts = listOf(fileOf(e.kind) to bodies.getValue(e)) +
                e.files.filter { it.endsWith(".md") }.map { it to File(dir, it).readText() }
            for ((file, text) in texts) {
                for (line in text.lines()) {
                    val named = ENTRY_REF.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    named.filter { it !in byKey }.forEach { (k, n) -> err("${where(e)}/$file: 参照先$k:$n がありません") }
                    // 行内に別のエントリの名前が指定されている場合、そのエントリに基づいて添付ファイルのパスが検索されます。
                    val target = named.singleOrNull()?.let(byKey::get) ?: e
                    REF.findAll(line).map { it.value }.filter { !File(File(root, where(target)), it).exists() }
                        .forEach { err("${where(e)}/$file: 参照ファイル$it が${where(target)}にありません") }
                }
            }
            File(dir, "scripts/manifest.json").takeIf { it.isFile }?.let { mf ->
                ScriptManifest.parse(mf.readText()).filter { it.runnable && !File(dir, it.file).isFile }
                    .forEach { err("${where(e)}: 実行可能と定義された${it.file}がありません") }
            }
        }
    }

    /** ビルド タスク エントリ: `<资源根目录> <输出的 index.json>`。検証に失敗すると、理由が出力され、ゼロ以外の値で終了します。 */
    @JvmStatic
    fun main(args: Array<String>) {
        val r = build(File(args[0]))
        if (r.errors.isNotEmpty()) {
            System.err.println("内蔵ナレッジの検証に失敗しました（${r.errors.size}件）:")
            r.errors.forEach { System.err.println("  $it") }
            exitProcess(1)
        }
        File(args[1]).apply { parentFile.mkdirs() }.writeText(encode(r.entries))
        println("knowledge index: ${r.entries.groupingBy { it.kind }.eachCount()}")
    }
}
