package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WorldClockZonesTest {
    @Test fun defaultsAreRealTimeZones() {
        assertEquals(4, WorldClockZones.resolve(null).size)
        assertTrue(WorldClockZones.choices.all { runCatching { ZoneId.of(it.zoneId) }.isSuccess })
    }

    @Test fun restoresSelectedCitiesInOrderWithoutDuplicates() {
        val cities = WorldClockZones.resolve("Europe/London|Asia/Tokyo|Europe/London|bad")
        assertEquals(listOf("Europe/London", "Asia/Tokyo"), cities.map { it.zoneId })
    }

    @Test fun ignoresInvalidOrExtraCities() {
        assertEquals(4, WorldClockZones.resolve("bad").size)
        assertEquals(4, WorldClockZones.resolve(WorldClockZones.choices.joinToString("|") { it.key }).size)
    }

    @Test fun citiesCanShareATimeZoneWithoutLosingTheirNames() {
        val cities = WorldClockZones.resolve("America/New_York|city:miami")
        assertEquals(listOf("New York", "Miami"), cities.map { it.label })
        assertEquals(listOf("America/New_York", "America/New_York"), cities.map { it.zoneId })
    }
}
