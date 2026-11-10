package io.embodit.klotskibox.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/**
 * All metres of the volume (design spatialLayout, container.volume), for one tilt. Scene space: origin on the volume
 * FLOOR centre, +X right, +Y up, +Z toward the user (the design's "volume centre" numbers are these minus HALF_H).
 *
 * ```
 * sceneRoot (volume floor)
 * ├ wedge (static, rebuilt per tilt)
 * └ tiltRoot (pivot on the box's near-bottom edge, rotated +θ about X: the far end rises)
 *   └ boxRoot (inner floor centre of the box, +Z toward the near wall)
 * ```
 */
class Layout(val tiltDeg: Int = Settings.DEFAULT_TILT) {
    companion object {
        // design gameSpec.board / pieces
        const val PITCH = 0.07f
        const val INNER_W = 0.28f
        const val INNER_D = 0.35f
        const val WALL = 0.025f
        const val WALL_H = 0.022f
        const val FLOOR_T = 0.015f
        const val OUTER_W = INNER_W + 2 * WALL // 0.33
        const val OUTER_D = INNER_D + 2 * WALL // 0.40
        const val GAP_W = 0.144f
        const val GAP_X = GAP_W / 2
        const val TILE_T = 0.028f
        const val CLEAR = 0.003f
        const val LIFT = 0.002f
        const val WEDGE_NEAR = 0.04f
        const val WEDGE_W = 0.36f
        /** The wedge's depth when the box stands upright (90°): a back block. */
        const val WEDGE_MIN_D = 0.05f

        // container.volume (deviation: height 0.34 -> 0.56 for the 90° tilt option)
        const val VOL_W = 0.48f
        const val VOL_H = 0.56f
        const val VOL_D = 0.62f
        const val HALF_H = VOL_H / 2

        // counter plate (design spatialLayout.counterPlate)
        const val PLATE_W = 0.26f
        const val PLATE_H = 0.045f
        const val PLATE_PX_W = 1024
        const val PLATE_PX_H = 180
        /** Usable text width on the plate (0.24 m of 0.26 m). */
        const val PLATE_TEXT_PX = 944
        const val PLATE_GAP = 0.025f

        const val EXIT_SLIDE_M = 0.165f
        const val ARROW = 0.06f

        val TILTS = listOf(0, 30, 45, 60, 90)

        fun size(s: Shape) = V3(s.w * PITCH - 2 * CLEAR, TILE_T, s.h * PITCH - 2 * CLEAR)

        /** The tile's centre (x, z) in the box frame for its top-left [cell]. */
        fun tileCentre(s: Shape, cell: Cell, lift: Float = 0f): V3 =
            V3((cell.c + s.w / 2f - 2f) * PITCH, TILE_T / 2 + lift, (cell.r + s.h / 2f - 2.5f) * PITCH)

        /** A cell's floor centre in the box frame (design boxFrame: x = (c - 1.5) * 0.07, z = (r - 2) * 0.07). */
        fun cellCentre(cell: Cell): V3 = V3((cell.c - 1.5f) * PITCH, 0f, (cell.r - 2f) * PITCH)

        /**
         * Eyes for the occlusion checks (design occlusionCheck.eyePositions, relative to the volume CENTRE): sitting /
         * standing straight ahead and 30° left / right, plus the emulator's camera-reset eye measured on the Mac (almost
         * level, ~4° down, ~1.9 m away; board-game template "防自遮挡的几何").
         */
        val EYES_FROM_CENTRE: List<Pair<String, V3>> = listOf(
            "sit" to V3(0f, 0.30f, 0.80f), "stand" to V3(0f, 0.55f, 0.80f),
            "sitL30" to V3(-0.40f, 0.30f, 0.69f), "sitR30" to V3(0.40f, 0.30f, 0.69f),
            "standL30" to V3(-0.40f, 0.55f, 0.69f), "standR30" to V3(0.40f, 0.55f, 0.69f),
            "emulatorReset" to V3(0f, 0.13f, 1.90f),
        )
    }

    val theta: Float = (tiltDeg * PI / 180).toFloat()
    private val cosT = cos(theta)
    private val sinT = sin(theta)

    /** Pivot z: near enough to keep the flat box inside the volume's back (design pivot z = +0.07 at 25°). */
    val pivotZ: Float = max(0.07f, -VOL_D / 2 + 0.01f + OUTER_D * cosT)
    /** tiltRoot origin = the box's near-bottom (outer) edge. */
    val tiltOrigin = V3(0f, WEDGE_NEAR, pivotZ)
    /** boxRoot inside tiltRoot: inner floor centre. */
    val boxOffset = V3(0f, FLOOR_T, -OUTER_D / 2)

    /** Box frame -> scene. */
    fun toScene(p: V3): V3 {
        val q = p + boxOffset
        val y = q.y * cosT - q.z * sinT
        val z = q.y * sinT + q.z * cosT
        return tiltOrigin + V3(q.x, y, z)
    }

    /** Scene -> box frame. */
    fun toBox(p: V3): V3 {
        val q = p - tiltOrigin
        val y = q.y * cosT + q.z * sinT
        val z = -q.y * sinT + q.z * cosT
        return V3(q.x, y, z) - boxOffset
    }

    /** Scene direction of box +Z (toward the near wall, down the slope) and of the floor normal. */
    val boxZ = V3(0f, -sinT, cosT)
    val boxUp = V3(0f, cosT, sinT)

    /** A pointer displacement in volume axes -> (x, z) on the box floor. */
    fun project(d: V3): Pair<Float, Float> = d.x to d.dot(boxZ)

    /** Wedge (static): top surface under the box, bottom on the floor. Depth along the floor, far height. */
    val wedgeDepth: Float = max(OUTER_D * cosT, WEDGE_MIN_D)
    val wedgeFarH: Float = WEDGE_NEAR + OUTER_D * sinT

    /** Far wall top (outer edge) centre, scene. */
    fun farWallTop(): V3 = toScene(V3(0f, WALL_H, -OUTER_D / 2 + 0.0f))

    /** Counter plate centre (scene): straight above the far wall top, upright, facing +Z (only yaw; no head pose in Shared Space). */
    fun plateCentre(): V3 { val t = farWallTop(); return V3(0f, t.y + PLATE_GAP + PLATE_H / 2, t.z - 0.005f) }

    fun plateCorners(): List<V3> { val c = plateCentre(); return listOf(V3(-PLATE_W / 2, -PLATE_H / 2, 0f), V3(PLATE_W / 2, -PLATE_H / 2, 0f), V3(-PLATE_W / 2, PLATE_H / 2, 0f), V3(PLATE_W / 2, PLATE_H / 2, 0f)).map { c + it } }

    /** Where Cao Cao comes to rest on the volume floor (design spatialLayout.landing): in front of the gap, lying flat. */
    fun landing(): V3 = V3(0f, TILE_T / 2, pivotZ + 0.10f)

    /**
     * Cao Cao's centre once he has slid out of the gap (box frame), the start of his fall: 16.5 cm down the slot, or less
     * when the box is steep (at 90° the gap faces the floor; he stops 7.5 cm above it and topples onto the table).
     */
    fun exitSlideEnd(): V3 {
        val base = tileCentre(Shape.BIG, Klotski.EXIT)
        var z = base.z + EXIT_SLIDE_M
        if (sinT > 1e-3f) {
            // scene y of the centre at box z: WEDGE_NEAR + (TILE_T / 2 + FLOOR_T) cos - (z - OUTER_D / 2) sin
            val zMax = OUTER_D / 2 + (WEDGE_NEAR + (TILE_T / 2 + FLOOR_T) * cosT - 0.075f) / sinT
            z = kotlin.math.min(z, max(base.z, zMax))
        }
        return V3(base.x, base.y, z)
    }

    /** Eyes in scene space. */
    fun eyes(): List<Pair<String, V3>> = EYES_FROM_CENTRE.map { (n, e) -> n to (e + V3(0f, HALF_H, 0f)) }

    /** Axis-aligned boxes (box frame) of the wooden box parts: floor, far / left / right walls, two near wall pieces. */
    fun boxParts(): List<Pair<V3, V3>> {
        val hw = OUTER_W / 2; val hd = OUTER_D / 2
        val gapNear = INNER_D / 2
        return listOf(
            V3(-hw, -FLOOR_T, -hd) to V3(hw, 0f, hd),
            V3(-hw, 0f, -hd) to V3(hw, WALL_H, -INNER_D / 2),
            V3(-hw, 0f, -INNER_D / 2) to V3(-INNER_W / 2, WALL_H, gapNear),
            V3(INNER_W / 2, 0f, -INNER_D / 2) to V3(hw, WALL_H, gapNear),
            V3(-hw, 0f, gapNear) to V3(-GAP_X, WALL_H, hd),
            V3(GAP_X, 0f, gapNear) to V3(hw, WALL_H, hd),
        )
    }

    /** A tile's box-frame AABB. */
    fun tileBox(s: Shape, cell: Cell): Pair<V3, V3> {
        val c = tileCentre(s, cell); val h = size(s) * 0.5f
        return (c - h) to (c + h)
    }
}

/** Segment-vs-oriented-box test helpers for the occlusion unit tests (box given in the box frame of [lay]). */
object Occlusion {
    /** Does the scene segment a -> b pass through the box-frame AABB (lo, hi)? */
    fun hits(lay: Layout, a: V3, b: V3, lo: V3, hi: V3): Boolean {
        // transform the segment into the box frame: inverse of Layout.toScene
        fun inv(p: V3): V3 {
            val q = p - lay.tiltOrigin
            val c = cos(lay.theta); val s = sin(lay.theta)
            val y = q.y * c + q.z * s
            val z = -q.y * s + q.z * c
            return V3(q.x, y, z) - lay.boxOffset
        }
        val p = inv(a); val q = inv(b)
        var t0 = 0f; var t1 = 1f
        val d = q - p
        val ps = floatArrayOf(p.x, p.y, p.z); val ds = floatArrayOf(d.x, d.y, d.z)
        val los = floatArrayOf(lo.x, lo.y, lo.z); val his = floatArrayOf(hi.x, hi.y, hi.z)
        for (i in 0..2) {
            if (kotlin.math.abs(ds[i]) < 1e-9f) { if (ps[i] < los[i] || ps[i] > his[i]) return false; continue }
            var ta = (los[i] - ps[i]) / ds[i]; var tb = (his[i] - ps[i]) / ds[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = kotlin.math.min(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }
}
