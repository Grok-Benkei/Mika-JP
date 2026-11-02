package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.agent.ReplyLanguage
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * アプリケーションを主な目的に従って分類します (権限を変更せず、整理と推奨のみに使用されます)。
 * [language] は互換性のために受け取る引数です。分類理由の出力言語は指定値にかかわらず日本語に固定します。
 * 分類名自体は固定の英語の列挙です。
 */
class AppClassifier(
    private val model: ChatModel,
    private val fallback: ChatModel? = null,
    @Suppress("UNUSED_PARAMETER") language: String = "日本語",
) {
    suspend fun classify(apps: List<Pair<String, String>>): Map<String, AppClassification> {
        if (apps.isEmpty()) return emptyMap()
        val input = buildJsonObject {
            put("language", "日本語")
            putJsonArray("apps") {
                apps.forEach { (pkg, label) -> add(buildJsonObject { put("package", pkg); put("label", label) }) }
            }
        }
        for (candidate in listOfNotNull(model, fallback?.takeUnless { it === model })) {
            val result = try {
                withTimeoutOrNull(30_000L) {
                    val reply = candidate.complete(Prompts.role("app_classifier"),
                        listOf(Message(Role.User, listOf(Part.Text(input.toString())), Author.User)), emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, apps.map { it.first }.toSet())
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!result.isNullOrEmpty()) return result.mapValues { (_, classification) ->
                if (ReplyLanguage.mismatch(classification.reason) == null) classification
                else classification.copy(reason = "モデルから日本語の分類理由が得られませんでした。分類は参考情報です。")
            }
        }
        return emptyMap()
    }

    companion object {
        fun parse(raw: String, packages: Set<String>): Map<String, AppClassification> = runCatching {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Json.parseToJsonElement(json).jsonArray.mapNotNull { item ->
                val o = item.jsonObject
                val pkg = o["package"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (pkg !in packages) return@mapNotNull null
                val category = o["category"]?.jsonPrimitive?.content?.let { value -> AppCategory.entries.firstOrNull { it.name == value } }
                    ?: AppCategory.Unknown
                pkg to AppClassification(category, o["reason"]?.jsonPrimitive?.content.orEmpty().take(240))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
