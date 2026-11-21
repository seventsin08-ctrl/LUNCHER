package com.luncher.home

import org.junit.Assert.assertTrue
import org.junit.Test

class IosFeaturesTest {
    @Test
    fun allIphoneFeatureSimulationsPass() {
        for (seed in listOf(1, 42, 2026)) {
            val r = IosSimulations.runAll(seed)
            assertTrue("seed $seed: ${r.failures.size} failures:\n" + r.failures.take(30).joinToString("\n"), r.failures.isEmpty())
            assertTrue("too few simulations: ${r.total}", r.total > 10_000)
        }
    }
}
