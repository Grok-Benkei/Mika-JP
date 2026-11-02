package com.wangzan101.mika.core.memory

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
enum class MemoryKind { fact, preference, procedure }

/** メソッドのみが保留中になります (ユーザーによる承認が必要です)。 */
@Suppress("EnumEntryName")
enum class MemoryStatus { active, pending }

/** [type]: `user_said` (ユーザーのオリジナルの単語 [quote]) または `manual` (ユーザーが設定で手書き)。 */
@Serializable
data class MemorySource(
    val type: String,
    val quote: String? = null,
    val conversationId: String? = null,
    val atMs: Long,
    val channel: String? = null,
)

/** [replaces]: 今回更新された古いコンテンツは 1 つのバージョンのみが残っています (インターフェース「Original:...」)。 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val replaces: String? = null,
)
