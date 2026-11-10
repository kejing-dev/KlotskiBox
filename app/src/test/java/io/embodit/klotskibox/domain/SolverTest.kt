package io.embodit.klotskibox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** acceptance.mustPass M1: minimum steps, every state can still reach the gap, the hint is a first step of a shortest path. */
class SolverTest {
    @Test fun classicIs81UnderTheDesignStepCount() {
        val s = Solver.build(Puzzles.byId("classic")!!.board)
        assertEquals(81, s.minSteps)
        println("classic: ${s.states} states, min ${s.minSteps}")
    }

    @Test fun everyShippedLayoutMatchesItsTableAndPublishedMinimum() {
        // published minimums (one tile any distance = one step): 横刀立马 81, 左右布兵 54, 齐头并进 60, 指挥若定 70, 桃花园中 70, 兵分三路 72
        val published = mapOf("classic" to 81, "zuoyou" to 54, "qitou" to 60, "zhihui" to 70, "taohua" to 70, "bingfen" to 72)
        assertEquals(published.keys, Puzzles.ALL.map { it.id }.toSet())
        for (p in Puzzles.ALL) {
            val s = Solver.build(p.board)
            assertEquals(p.id, p.minSteps, s.minSteps)
            assertEquals(p.id, published[p.id], s.minSteps)
            println("${p.id} ${p.zh}: ${s.states} states, min ${s.minSteps}")
        }
    }

    @Test fun everyReachableStateCanStillReachTheGap() {
        for (p in Puzzles.ALL) {
            val s = Solver.build(p.board)
            assertTrue(p.id, s.allReachGoal())
            assertTrue(p.id, s.states > 10_000)
        }
    }

    @Test fun hintIsTheFirstStepOfAShortestPathAndFollowingItSolvesInMin() {
        val p = Puzzles.byId("classic")!!
        val s = Solver.build(p.board)
        var b = p.board
        var steps = 0
        while (!b.atExit) {
            val h = s.hint(b)
            assertNotNull(h)
            val before = s.distance(b.key())
            // the segments replay the unit path of the hinted tile
            var cur = b
            for (seg in h!!.segments) cur = cur.apply(seg.copy(tile = h.tile))
            assertEquals(h.to, cur.at[h.tile])
            assertEquals(before - 1, s.distance(cur.key()))
            b = cur
            steps++
            assertTrue(steps <= 81)
        }
        assertEquals(81, steps)
    }

    @Test fun layoutsUseTheShapesTheModelsHave() {
        for (p in Puzzles.ALL) for (t in p.board.tiles) {
            val want = when (t.who) { Who.CAO -> Shape.BIG; Who.GUAN -> Shape.WIDE; Who.ZU -> Shape.SMALL; else -> Shape.TALL }
            assertEquals("${p.id} ${t.who}", want, t.shape)
        }
    }
}
