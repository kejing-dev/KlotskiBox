package io.embodit.klotskibox.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.klotskibox.domain.Cell
import io.embodit.klotskibox.domain.Dir
import io.embodit.klotskibox.domain.GameFlow
import io.embodit.klotskibox.domain.Klotski
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.LiveDrag
import io.embodit.klotskibox.domain.Phase
import io.embodit.klotskibox.domain.PlayState
import io.embodit.klotskibox.domain.Settings
import io.embodit.klotskibox.domain.Shape
import io.embodit.klotskibox.domain.V3
import io.embodit.klotskibox.domain.Who
import io.embodit.klotskibox.platform.TAG
import io.embodit.klotskibox.ui.board.input.GazeTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** The GLB templates (tools/build_models.py). */
internal class Models(val box: Entity, val wedges: Map<Int, Entity>, val tiles: Map<Who, Entity>)

/**
 * Every entity of the volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves (tiles,
 * the held tile, Cao Cao leaving the box, arrows, glows, the ghost) is placed in [onFrame], driven by BoardFrameSystem.
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ wedge_<tilt> (static, one enabled), counter plate (upright, facing +Z), key light
 *   └ tiltRoot (the box's near-bottom edge, rotated +θ about X)
 *     └ boxRoot (inner floor centre): box.glb (no hits), 20 cell holders "cell:c:r" (collider + Interactable + system
 *        HoverEffect, a <= 5 mm marker child each), 10 tiles "tile:<id>" (the GLB clone root is the holder), glows,
 *        arrows, the hint ghost, the gap strip, the S10-only "box" collider
 * ```
 */
internal class BoardScene(private val live: LiveDrag, private val scope: CoroutineScope) {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boxRoot = Entity().apply { setName("boxRoot") }

    private var res: SceneResources? = null
    private var models: Models? = null
    private var fallback: Entity? = null
    private var built = false

    private var lay = Layout()
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f
    private var panelOpen = true
    private var plateText = ""
    private var plateShown: String? = null
    private var plateBusy = false

    private val tiles = ArrayList<Entity>()
    private val tileWho = ArrayList<Who>()
    private val halos = ArrayList<ModelEntity>()
    private val arrows = ArrayList<ModelEntity>()
    private val ghosts = HashMap<Who, Entity>()
    private var strip: ModelEntity? = null
    private var plate: ModelEntity? = null
    private var plateMat: UnlitMaterial? = null
    private var plateTex: TextureResource? = null
    private var boxHolder: Entity? = null
    private var boxColliderOn = false
    private val wedges = HashMap<Int, Entity>()
    private var fading = false
    private val flatQ = Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())

    @Volatile private var hover: String? = null
    @Volatile private var hoverAt = 0L

    /** D50 hover: the holder under a resting pointer (null = none). A hint only, never a state change. */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boxRoot)
    }

    val ready: Boolean get() = built

    fun setRegion(halfY: Float) {
        if (abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    /** S00 stand-in: one plain slab where the box goes, shown until the models are in (design lifecycle "首帧兜底"). */
    fun showFallback(slab: MeshResource, material: Material) {
        if (built || fallback != null) return
        val e = ModelEntity(slab, material).apply { setName("fallbackBox") }
        place(e, V3(0f, -Layout.FLOOR_T / 2 + 0.01f, 0f))
        boxRoot.addChild(e)
        fallback = e
        layout(force = true)
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    fun install(r: SceneResources, m: Models, tileList: List<Who>) {
        if (built) { r.close(); return }
        res = r; models = m
        val t0 = SystemClock.elapsedRealtime()
        boxRoot.addChild(clone(m.box).apply { setName("boxModel") })
        for ((deg, w) in m.wedges) { val e = clone(w).apply { setName("wedge:$deg"); enabled = false }; sceneRoot.addChild(e); wedges[deg] = e }
        // gaze targets (board-input template): the box takes no hits; every cell is a holder with a visible <= 5 mm marker
        GazeTargets.cells(
            boxRoot,
            (0 until Klotski.ROWS).flatMap { rr -> (0 until Klotski.COLS).map { c -> Cell(c, rr) } }
                .associate { BoardNames.cell(it) to Layout.cellCentre(it).let { v -> Vector3(v.x, v.y, v.z) } },
            r.cellShape, r.physics, r.markerParts, r.marker,
        )
        // S10 only: a pinch anywhere on the box reopens the panel (collider added while the panel is closed)
        boxHolder = Entity().apply { setName(BoardNames.BOX); boxRoot.addChild(this); place(this, V3(0f, (Layout.WALL_H - Layout.FLOOR_T) / 2, 0f)) }
        tileList.forEachIndexed { id, who ->
            val shape = shapeOf(who)
            val e = clone(m.tiles[who]!!).apply { setName(BoardNames.tile(id)) }
            GazeTargets.install(e, r.tileShapes[shape]!!, r.physics)
            boxRoot.addChild(e)
            tiles += e; tileWho += who
            val h = ModelEntity(r.haloPlanes[shape]!!, r.haloByShape[shape]!!).apply { setName("halo:$id"); enabled = false }
            h.transform().setQuaternion(flatQ)
            boxRoot.addChild(h); halos += h
        }
        for (who in Who.entries) {
            val g = clone(m.tiles[who]!!).apply { setName("ghost:${who.name}"); enabled = false }
            g.components.set(OpacityControllerComponent(0.42f))
            boxRoot.addChild(g); ghosts[who] = g
        }
        repeat(6) { i -> arrows += ModelEntity(r.arrowPlane, r.arrowMat).apply { setName("arrow:$i"); enabled = false; boxRoot.addChild(this) } }
        strip = ModelEntity(r.stripPlane, r.stripWeak).apply {
            setName("gapStrip"); transform().setQuaternion(flatQ)
            place(this, V3(0f, 0.0012f, Layout.INNER_D / 2 + 0.013f)); boxRoot.addChild(this)
        }
        plateMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.BACK); setApplyToneMapping(false) }.also { r.keep(it) }
        plate = ModelEntity(r.platePlane, plateMat!!).apply { setName("counterPlate"); sceneRoot.addChild(this) }
        buildLight()
        fallback?.removeFromParent(); fallback = null
        built = true
        layout(force = true)
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
        onFrame(0f)
    }

    private fun shapeOf(who: Who) = when (who) { Who.CAO -> Shape.BIG; Who.GUAN -> Shape.WIDE; Who.ZU -> Shape.SMALL; else -> Shape.TALL }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.85f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    private fun layout(force: Boolean = false) {
        if (!force && lay.tiltDeg == settings.tilt) return
        lay = Layout(settings.tilt)
        place(tiltRoot, lay.tiltOrigin)
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), lay.theta))
        place(boxRoot, lay.boxOffset)
        for ((deg, w) in wedges) { w.enabled = deg == lay.tiltDeg; if (w.enabled) place(w, V3(0f, 0f, lay.pivotZ)) }
        plate?.let { place(it, lay.plateCentre()) }
        Log.i(TAG, "LAYOUT tilt=${lay.tiltDeg} pivotZ=${lay.pivotZ} plate=${lay.plateCentre()} landing=${lay.landing()} farTop=${lay.farWallTop()}")
    }

    fun apply(next: PlayState, s: Settings, panelOpen: Boolean, plateText: String) {
        state = next
        val changed = s != settings
        settings = s
        this.panelOpen = panelOpen
        this.plateText = plateText
        if (!built) return
        if (changed) layout()
        onFrame(0f)
    }

    private fun refreshPlate() {
        val text = plateText
        if (text == plateShown || plateBusy || text.isEmpty()) return
        plateBusy = true
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.plate(text)) }.getOrNull() }
            if (tex != null) {
                plateMat?.setBaseColorTexture(tex)
                plateTex?.let { old -> runCatching { old.close() } }
                plateTex = tex
                plateShown = text
                Log.i(TAG, "PLATE \"$text\"")
            }
            plateBusy = false
        }
    }

    // ---------------------------------------------------------------- per frame

    private fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return 1f - (1f - u) * (1f - u) * (1f - u) }

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.let { f -> min(e, f) } ?: e }
        refreshPlate()

        // S10: the box answers a pinch too (design input: 盒底板临时带碰撞体)
        val wantBox = !panelOpen
        if (wantBox != boxColliderOn) {
            boxColliderOn = wantBox
            boxHolder?.let { b ->
                if (wantBox) GazeTargets.install(b, r.boxShape, r.physics)
                else { b.components.remove(CollisionComponent::class.java); b.components.remove(InteractableComponent::class.java) }
            }
        }

        // ---------------- tiles
        val b = st.board
        val anim = st.anim
        val shake = st.shake
        val fade = st.fadeAt.takeIf { it > 0 && now - it < GameFlow.FADE_MS }
        for (id in tiles.indices) {
            val e = tiles[id]
            val shape = b.tile(id).shape
            var p = Layout.tileCentre(shape, b.at[id])
            var q: Quat? = null
            if (anim != null && anim.tile == id) {
                val t = elapsed(anim.startedAt).toFloat() / anim.durationMs
                p = anim.from.lerp(anim.to, ease(t))
            } else if (live.tile == id) {
                p = p + V3(live.x, Layout.LIFT, live.z)
            } else if (st.held == id) {
                p = p + V3(0f, Layout.LIFT, 0f)
            }
            if (shake != null && shake.tile == id && !reduced) {
                val e2 = elapsed(shake.at)
                if (e2 < GameFlow.SHAKE_MS || st.freezeMs != null) {
                    val k = 0.002f * sin(e2 / 50f * 2 * PI.toFloat()).let { if (st.freezeMs != null) 1f else it }
                    p = when (shake.dir?.axisX) { null, true -> p + V3(k, 0f, 0f); false -> p + V3(0f, 0f, k) }
                }
            }
            if (id == Klotski.CAO && (st.phase == Phase.EXITING || st.phase == Phase.RESULT)) {
                val (pp, qq) = exitPose(st, now, reduced)
                p = pp; q = qq
            }
            place(e, p)
            e.transform().setQuaternion(q ?: Quat.identity())
            if (fade != null) {
                fading = true
                val k = (elapsed(fade).toFloat() / GameFlow.FADE_MS).coerceIn(0.05f, 1f)
                e.components.set(OpacityControllerComponent(k))
            }
        }
        if (fade == null && fading) {
            // D24 lesson: an opacity component of 1.0 still renders blended; remove it
            fading = false
            tiles.forEach { it.components.remove(OpacityControllerComponent::class.java) }
        }

        // ---------------- glows: held tile, coach (first 3 steps: the movable tiles), hover
        val coach = st.coachActive(settings)
        val movable = if (coach) b.movable().toSet() else emptySet()
        val hoverTile = hover?.takeIf { now - hoverAt < 1500 }?.let { BoardNames.tileOf(it) }
        for (id in halos.indices) {
            val h = halos[id]
            val shape = b.tile(id).shape
            val mat: Material? = when {
                st.phase == Phase.EXITING || st.phase == Phase.RESULT -> null
                st.held == id || live.tile == id -> r.haloByShape[shape]
                id in movable && st.anim == null -> r.coachByShape[shape]
                hoverTile == id -> r.hoverByShape[shape]
                else -> null
            }
            if (mat == null) { h.enabled = false; continue }
            h.components[ModelComponent::class.java]?.materials?.set(0, mat)
            val c = tiles[id].transform().position
            h.transform().setPosition(Vector3(c.x, 0.0009f, c.z))
            h.enabled = true
        }

        // ---------------- arrows (press / tap) and the hint
        var used = 0
        fun arrow(at: V3, d: Dir, mat: Material) {
            val a = arrows.getOrNull(used++) ?: return
            a.components[ModelComponent::class.java]?.materials?.set(0, mat)
            place(a, at)
            val yaw = when (d) { Dir.UP -> 0.0; Dir.DOWN -> PI; Dir.LEFT -> PI / 2; Dir.RIGHT -> -PI / 2 }
            a.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw.toFloat()).times(flatQ))
            a.enabled = true
        }
        val ar = st.arrows?.takeIf { it.until > now || st.freezeMs != null }
        if (ar != null && st.phase in setOf(Phase.IDLE, Phase.AT_EXIT, Phase.DRAGGING, Phase.BLOCKED)) {
            for (d in ar.dirs) arrow(arrowSpot(b, ar.tile, d), d, if (ar.grey == d) r.arrowGrey else r.arrowMat)
            if (ar.exit) arrow(V3(0f, 0.0016f, Layout.OUTER_D / 2 + 0.035f), Dir.DOWN, r.arrowMat)
        } else if (st.phase == Phase.AT_EXIT && st.anim == null) {
            // S07: one outward arrow under Cao Cao's near edge
            arrow(V3(0f, 0.0016f, Layout.OUTER_D / 2 + 0.035f), Dir.DOWN, r.arrowMat)
        }
        val hint = st.hint?.takeIf { st.hintOn && st.phase in setOf(Phase.IDLE, Phase.AT_EXIT, Phase.DRAGGING, Phase.BLOCKED) }
        ghosts.values.forEach { it.enabled = false }
        if (hint != null) {
            val seg = hint.segments.getOrNull(st.hintSeg)
            if (seg != null) {
                val who = tileWho[hint.tile]
                val shape = b.tile(hint.tile).shape
                val from = b.at[hint.tile]
                val to = Cell(from.c + seg.dir.dc * seg.cells, from.r + seg.dir.dr * seg.cells)
                ghosts[who]?.let { g -> place(g, Layout.tileCentre(shape, to)); g.enabled = true }
                if (ar == null) arrow(arrowSpot(b, hint.tile, seg.dir), seg.dir, r.arrowMat)
            }
        }
        for (i in used until arrows.size) arrows[i].enabled = false

        // ---------------- the gap strip: weak always, strong at S07, two pulses at S01, off while he leaves
        strip?.let { s ->
            val pulse = st.welcome && now - st.pulseAt < 1000 && ((now - st.pulseAt) / 250) % 2 == 0L
            s.enabled = st.phase != Phase.EXITING && st.phase != Phase.RESULT
            s.components[ModelComponent::class.java]?.materials?.set(0, if (st.phase == Phase.AT_EXIT || pulse) r.stripStrong else r.stripWeak)
        }
    }

    /** Cao Cao leaving (S08) and lying on the table (S09): slide out of the gap, fall to the landing spot, one small bounce. */
    private fun exitPose(st: PlayState, now: Long, reduced: Boolean): Pair<V3, Quat> {
        val start = Layout.tileCentre(Shape.BIG, Klotski.EXIT)
        val slideEnd = lay.exitSlideEnd()
        val landScene = lay.landing()
        if (st.phase == Phase.RESULT || st.exit == null) return lay.toBox(landScene) to Quat(Vector3(1f, 0f, 0f), -lay.theta)
        val e = (now - st.exit.startedAt).let { x -> st.freezeMs?.let { min(x, it) } ?: x }
        val slideMs = if (reduced) GameFlow.EXIT_REDUCED_SLIDE_MS else GameFlow.EXIT_SLIDE_MS
        if (e < slideMs) return start.lerp(slideEnd, (e.toFloat() / slideMs).let { it * it }) to Quat.identity()
        val a = lay.toScene(slideEnd)
        val u = ((e - slideMs).toFloat() / GameFlow.EXIT_FALL_MS).coerceIn(0f, 1f)
        // gravity-like: horizontal linear, vertical accelerating
        var pos = V3(a.x + (landScene.x - a.x) * u, a.y + (landScene.y - a.y) * u * u, a.z + (landScene.z - a.z) * u)
        if (u >= 1f && !reduced) {
            val v = ((e - slideMs - GameFlow.EXIT_FALL_MS).toFloat() / GameFlow.EXIT_BOUNCE_MS).coerceIn(0f, 1f)
            pos = pos + V3(0f, 0.006f * sin(PI.toFloat() * v), 0f)
        }
        return lay.toBox(pos) to Quat(Vector3(1f, 0f, 0f), -lay.theta * u)
    }

    /** Box-frame spot of the arrow next to [tile] toward [d] (the middle of the neighbouring cells). */
    private fun arrowSpot(b: io.embodit.klotskibox.domain.Board, tile: Int, d: Dir): V3 {
        val s = b.tile(tile).shape; val p = b.at[tile]
        val cells = when (d) {
            Dir.LEFT -> (0 until s.h).map { Cell(p.c - 1, p.r + it) }
            Dir.RIGHT -> (0 until s.h).map { Cell(p.c + s.w, p.r + it) }
            Dir.UP -> (0 until s.w).map { Cell(p.c + it, p.r - 1) }
            Dir.DOWN -> (0 until s.w).map { Cell(p.c + it, p.r + s.h) }
        }
        val c = cells.map { Layout.cellCentre(it) }
        return V3(c.map { it.x }.average().toFloat(), 0.0016f, c.map { it.z }.average().toFloat())
    }

    /** Debug hit replay (template README 必带单测 4): the holder and its deepest child (marker / GLB mesh node). */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String =
        "tilt=${lay.tiltDeg} pivotZ=${lay.pivotZ} plate=${lay.plateCentre()} plateText=\"$plateShown\" landing=${lay.landing()} " +
            "exitEnd=${lay.toScene(lay.exitSlideEnd())} halfY=$regionHalfY cao=${tiles.getOrNull(0)?.transform()?.position} boxCollider=$boxColliderOn"

    fun dispose() {
        plateTex?.let { runCatching { it.close() } }
        res?.close()
        res = null
    }
}

/** Every holder name (one place; never built inside Entity().apply {} where `id` means the entity's id, build.md). */
object BoardNames {
    const val BOX = "box"
    fun tile(id: Int) = "tile:$id"
    fun cell(c: Cell) = "cell:${c.c}:${c.r}"

    sealed interface Hit
    data class TileHit(val id: Int) : Hit
    data class CellHit(val cell: Cell) : Hit
    data object BoxHit : Hit
    data object NoHit : Hit

    /** A holder name -> what was hit; out-of-range names resolve to nothing (a naming bug shows in the log). */
    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "tile" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..9 && f.size == 2 }?.let { TileHit(it) } ?: NoHit
            "cell" -> {
                val c = f.getOrNull(1)?.toIntOrNull(); val r = f.getOrNull(2)?.toIntOrNull()
                if (f.size == 3 && c in 0 until Klotski.COLS && r in 0 until Klotski.ROWS) CellHit(Cell(c!!, r!!)) else NoHit
            }
            BOX -> if (f.size == 1) BoxHit else NoHit
            else -> NoHit
        }
    }

    fun tileOf(name: String): Int? = (hitOf(name) as? TileHit)?.id
}
