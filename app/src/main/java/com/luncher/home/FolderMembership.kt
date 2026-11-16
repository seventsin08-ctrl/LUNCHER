package com.luncher.home

/** Preserve existing order and never add duplicate or unavailable app entries. */
object FolderMembership {
    fun add(members: List<String>, key: String, installedKeys: Set<String>): List<String> {
        if (key in members || key !in installedKeys) return members
        return members + key
    }
}
