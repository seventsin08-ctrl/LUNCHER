package com.luncher.home

/** Stable IANA zone IDs keep clock widgets accurate through daylight-saving changes. */
object WorldClockZones {
    data class City(val label: String, val zoneId: String)
    val choices = listOf(
        City("Manila", "Asia/Manila"), City("Tokyo", "Asia/Tokyo"),
        City("Seoul", "Asia/Seoul"), City("Dubai", "Asia/Dubai"),
        City("London", "Europe/London"), City("Paris", "Europe/Paris"),
        City("New York", "America/New_York"), City("Los Angeles", "America/Los_Angeles"),
        City("Sydney", "Australia/Sydney"), City("Singapore", "Asia/Singapore")
    )
    private val defaults = listOf("Asia/Manila", "Asia/Tokyo", "Europe/London", "America/New_York")

    fun resolve(saved: String?): List<City> {
        val byId = choices.associateBy { it.zoneId }
        val ids = saved?.split('|')?.filter { it in byId }?.distinct()?.take(4).orEmpty()
        return (if (ids.isEmpty()) defaults else ids).mapNotNull { byId[it] }
    }
}
