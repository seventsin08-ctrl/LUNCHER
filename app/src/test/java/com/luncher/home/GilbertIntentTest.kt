package com.luncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every simulated phrasing (GilbertIntentCases) on each build. */
class GilbertIntentTest {
    @Test fun understandsEverySimulatedCommand() {
        val fails = GilbertIntentCases.ALL.mapNotNull { c ->
            val r = GilbertIntent.parse(c.say)
            val ok = r?.kind == c.kind && (c.text == null || r?.text == c.text) && (c.on == null || r?.on == c.on) &&
                (c.num == null || r?.num == c.num) && (c.num2 == null || r?.num2 == c.num2)
            if (ok) null else "\"${c.say}\" → want ${c.kind} ${c.text} on=${c.on} ${c.num} / got ${r?.kind} ${r?.text} on=${r?.on} ${r?.num}"
        }
        assertTrue("${fails.size} of ${GilbertIntentCases.ALL.size} failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun wakeWord() {
        for ((heard, want) in GilbertIntentCases.WAKE) assertEquals("wake(\"$heard\")", want, GilbertIntent.wake(heard))
    }

    @Test fun atLeastAHundredSimulations() = assertTrue(GilbertIntentCases.ALL.size + GilbertIntentCases.WAKE.size >= 100)
}
