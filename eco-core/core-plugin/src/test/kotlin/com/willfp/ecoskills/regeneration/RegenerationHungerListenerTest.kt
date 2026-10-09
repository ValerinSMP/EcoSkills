package com.willfp.ecoskills.regeneration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegenerationHungerListenerTest {
    @Test
    fun `normalizes custom health without affecting vanilla health`() {
        assertEquals(1.0, regenerationHungerMultiplier(20.0, 20.0, 0.15))
        assertEquals(0.5, regenerationHungerMultiplier(40.0, 20.0, 0.15))
        assertEquals(1.0 / 6.0, regenerationHungerMultiplier(120.0, 20.0, 0.15), 0.000001)
        assertEquals(0.15, regenerationHungerMultiplier(200.0, 20.0, 0.15))
    }
}
