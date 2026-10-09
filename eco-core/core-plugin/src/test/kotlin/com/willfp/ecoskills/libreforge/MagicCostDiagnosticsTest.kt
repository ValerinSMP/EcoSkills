package com.willfp.ecoskills.libreforge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MagicCostDiagnosticsTest {
    @Test fun `disabled or blank filter never logs`() {
        val limit = MagicDiagnosticLimit()
        assertFalse(limit.allow(false, "*", "A", 0))
        assertFalse(limit.allow(true, "", "A", 0))
    }

    @Test fun `filter matches player without leaking other players`() {
        val limit = MagicDiagnosticLimit()
        assertFalse(limit.allow(true, "Mtynnn", "B", 0))
        assertTrue(limit.allow(true, "Mtynnn", "mtynnn", 0))
    }

    @Test fun `global budget resets after a second`() {
        val limit = MagicDiagnosticLimit()
        repeat(5) { assertTrue(limit.allow(true, "*", "A", 0)) }
        assertFalse(limit.allow(true, "*", "B", 0))
        assertTrue(limit.allow(true, "*", "B", 1_000_000_000))
    }
}
