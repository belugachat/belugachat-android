package com.belugachat.app.chat

import com.belugachat.app.data.MessageEntity

/**
 * Messages form a tree: edits and regenerations add siblings under the same parent.
 * A conversation shows the path from the root to its active leaf.
 */
object MessageTree {
    fun path(messages: List<MessageEntity>, leafId: String?): List<MessageEntity> {
        if (leafId == null) return emptyList()
        val byId = messages.associateBy { it.id }
        val path = ArrayList<MessageEntity>()
        var cursor = byId[leafId]
        val seen = HashSet<String>()
        while (cursor != null && seen.add(cursor.id)) {
            path += cursor
            cursor = cursor.parentId?.let(byId::get)
        }
        return path.asReversed()
    }

    fun children(messages: List<MessageEntity>, parentId: String?): List<MessageEntity> =
        messages.filter { it.parentId == parentId }.sortedBy { it.createdAt }

    /** Follows the newest child from [fromId] down to a leaf. */
    fun latestLeaf(messages: List<MessageEntity>, fromId: String): String {
        val byParent = messages.groupBy { it.parentId }
        var current = fromId
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val next = byParent[current]?.maxByOrNull { it.createdAt } ?: break
            current = next.id
        }
        return current
    }

    data class Siblings(val index: Int, val ids: List<String>) {
        val count get() = ids.size
    }

    fun siblings(messages: List<MessageEntity>, message: MessageEntity): Siblings {
        val ids = children(messages, message.parentId).filter { it.role == message.role }.map { it.id }
        return Siblings(ids.indexOf(message.id).coerceAtLeast(0), ids.ifEmpty { listOf(message.id) })
    }

    /** Leaf to activate when switching to the sibling [delta] steps away from [message]. */
    fun switchSibling(messages: List<MessageEntity>, message: MessageEntity, delta: Int): String? {
        val siblings = siblings(messages, message)
        val target = siblings.ids.getOrNull(siblings.index + delta) ?: return null
        return latestLeaf(messages, target)
    }
}
