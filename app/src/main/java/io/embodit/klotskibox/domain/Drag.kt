package io.embodit.klotskibox.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * One-dimensional constrained slide of one tile (design gameSpec.move, D27 / D45). Pure: the caller feeds the pointer's
 * displacement projected onto the box floor (metres, box axes: +x right, +z toward the near wall) with a timestamp.
 *
 *  - On press the free run along each axis is known; an axis with run 0 never takes part. One axis left -> it is locked
 *    at once; both -> locked by the dominant component once the movement passes [LOCK_M] (8 mm).
 *  - A wrong lock may be switched once: locked travel < 1/4 cell while the other axis has moved >= 1.5 cm.
 *  - The tile follows clamp(start + displacement, run): reaching the end of the run is a soft stop, never an error.
 *  - "Blocked" (S04: shake + 咔) only when nothing can move at press, or after pushing >= 1.5 cm past the end for
 *    >= 300 ms (once per push).
 *  - Release: the nearest cell (>= half a cell goes on, < half comes back).
 *  - Cao Cao at the gap may also be pulled out: >= 2 cm past the near end of his run (design exitPull).
 */
class SlideDrag(
    val tile: Int,
    /** Free run in cells: LEFT, RIGHT, UP (far), DOWN (near). */
    val runs: Map<Dir, Int>,
    val pitch: Float = Layout.PITCH,
    /** The tile is Cao Cao standing at the gap: a pull toward the user past his run means "let me out". */
    val canExit: Boolean = false,
    startMs: Long = 0L,
) {
    enum class Axis { NONE, X, Z }

    companion object {
        const val LOCK_M = 0.008f
        const val SWITCH_OTHER_M = 0.015f
        const val OVERPUSH_M = 0.015f
        const val OVERPUSH_MS = 300L
        const val EXIT_PULL_M = 0.02f
    }

    private val minX = -(runs[Dir.LEFT] ?: 0) * pitch
    private val maxX = (runs[Dir.RIGHT] ?: 0) * pitch
    private val minZ = -(runs[Dir.UP] ?: 0) * pitch
    private val maxZ = (runs[Dir.DOWN] ?: 0) * pitch
    private val canX = maxX - minX > 0f
    private val canZ = maxZ - minZ > 0f || canExit

    /** Nothing can move at press time (T4 / S04 "这块现在动不了"). */
    val stuck: Boolean = !canX && !canZ

    var axis: Axis = when {
        canX && !canZ -> Axis.X
        canZ && !canX -> Axis.Z
        else -> Axis.NONE
    }; private set
    var switched = false; private set
    /** Offset of the tile from its start along the locked axis (metres; x or z). */
    var offset = 0f; private set
    var blockedCount = 0; private set
    var exitPulled = false; private set
    /** The direction that was pushed against at the last blocked event. */
    var blockedDir: Dir? = null; private set

    private val fx = OneEuro()
    private val fz = OneEuro()
    private var overSince = -1L
    private var overFired = false
    private var lastMs = startMs

    /** Feed the raw projected displacement since the press. Returns true when this update fired a blocked event. */
    fun update(rawX: Float, rawZ: Float, nowMs: Long): Boolean {
        val dt = ((nowMs - lastMs).coerceAtLeast(1L)) / 1000f
        lastMs = nowMs
        val x = fx.filter(rawX, dt)
        val z = fz.filter(rawZ, dt)
        if (stuck) return false
        if (axis == Axis.NONE) {
            if (max(abs(x), abs(z)) < LOCK_M) return false
            axis = if (abs(x) >= abs(z)) Axis.X else Axis.Z
        }
        // one switch: the lock went the wrong way
        if (!switched) {
            val along = if (axis == Axis.X) x else z
            val other = if (axis == Axis.X) z else x
            val otherOk = if (axis == Axis.X) canZ else canX
            if (otherOk && abs(along) < pitch / 4 && abs(other) >= SWITCH_OTHER_M) {
                axis = if (axis == Axis.X) Axis.Z else Axis.X
                switched = true
                overSince = -1L; overFired = false
            }
        }
        val v = if (axis == Axis.X) x else z
        val lo = if (axis == Axis.X) minX else minZ
        val hi = if (axis == Axis.X) maxX else maxZ
        offset = v.coerceIn(lo, hi)
        if (canExit && axis == Axis.Z && v - maxZ >= EXIT_PULL_M) exitPulled = true
        // over-push past either end (soft stop first; S04 only when it keeps pushing)
        val over = if (v > hi) v - hi else if (v < lo) lo - v else 0f
        if (canExit && axis == Axis.Z && v > hi) return false
        if (over >= OVERPUSH_M) {
            if (overSince < 0) overSince = nowMs
            if (!overFired && nowMs - overSince >= OVERPUSH_MS) {
                overFired = true
                blockedCount++
                blockedDir = if (axis == Axis.X) (if (v > hi) Dir.RIGHT else Dir.LEFT) else (if (v > hi) Dir.DOWN else Dir.UP)
                return true
            }
        } else if (over < OVERPUSH_M / 2) { overSince = -1L; overFired = false }
        return false
    }

    /** Where the tile lands on release: a slide of whole cells (cells may be 0 = back to the start). */
    fun release(): Slide {
        val k = roundHalfAway(offset / pitch)
        return when {
            axis == Axis.X && k > 0 -> Slide(tile, Dir.RIGHT, k)
            axis == Axis.X && k < 0 -> Slide(tile, Dir.LEFT, -k)
            axis == Axis.Z && k > 0 -> Slide(tile, Dir.DOWN, k)
            axis == Axis.Z && k < 0 -> Slide(tile, Dir.UP, -k)
            else -> Slide(tile, Dir.RIGHT, 0)
        }
    }

    /** Offset as a box-floor vector (metres). */
    fun offsetXZ(): Pair<Float, Float> = when (axis) { Axis.X -> offset to 0f; Axis.Z -> 0f to offset; Axis.NONE -> 0f to 0f }

    private fun roundHalfAway(v: Float): Int = if (v >= 0) floor(v + 0.5f).toInt() else -floor(-v + 0.5f).toInt()
}

/** One-euro filter (Casiez et al.): smooths hand / ray jitter while staying responsive to fast moves. */
class OneEuro(private val minCutoff: Float = 2.0f, private val beta: Float = 8.0f, private val dCutoff: Float = 1.0f) {
    private var x: Float? = null
    private var dx = 0f

    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2f * Math.PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }

    fun filter(v: Float, dt: Float): Float {
        val prev = x ?: run { x = v; return v }
        val d = (v - prev) / dt
        dx += alpha(dCutoff, dt) * (d - dx)
        val cutoff = minCutoff + beta * abs(dx)
        val out = prev + alpha(cutoff, dt) * (v - prev)
        x = out
        return out
    }

}
