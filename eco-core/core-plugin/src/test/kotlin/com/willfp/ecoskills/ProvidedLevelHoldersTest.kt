package com.willfp.ecoskills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProvidedLevelHoldersTest {
    private data class Entry(val id: String, var level: Int)
    private data class Holder(val entry: Entry, val level: Int)
    private data class Provided(val holder: Holder)

    @Test fun `matches original pipeline for union order and absent levels`() {
        val effects = listOf(Entry("speed", 9), Entry("zero", 0), Entry("shared", 2))
        val stats = listOf(effects.last(), Entry("health", 3))
        val entries = effects union stats
        val resolve: (Entry) -> Holder? = { if (it.level == 0) null else Holder(it, it.level) }
        val expected = entries.mapNotNull(resolve).map { Provided(it) }
        assertEquals(expected, collectProvidedLevelHolders(entries, resolve, ::Provided))
        assertEquals(listOf("speed", "shared", "health"), expected.map { it.holder.entry.id })
    }

    @Test fun `every entry is queried once and only nonnull holders are wrapped`() {
        val queries = mutableListOf<String>()
        val wrapped = mutableListOf<String>()
        val entries = listOf(Entry("a", 1), Entry("zero", 0), Entry("b", 2))
        collectProvidedLevelHolders(entries, {
            queries += it.id
            if (it.level == 0) null else Holder(it, it.level)
        }, { wrapped += it.entry.id; Provided(it) })
        assertEquals(listOf("a", "zero", "b"), queries)
        assertEquals(listOf("a", "b"), wrapped)
    }

    @Test fun `returns exact holder identity and does not deduplicate holder results`() {
        val holder = Holder(Entry("shared", 1), 1)
        val result = collectProvidedLevelHolders(listOf(1, 2), { holder }, ::Provided)
        assertEquals(2, result.size)
        result.forEach { assertSame(holder, it.holder) }
    }

    @Test fun `subsequent scan sees changed levels and registry entries without stale cache`() {
        val entry = Entry("speed", 0)
        val entries = mutableListOf(entry)
        fun scan() = collectProvidedLevelHolders(entries,
            { if (it.level == 0) null else Holder(it, it.level) }, ::Provided)
        assertTrue(scan().isEmpty())
        entry.level = 9
        entries += Entry("health", 3)
        assertEquals(listOf(9, 3), scan().map { it.holder.level })
        entries.clear()
        assertTrue(scan().isEmpty())
    }

    @Test fun `resolution failure propagates without querying later entries`() {
        val error = IllegalStateException("test")
        val queried = mutableListOf<Int>()
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            collectProvidedLevelHolders(listOf(1, 2, 3), {
                queried += it
                if (it == 2) throw error else it
            }, { it })
        })
        assertEquals(listOf(1, 2), queried)
    }
}
