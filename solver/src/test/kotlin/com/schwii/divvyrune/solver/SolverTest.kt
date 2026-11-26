package com.schwii.divvyrune.solver

import com.schwii.divvyrune.engine.LEVELS
import com.schwii.divvyrune.engine.buildGraph
import com.schwii.divvyrune.engine.check
import com.schwii.divvyrune.engine.decodeGrid
import com.schwii.divvyrune.engine.levelNamed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class SolverTest {
    @TestFactory
    fun `every level gets at least ten different solutions that pass Check`(): List<DynamicTest> = LEVELS.mapIndexed { i, level ->
        DynamicTest.dynamicTest("L${i + 1} ${level.name}") {
            val found = solveLevel(level, seed = 7L + i, want = 10, attempts = 30000)
            assertEquals(10, found.size, "only ${found.size}")
            // each is a real build: its text code decodes to a layout the game solves
            for (f in found) assertTrue(check(buildGraph(decodeGrid(f.code).tiles!!, level), level).solved, f.code)
            assertEquals(10, found.map { it.code }.toSet().size)
        }
    }

    @Test fun `the same seed finds the same solutions`() {
        val level = levelNamed("Fifth")
        assertEquals(solveLevel(level, 3, want = 5).map { it.code }, solveLevel(level, 3, want = 5).map { it.code })
    }

    @Test fun `routes bridge straight belts when they cross`() {
        val found = solveLevel(levelNamed("Seventeenth"), 5, want = 20)
        assertTrue(found.any { it.metrics.bridges > 0 })
    }

    @Test fun `share counts are made of twos and threes, and only twos without 3-way splits`() {
        assertEquals(listOf(9L, 12L, 16L, 18L), smooth(9, 18, threes = true))
        assertEquals(listOf(16L), smooth(9, 18, threes = false))
    }

    @Test fun `a level without 3-way splits gets no 3-way splitter`() {
        val level = levelNamed("Fifths by halves")
        for (f in solveLevel(level, 11, want = 10)) assertTrue(f.tiles.isNotEmpty() && buildGraph(f.tiles, level).nodes.none { it.succ.size == 3 })
    }
}
