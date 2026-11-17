package com.luncher.home

/** Preserve existing order and never add duplicate or unavailable app entries. */
object FolderMembership {
    fun available(installedKeys: List<String>, groupedKeys: Set<String>): List<String> {
        val groupedPackages = groupedKeys.map { it.substringBefore('/') }.toSet()
        return installedKeys.filter { it.substringBefore('/') !in groupedPackages }
    }

    fun add(members: List<String>, key: String, installedKeys: Set<String>): List<String> {
        if (key in members || key !in installedKeys) return members
        return members + key
    }
}
