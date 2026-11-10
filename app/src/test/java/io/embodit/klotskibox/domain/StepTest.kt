package io.embodit.klotskibox.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** acceptance.mustPass M2 (part): the six step-count cases of design gameSpec.stepCounting. */
class StepTest {
    private val a = Cell(0, 0); private val b = Cell(0, 1); private val c = Cell(1, 1); private val d = Cell(1, 2)

    @Test fun sameTileThreeDragsIsOneStep() {
        var h = mergeStep(emptyList(), Step(6, a, b))
        h = mergeStep(h, Step(6, b, c))
        h = mergeStep(h, Step(6, c, d))
        assertEquals(listOf(Step(6, a, d)), h)
    }

    @Test fun aTurnIsOneStep() {
        val h = mergeStep(mergeStep(emptyList(), Step(6, a, b)), Step(6, b, c))
        assertEquals(1, h.size)
        assertEquals(Step(6, a, c), h[0])
    }

    @Test fun backToTheStartIsNoStep() {
        val h = mergeStep(mergeStep(emptyList(), Step(6, a, b)), Step(6, b, a))
        assertEquals(0, h.size)
    }

    @Test fun anotherTileIsOneMore() {
        val h = mergeStep(mergeStep(emptyList(), Step(6, a, b)), Step(7, c, d))
        assertEquals(2, h.size)
    }

    @Test fun backToTheStartAfterOtherStepsOnlyRemovesThatStep() {
        var h = mergeStep(emptyList(), Step(7, c, d))
        h = mergeStep(h, Step(6, a, b))
        h = mergeStep(h, Step(6, b, a))
        assertEquals(listOf(Step(7, c, d)), h)
    }

    @Test fun alternatingTilesCountEveryChange() {
        var h = emptyList<Step>()
        h = mergeStep(h, Step(6, a, b)); h = mergeStep(h, Step(7, c, d)); h = mergeStep(h, Step(6, b, a))
        assertEquals(3, h.size)
    }
}
