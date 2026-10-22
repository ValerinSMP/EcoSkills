package com.willfp.ecoskills.gui.menus

import com.willfp.eco.core.gui.menu.MenuBuilder
import com.willfp.eco.core.gui.slot.FillerMask
import com.willfp.eco.core.gui.slot.Slot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*

/** Exercises eco's actual builder/layer resolver, not a second coordinate model. */
class NativeMenuLayerTest {
    private fun builder(rows: Int): MenuBuilder = Class.forName("com.willfp.eco.internal.gui.menu.EcoMenuBuilder")
        .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(rows, 9) as MenuBuilder

    @Test fun sameLayerFillerReproducesTheBugAndNativeBackgroundMaskLeavesContentVisible() {
        org.junit.jupiter.api.Assumptions.assumeTrue(javaClass.classLoader.getResource(
            "com/willfp/eco/internal/gui/menu/EcoMenuBuilder.class") != null,
            "Provide -PecoRuntimeJar=<pinned eco runtime> to test native layers")
        for (rows in listOf(4, 6)) {
            val filler = mock(Slot::class.java, CALLS_REAL_METHODS)
            val control = mock(Slot::class.java, CALLS_REAL_METHODS)
            val broken = builder(rows)
            for (row in 1..rows) for (column in 1..9) broken.setSlot(row, column, filler)
            broken.setSlot(2, 6, control)
            assertSame(filler, broken.build().getSlot(2, 6), "Same-layer first component covers later content")

            val mask = mock(FillerMask::class.java)
            `when`(mask.rows).thenReturn(rows)
            `when`(mask.columns).thenReturn(9)
            `when`(mask.getSlotAt(anyInt(), anyInt())).thenReturn(filler)
            val repaired = builder(rows).setMask(mask).setSlot(2, 6, control).build()
            assertSame(control, repaired.getSlot(2, 6), "Native middle content must win over background")
            assertSame(filler, repaired.getSlot(1, 1), "Background remains visible where no content exists")
        }
    }
}
