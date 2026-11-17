package com.luncher.home

/** Calculates a folder removal without mutating saved state. */
object FolderExtraction {
    data class Result(val remaining: List<String>, val order: List<String>, val dissolve: Boolean)

    fun take(members: List<String>, key: String, folderKey: String, order: List<String>): Result? {
        if (key !in members) return null
        val remaining = members.filter { it != key }.distinct()
        val updated = order.filter { it != key }.toMutableList()
        val dissolve = remaining.size < 2
        if (dissolve) {
            val folderIndex = updated.indexOf(folderKey)
            if (folderIndex >= 0) {
                updated[folderIndex] = remaining.firstOrNull() ?: HomeGridOrder.EMPTY
            } else if (remaining.isNotEmpty() && remaining[0] !in updated) {
                updated.add(remaining[0])
            }
        }
        return Result(remaining, updated, dissolve)
    }
}
