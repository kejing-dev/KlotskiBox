package io.embodit.klotskibox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** acceptance.mustPass M2 / M11 / M12: slide ranges (40 cases), clamping, soft stop vs blocked, axis lock, one switch. */
class DragTest {
    private val P = Layout.PITCH
    private var t = 0L

    /** Feed the same displacement for [n] samples 40 ms apart (the one-euro filter settles). Returns how many blocked events fired. */
    private fun feed(d: SlideDrag, x: Float, z: Float, n: Int = 15): Int { var k = 0; repeat(n) { t += 40; if (d.update(x, z, t)) k++ }; return k }

    @Test fun fortyRunsOnTheClassicStart() {
        val b = Puzzles.byId("classic")!!.board
        val want = mapOf(
            6 to mapOf(Dir.DOWN to 1), 7 to mapOf(Dir.DOWN to 1),
            8 to mapOf(Dir.RIGHT to 2), 9 to mapOf(Dir.LEFT to 2),
        )
        var cases = 0
        for (id in 0..9) for (d in Dir.entries) {
            assertEquals("tile $id $d", want[id]?.get(d) ?: 0, b.freeRun(id, d))
            cases++
        }
        assertEquals(40, cases)
    }

    @Test fun theTileIsClampedToItsRun() {
        val d = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t)
        feed(d, 0.30f, 0f)
        assertEquals(2 * P, d.offset, 1e-4f)
        assertEquals(Slide(8, Dir.RIGHT, 2), d.release())
    }

    @Test fun reachingTheEndOfTheSlotIsASoftStopNotBlocked() {
        val d = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t)
        val fired = feed(d, 2 * P + 0.01f, 0f, n = 30) // 1 cm past the end for 1.2 s
        assertEquals(0, fired)
        assertEquals(0, d.blockedCount)
        assertEquals(Slide(8, Dir.RIGHT, 2), d.release())
    }

    @Test fun pushingTwoCentimetresPastTheEndFor400msIsBlockedOnce() {
        val d = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t)
        feed(d, 2 * P, 0f)
        val fired = feed(d, 2 * P + 0.02f, 0f, n = 12) // 480 ms
        assertEquals(1, fired)
        assertEquals(Dir.RIGHT, d.blockedDir)
        assertEquals(0, feed(d, 2 * P + 0.03f, 0f, n = 12))
    }

    @Test fun releaseRoundsToTheNearestCell() {
        val a = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t); feed(a, 0.6f * P, 0f); assertEquals(Slide(8, Dir.RIGHT, 1), a.release())
        val b = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t); feed(b, 0.4f * P, 0f); assertEquals(0, b.release().cells)
        val c = SlideDrag(8, mapOf(Dir.RIGHT to 2), startMs = t); feed(c, 1.5f * P, 0f); assertEquals(Slide(8, Dir.RIGHT, 2), c.release())
    }

    @Test fun onlyOneAxisLocksAtOnce() {
        val d = SlideDrag(8, mapOf(Dir.RIGHT to 2))
        assertEquals(SlideDrag.Axis.X, d.axis)
        val z = SlideDrag(6, mapOf(Dir.DOWN to 1))
        assertEquals(SlideDrag.Axis.Z, z.axis)
    }

    @Test fun thirtyDegreeDragsLockTheRightAxisTenTimes() {
        val runs = mapOf(Dir.LEFT to 1, Dir.RIGHT to 1, Dir.UP to 2, Dir.DOWN to 1)
        val angles = listOf(30.0, -30.0, 150.0, 210.0, 25.0, 60.0, -60.0, 120.0, 240.0, 75.0)
        for (deg in angles) {
            val d = SlideDrag(1, runs, startMs = t)
            assertEquals(SlideDrag.Axis.NONE, d.axis)
            val r = 0.03f
            val a = Math.toRadians(deg)
            feed(d, (r * cos(a)).toFloat(), (r * sin(a)).toFloat())
            val want = if (kotlin.math.abs(cos(a)) >= kotlin.math.abs(sin(a))) SlideDrag.Axis.X else SlideDrag.Axis.Z
            assertEquals("angle $deg", want, d.axis)
        }
    }

    @Test fun aWrongLockSwitchesOnceOnly() {
        val d = SlideDrag(1, mapOf(Dir.LEFT to 1, Dir.RIGHT to 1, Dir.UP to 1, Dir.DOWN to 1), startMs = t)
        feed(d, 0.010f, 0.0f)
        assertEquals(SlideDrag.Axis.X, d.axis)
        feed(d, 0.010f, 0.020f)
        assertEquals(SlideDrag.Axis.Z, d.axis)
        assertTrue(d.switched)
        feed(d, 0.040f, 0.0f)
        assertEquals(SlideDrag.Axis.Z, d.axis)
    }

    @Test fun nothingFreeIsStuck() {
        assertTrue(SlideDrag(0, Dir.entries.associateWith { 0 }).stuck)
        assertFalse(SlideDrag(0, Dir.entries.associateWith { 0 }, canExit = true).stuck)
    }

    @Test fun caoCaoAtTheGapIsPulledOutPastTwoCentimetres() {
        val d = SlideDrag(0, Dir.entries.associateWith { 0 }, canExit = true, startMs = t)
        feed(d, 0f, 0.015f)
        assertFalse(d.exitPulled)
        feed(d, 0f, 0.025f)
        assertTrue(d.exitPulled)
        assertEquals(0, d.blockedCount)
    }
}
