package io.embodit.klotskibox.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved?.let { SaveCodec.decode(SaveCodec.encode(it)) }
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engine = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { sounds += it },
        )
        val st get() = flow.state.value
    }

    private fun TestScope.settle(ms: Long = 1000) { runCurrent(); advanceTimeBy(ms); runCurrent() }

    @Test fun firstRunShowsTheClassicWithTheRulesAndTheFirstTouchEndsTheWelcome() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertEquals(Phase.IDLE, h.st.phase)
        assertTrue(h.st.welcome)
        assertEquals("classic", h.st.puzzle.id)
        h.flow.tapTile(8)
        assertFalse(h.st.welcome)
        assertTrue(h.settings.firstRunDone)
    }

    @Test fun aDragSnapsCountsOneStepAndGoingBackCancelsIt() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugDrag(8, Dir.RIGHT, 2f)
        assertEquals(Phase.SNAPPING, h.st.phase)
        settle()
        assertEquals(Cell(2, 4), h.st.board.at[8])
        assertEquals(1, h.st.steps)
        assertTrue(Sound.SNAP in h.sounds)
        h.flow.debugDrag(8, Dir.LEFT, 1f); settle()
        assertEquals(1, h.st.steps) // same tile again: still one step
        h.flow.debugDrag(8, Dir.LEFT, 1f); settle()
        assertEquals(0, h.st.steps) // back where it started: no step
        assertEquals(Cell(0, 4), h.st.board.at[8])
    }

    @Test fun lessThanHalfACellComesBack() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugDrag(8, Dir.RIGHT, 0.4f); settle()
        assertEquals(Cell(0, 4), h.st.board.at[8])
        assertEquals(0, h.st.steps)
    }

    @Test fun aTileThatCannotMoveShakesAndSaysSo() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.tapTile(0)
        assertEquals(Phase.BLOCKED, h.st.phase)
        assertEquals(Notice.STUCK, h.st.notice)
        assertNotNull(h.st.shake)
        assertEquals(listOf(Sound.BLOCK), h.sounds)
        settle(400)
        assertEquals(Phase.IDLE, h.st.phase)
    }

    @Test fun reachingTheEndIsQuietButPushingOnIsBlocked() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugDrag(8, Dir.RIGHT, 2.1f); settle()
        assertFalse(Sound.BLOCK in h.sounds)
        assertEquals(2, h.st.board.at[8].c)
        assertEquals(1, h.st.steps)
    }

    @Test fun pushingPastTheEndFiresBlockedOnce() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugPush(8, Dir.RIGHT, 2f, 500)
        runCurrent(); advanceTimeBy(420); runCurrent()
        assertEquals(Phase.BLOCKED, h.st.phase)
        assertEquals(Notice.BLOCKED, h.st.notice)
        settle(1000)
        assertEquals(1, h.sounds.count { it == Sound.BLOCK })
        assertEquals(Cell(2, 4), h.st.board.at[8]) // released at the end of its run: a normal snap
    }

    @Test fun tapTileThenItsArrowSlidesToTheEnd() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.tapTile(8)
        assertEquals(listOf(Dir.RIGHT), h.st.arrows!!.dirs)
        h.flow.tapCell(Cell(1, 4)); settle()
        assertEquals(Cell(2, 4), h.st.board.at[8])
        assertEquals(1, h.st.steps)
        h.flow.tapTile(6) // (1, 3): down is free now
        h.flow.tapCell(Cell(0, 0)) // not one of its cells: nothing moves, the arrows go
        assertNull(h.st.arrows)
        assertEquals(Cell(1, 3), h.st.board.at[6])
    }

    @Test fun undoTakesBackTheWholeStep() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugDrag(6, Dir.DOWN, 1f); settle()
        h.flow.debugDrag(6, Dir.RIGHT, 1f); settle() // a turn: still one step
        assertEquals(1, h.st.steps)
        assertEquals(Cell(2, 4), h.st.board.at[6])
        h.flow.undo(); settle()
        assertEquals(0, h.st.steps)
        assertEquals(Cell(1, 3), h.st.board.at[6])
        assertEquals(Notice.UNDID, h.st.notice)
    }

    @Test fun hintFollowedAllTheWaySolvesInTheMinimumAndTheExitEndsInTheResult() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.setHint(true)
        settle(500)
        assertEquals(1, h.st.hintCount)
        assertNotNull(h.st.hint)
        h.flow.autoSolve(50)
        advanceTimeBy(81 * 2000L); runCurrent()
        assertTrue(h.st.board.atExit)
        assertEquals(Phase.AT_EXIT, h.st.phase)
        assertEquals(81, h.st.steps)
        h.flow.exitNow()
        assertEquals(Phase.EXITING, h.st.phase)
        settle(GameFlow.EXIT_SLIDE_MS + GameFlow.EXIT_FALL_MS + GameFlow.EXIT_BOUNCE_MS + 100)
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(81, h.st.result!!.steps)
        assertEquals(81, h.st.result!!.min)
        assertTrue(h.st.result!!.newRecord)
        assertEquals(81, h.settings.best["classic"])
        assertTrue(Sound.DROP in h.sounds && Sound.SOLVED in h.sounds)
        h.flow.next(); runCurrent()
        assertEquals(1, h.st.puzzleIndex)
        assertEquals(0, h.st.steps)
    }

    @Test fun pullingCaoCaoAtTheGapLetsHimOut() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.debugSetup("张关关赵/张卒卒赵/马..黄/马曹曹黄/卒曹曹卒"); settle(200)
        assertEquals(Phase.AT_EXIT, h.st.phase)
        h.flow.debugDrag(0, Dir.DOWN, 0.4f, release = false)
        assertEquals(Phase.EXITING, h.st.phase)
    }

    @Test fun resetAsksOnlyWhenThereIsProgress() = runTest {
        val h = Harness(this); h.flow.start(); runCurrent()
        h.flow.requestReset(); runCurrent()
        assertNull(h.st.confirm)
        h.flow.debugDrag(8, Dir.RIGHT, 1f); settle()
        h.flow.requestReset()
        assertEquals(ConfirmKind.RESET, h.st.confirm)
        assertTrue(h.st.inputLocked)
        h.flow.tapTile(9); assertNull(h.st.arrows) // the volume is locked while the card is open
        h.flow.cancelConfirm(); assertNull(h.st.confirm)
        h.flow.requestReset(); h.flow.confirm(); runCurrent()
        assertEquals(0, h.st.steps)
        assertEquals(Cell(0, 4), h.st.board.at[8])
    }

    @Test fun aSaveComesBackAsS14AndAHeldTileSpringsBackOnBackground() = runTest {
        val store = MemStore()
        val h = Harness(this, store); h.flow.start(); runCurrent()
        h.flow.debugDrag(8, Dir.RIGHT, 2f); settle()
        h.flow.setHint(true); settle()
        h.flow.debugDrag(6, Dir.DOWN, 0.6f, release = false) // killed mid-drag
        h.flow.onBackground()
        val h2 = Harness(this, store); h2.flow.start(); settle()
        assertTrue(h2.st.restored)
        assertEquals("S06", h2.st.stateId) // hint state comes back (design S14: 提示开关态按存档恢复)
        assertEquals(1, h2.st.steps)
        assertEquals(Cell(2, 4), h2.st.board.at[8])
        assertEquals(Cell(1, 3), h2.st.board.at[6]) // the held tile is back at the start of its step
        assertFalse(h2.st.coachActive(h2.settings)) // coach cleared on restore
    }

    @Test fun saveCodecRoundTrips() {
        val g = SavedGame("zhihui", Puzzles.byId("zhihui")!!.board.at, listOf(Step(6, Cell(0, 2), Cell(0, 2 + 0))), 3, true, false)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("kb1|nope|x"))
    }
}
