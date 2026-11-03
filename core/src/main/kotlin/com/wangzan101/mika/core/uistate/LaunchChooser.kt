package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** システムが「どれを開きますか?」と尋ねたときにリストされるアイテムの 1 つ。 [clone]: 別のユーザー（クローン、作品情報）のものですか？ null であることはわかりません。 */
data class ChoiceOption(val label: String, val clone: Boolean?)

/** アプリを開いた後、システムは「どれを開きますか?」というポップアップを表示します。 [packageName] は問題のウィンドウが配置されているパッケージで、[options] はこのアプリを言及するオプションです。 */
data class LaunchChoice(val packageName: String, val options: List<ChoiceOption>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        put("options", buildJsonArray {
            for (o in options) add(buildJsonObject { put("label", o.label); o.clone?.let { put("clone", it) } })
        })
    }
}

/**
 * 「どれを開きますか?」: アプリを開くと、上部のウィンドウが別のシステム パッケージ ウィンドウに置き換えられ、ウィンドウ内のオプションにアプリの名前が表示されます。
 * 同じアプリが 2 人のユーザー (アバター、仕事用プロファイル) にインストールされている場合、一部のシステムはどちらを最初に使用するかを尋ねます。そうでない場合、モデルはオープンされていないものとして扱われ、モデルが再度オープンされて何度も質問されます。
 */
object LaunchChooser {
    /**
     * ウィンドウ要素には、アプリ名 [appLabel] のクリック可能なオプションが記載されています。どれも null ではありません (どれを開くかは尋ねません)。認識されたポイントのみ:
     * 権限ボックスなどの説明テキストにもアプリ名が記載されていますが、そのボタンにはアプリ名がありません。
     * [profiles] は、この携帯電話の他のユーザーの名前です。オプションにそれが含まれているユーザーは別のユーザーで、アプリの名前が付いているユーザーは元のユーザーです。
     */
    fun options(appLabel: String, elements: List<UiElement>, profiles: List<String>): List<ChoiceOption>? {
        val label = key(appLabel)
        val picked = optionIndices(appLabel, elements)?.map { elements[it] } ?: return null
        val others = profiles.map(::key).filter { it.isNotEmpty() }
        val counts = picked.groupingBy { key(it.name) }.eachCount()
        return picked.map { e ->
            val name = key(e.name)
            val clone = when {
                others.any { name.contains(it) } -> true
                // 2 つの名前はまったく同じです (角のマークによってのみ区別されます)。文字を見ただけではどちらが元の名前であるかわかりません。
                counts.getValue(name) > 1 -> null
                name == label -> false
                else -> null
            }
            ChoiceOption(e.name.replace('\n', ' ').trim(), clone)
        }
    }

    /** [options] [elements] で認識されたオプションの添え字 (順序は変更されません)。どちらを開くかを尋ねるのは null ではありません。オプションをクリックするときに使用します。 */
    fun optionIndices(appLabel: String, elements: List<UiElement>): List<Int>? {
        val label = key(appLabel)
        if (label.isEmpty()) return null
        return elements.indices.filter { elements[it].let { e -> e.clickable && e.name.isNotBlank() && key(e.name).contains(label) } }.ifEmpty { null }
    }

    private fun key(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}

/** この携帯電話のユーザー(`pm list users`の行): [id] ユーザー番号、[name] 名前(クローン、作品情報など)、[running] が動作しています。 */
data class UserProfile(val id: Int, val name: String, val running: Boolean)

/** は、`pm list users` および `pm list packages` の出力を読み取ります。 */
object AppProfiles {
    private val USER = Regex("""UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}(\s+running)?""")
    private val PACKAGE = Regex("""^package:([A-Za-z0-9_.]+)\s*$""")

    fun parseUsers(output: String): List<UserProfile> = output.lineSequence().mapNotNull { line ->
        val m = USER.find(line) ?: return@mapNotNull null
        UserProfile(m.groupValues[1].toInt(), m.groupValues[2].trim(), m.groupValues[3].isNotEmpty())
    }.toList()

    fun parsePackages(output: String): Set<String> =
        output.lineSequence().mapNotNull { PACKAGE.find(it.trim())?.groupValues?.get(1) }.toSet()

    /** 各パッケージは複数のユーザー コピーにもインストールされます。 [others] はユーザー → そのユーザーによってインストールされたパッケージです。 */
    fun alsoIn(others: Map<UserProfile, Set<String>>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for ((user, packages) in others) for (p in packages) out.getOrPut(p) { mutableListOf() } += user.name.ifEmpty { "user ${user.id}" }
        return out
    }
}
