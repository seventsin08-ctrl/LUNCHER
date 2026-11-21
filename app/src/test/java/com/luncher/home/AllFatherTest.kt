package com.luncher.home

import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every simulated notification (AllFatherCases) through All Father's brain on each build. */
class AllFatherTest {
    @Test fun understandsEverySimulatedNotification() {
        val fails = AllFatherCases.ALL.mapNotNull { c ->
            val v = AllFatherBrain.classify(c.app, c.who, c.text)
            val acts = AllFatherBrain.actions(c.app, c.who, c.text, v)
            val task = acts.firstOrNull { it.kind == "TASK" }?.text
            val ok = v.cat == c.cat && v.important == c.imp && acts.map { it.kind }.toSet().containsAll(c.acts) && (c.task == null || task == c.task)
            if (ok) null else "[${c.app}] ${c.who}: ${c.text} → want ${c.cat}/${c.imp}/${c.acts} got ${v.cat}/${v.important}/${acts.map { it.kind }}"
        }
        assertTrue("${fails.size} of ${AllFatherCases.ALL.size} failed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun findsDeadlines() {
        for ((say, want) in AllFatherCases.DUES) {
            val got = AllFatherBrain.dueIn(say)
            assertTrue("dueIn(\"$say\") = $got", got != null && got.first != null && got.second == want)
        }
    }
}
