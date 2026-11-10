package io.embodit.klotskibox.ui.board

import io.embodit.klotskibox.domain.Cell
import io.embodit.klotskibox.domain.FlowTest
import io.embodit.klotskibox.domain.Klotski
import io.embodit.klotskibox.domain.Puzzles
import io.embodit.klotskibox.ui.board.input.parseLoggedPath
import io.embodit.klotskibox.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」+ board-input README 必带单测: replay what the system reports as the hit node —
 * a cell's marker, its holder, any GLB mesh node under a tile holder — through the same name resolver the template uses
 * (TargetResolver.byName + resolvePath), then into GameFlow through the same [routeFlow] the volume uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val box = listOf("boxRoot", "tiltRoot", "sceneRoot", "root")
    private val board = Puzzles.byId("classic")!!.board

    private fun sq(path: List<String>) = BoardTargets.squareOf(BoardTargets.resolvePath(path), board)

    @Test fun everyCellMarkerAndHolderResolvesToItsCell() {
        for (r in 0 until Klotski.ROWS) for (c in 0 until Klotski.COLS) {
            val name = BoardNames.cell(Cell(c, r))
            assertEquals("$c,$r", sq(listOf("mk:$name:0", name) + box))
            assertEquals("$c,$r", sq(listOf(name) + box))
        }
    }

    @Test fun everyTileMeshNodeResolvesToTheTilesCell() {
        for (id in 0..9) {
            val holder = BoardNames.tile(id)
            val want = board.at[id].toString()
            assertEquals(want, sq(listOf("Tile_cao", holder) + box))         // the GLB's mesh node
            assertEquals(want, sq(listOf("primitive_0", "Tile_zu", holder) + box))
            assertEquals(want, sq(listOf(holder) + box))
        }
    }

    @Test fun theBoxModelAndStrayNamesResolveToNothing() {
        assertEquals("-", sq(listOf("BoxFloor", "boxModel") + box))
        assertEquals("-", sq(listOf("gapStrip") + box))
        assertNull(BoardTargets.resolvePath(listOf("tile:99") + box))  // out of range: a naming bug shows as "-"
        assertNull(BoardTargets.resolvePath(listOf("cell:4:0") + box))
        assertNull(BoardTargets.resolvePath(listOf("tile:1:2") + box))
        assertEquals("box", sq(listOf("box") + box))
    }

    /** Paths in the exact `path=` format of the template's TAP lines (replay of a headset input-log once there is one). */
    @Test fun replayLoggedPaths() {
        val logged = listOf(
            "mk:cell:1:4:0 < cell:1:4 < boxRoot < tiltRoot < sceneRoot < root" to "1,4",
            "Tile_cao < tile:0 < boxRoot < tiltRoot < sceneRoot < root" to "1,0",
            "tile:8 < boxRoot < tiltRoot < sceneRoot < root" to "0,4",
        )
        for ((p, want) in logged) assertEquals(p, want, BoardTargets.squareOf(resolvePath(parseLoggedPath(p), BoardTargets::targetOf), board))
    }

    @Test fun aPinchOnATileThenOnACellMovesItThroughTheSameRoute() = runTest {
        val h = FlowTest.Harness(this)
        h.flow.start(); runCurrent()
        routeFlow(h.flow, BoardTargets.resolvePath(listOf("Tile_zu", "tile:8") + box))
        routeFlow(h.flow, BoardTargets.resolvePath(listOf("mk:cell:1:4:0", "cell:1:4") + box))
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(Cell(2, 4), h.st.board.at[8])
        assertEquals(1, h.st.steps)
    }
}
