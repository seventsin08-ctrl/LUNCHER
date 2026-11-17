package com.luncher.home

/** Stable IANA zone IDs keep clock widgets accurate through daylight-saving changes. */
object WorldClockZones {
    data class City(val label: String, val zoneId: String, val key: String = zoneId)
    val choices = listOf(
        City("Manila", "Asia/Manila"), City("Tokyo", "Asia/Tokyo"),
        City("Seoul", "Asia/Seoul"), City("Singapore", "Asia/Singapore"),
        City("Hong Kong", "Asia/Hong_Kong"), City("Bangkok", "Asia/Bangkok"),
        City("Dubai", "Asia/Dubai"), City("Riyadh", "Asia/Riyadh"),
        City("London", "Europe/London"), City("Paris", "Europe/Paris"),
        City("Berlin", "Europe/Berlin"), City("Rome", "Europe/Rome"),
        City("New York", "America/New_York"), City("Miami", "America/New_York", "city:miami"),
        City("Chicago", "America/Chicago"), City("Los Angeles", "America/Los_Angeles"),
        City("San Francisco", "America/Los_Angeles", "city:san_francisco"),
        City("Mexico City", "America/Mexico_City"), City("Rio de Janeiro", "America/Sao_Paulo"),
        City("Sydney", "Australia/Sydney"), City("Melbourne", "Australia/Melbourne"),
        City("Auckland", "Pacific/Auckland")
    )
    private val defaults = listOf("Asia/Manila", "Asia/Tokyo", "Europe/London", "America/New_York")

    fun resolve(saved: String?): List<City> {
        val byId = choices.associateBy { it.key }
        val ids = saved?.split('|')?.filter { it in byId }?.distinct()?.take(4).orEmpty()
        return (if (ids.isEmpty()) defaults else ids).mapNotNull { byId[it] }
    }
}
