package io.embodit.klotskibox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * acceptance.mustPass M5 / M8 (D35, occlusionCheck): for every tilt stop, from every design eye plus the emulator's
 * camera-reset eye, the four corners of the counter plate are not hidden by the box or by any tile in any place it can
 * stand (lifted 2 mm); the plate does not hide any cell; everything stays inside the volume (D16).
 */
class LayoutTest {
    private fun allTileSpots(): List<Pair<V3, V3>> = Shape.entries.flatMap { s ->
        (0..Klotski.ROWS - s.h).flatMap { r -> (0..Klotski.COLS - s.w).map { c -> Cell(c, r) } }.map { cell ->
            val (lo, hi) = Layout(0).tileBox(s, cell)
            lo to (hi + V3(0f, Layout.LIFT, 0f))
        }
    }

    @Test fun plateCornersAreSeenFromEveryEyeAtEveryTilt() {
        val spots = allTileSpots()
        var rays = 0
        for (tilt in Layout.TILTS) {
            val lay = Layout(tilt)
            val parts = lay.boxParts() + spots
            for ((name, eye) in lay.eyes()) for (corner in lay.plateCorners()) {
                val end = eye.lerp(corner, 0.995f)
                for ((lo, hi) in parts) {
                    assertFalse("tilt $tilt eye $name corner $corner hidden by $lo..$hi", Occlusion.hits(lay, eye, end, lo, hi))
                }
                rays++
            }
        }
        assertEquals(Layout.TILTS.size * 7 * 4, rays)
    }

    @Test fun thePlateHidesNoCell() {
        for (tilt in Layout.TILTS) {
            val lay = Layout(tilt)
            val c = lay.plateCentre()
            for ((name, eye) in lay.eyes()) for (r in 0 until Klotski.ROWS) for (col in 0 until Klotski.COLS) {
                val target = lay.toScene(Layout.cellCentre(Cell(col, r)) + V3(0f, Layout.TILE_T, 0f))
                // segment eye -> cell top crosses the plate's plane z = c.z ?
                if ((eye.z - c.z) * (target.z - c.z) > 0) continue
                val u = (c.z - eye.z) / (target.z - eye.z)
                val p = eye.lerp(target, u)
                val inside = kotlin.math.abs(p.x - c.x) <= Layout.PLATE_W / 2 && kotlin.math.abs(p.y - c.y) <= Layout.PLATE_H / 2
                assertFalse("tilt $tilt eye $name cell $col,$r behind the plate", inside)
            }
        }
    }

    @Test fun everythingStaysInsideTheVolume() {
        for (tilt in Layout.TILTS) {
            val lay = Layout(tilt)
            val pts = ArrayList<V3>()
            for ((lo, hi) in lay.boxParts()) for (x in listOf(lo.x, hi.x)) for (y in listOf(lo.y, hi.y)) for (z in listOf(lo.z, hi.z)) pts += lay.toScene(V3(x, y, z))
            // tiles lifted, on every spot
            for ((lo, hi) in allTileSpots()) for (y in listOf(lo.y, hi.y)) for (z in listOf(lo.z, hi.z)) pts += lay.toScene(V3(lo.x, y, z))
            pts += lay.plateCorners()
            pts += V3(0f, lay.wedgeFarH, lay.pivotZ - lay.wedgeDepth); pts += V3(Layout.WEDGE_W / 2, 0f, lay.pivotZ)
            val land = lay.landing(); val h = Layout.size(Shape.BIG)
            pts += land + V3(h.x / 2, h.y / 2, h.z / 2); pts += land - V3(h.x / 2, h.y / 2, h.z / 2)
            pts += lay.toScene(lay.exitSlideEnd())
            for (p in pts) {
                assertTrue("tilt $tilt $p x", kotlin.math.abs(p.x) <= Layout.VOL_W / 2 + 1e-4f)
                assertTrue("tilt $tilt $p y", p.y >= -1e-4f && p.y <= Layout.VOL_H + 1e-4f)
                assertTrue("tilt $tilt $p z", kotlin.math.abs(p.z) <= Layout.VOL_D / 2 + 1e-4f)
            }
            // Cao Cao's landing clears the wedge's near face and the box
            assertTrue("tilt $tilt landing", land.z - h.z / 2 > lay.pivotZ + 0.02f || tilt >= 60)
        }
    }

    @Test fun tiltIsAPureRotationAndTheFarEndRises() {
        for (tilt in Layout.TILTS) {
            val lay = Layout(tilt)
            val p = V3(0.05f, 0.01f, -0.12f)
            val back = lay.toBox(lay.toScene(p))
            assertEquals(p.x, back.x, 1e-5f); assertEquals(p.y, back.y, 1e-5f); assertEquals(p.z, back.z, 1e-5f)
            val far = lay.toScene(V3(0f, 0f, -Layout.INNER_D / 2)); val near = lay.toScene(V3(0f, 0f, Layout.INNER_D / 2))
            if (tilt > 0) assertTrue(far.y > near.y) else assertEquals(far.y, near.y, 1e-5f)
        }
    }

    @Test fun projectingADragDownTheSlopeGivesBoxZ() {
        val lay = Layout(30)
        val (x, z) = lay.project(lay.boxZ * 0.05f)
        assertEquals(0f, x, 1e-6f); assertEquals(0.05f, z, 1e-5f)
        val (x2, z2) = lay.project(V3(0.03f, 0f, 0f))
        assertEquals(0.03f, x2, 1e-6f); assertEquals(0f, z2, 1e-6f)
    }
}
