package io.embodit.klotskibox.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.klotskibox.R
import io.embodit.klotskibox.domain.GameFlow
import io.embodit.klotskibox.domain.Klotski
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.Notice
import io.embodit.klotskibox.domain.Phase
import io.embodit.klotskibox.domain.PlayState
import io.embodit.klotskibox.domain.V3
import io.embodit.klotskibox.domain.Who
import io.embodit.klotskibox.platform.Graph
import io.embodit.klotskibox.platform.TAG
import io.embodit.klotskibox.ui.board.input.BoardInputConfig
import io.embodit.klotskibox.ui.board.input.BoardInputListener
import io.embodit.klotskibox.ui.board.input.BoardInputLog
import io.embodit.klotskibox.ui.board.input.InputSource
import io.embodit.klotskibox.ui.board.input.Offset3M
import io.embodit.klotskibox.ui.board.input.TargetResolver
import io.embodit.klotskibox.ui.board.input.boardInput
import io.embodit.klotskibox.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

/**
 * The box volume (default container). Opens the control window beside it through the level gate, shows the box, and
 * routes every pinch / trigger / drag on a holder (a tile, a cell, the S10 box) to [GameFlow] through the factory
 * board-input template (D50: hand ray, controller ray and eye-hand alike).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { BoardScene(graph.flow.live, scope) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> Log.i(TAG, "LIFECYCLE foreground")
                // S10 fallback (design T12): the app icon tapped again brings the volume to the front; the panel comes back
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.request("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> { Log.i(TAG, "VOLUME destroyed S10b"); graph.window.onVolumeLifecycle("ON_DESTROY") }
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val plate = plateText(state, graph.window.panelOpen) { id, args -> loc.getString(id, *args) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底"), removed when the GLBs are in
                    val slab = MeshResource.createBox(Vector3(Layout.OUTER_W, Layout.FLOOR_T + Layout.WALL_H, Layout.OUTER_D), cornerRadius = 0.004f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.24f, 0.16f, 0.11f, 1f)); setRoughness(0.8f) }
                    slab to mat
                }.getOrNull()
            }
            quick?.let { (s, m) -> scene.showFallback(s, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(
                    load("box"),
                    Layout.TILTS.associateWith { load("wedge_$it") },
                    mapOf(Who.CAO to "cao", Who.GUAN to "guan", Who.ZHANG to "zhang", Who.ZHAO to "zhao", Who.MA to "ma", Who.HUANG to "huang", Who.ZU to "zu")
                        .mapValues { load("tile_${it.value}") },
                )
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, graph.flow.state.value.board.tiles.map { it.who })
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings, graph.window.panelOpen, plate)
        },
    )
}

/**
 * The counter plate line (design spatialLayout.counterPlate; D38: the same source as the panel): S10 asks for a pinch,
 * S09 the result, S14's first frame "picking up", otherwise "Move N · Min M".
 */
internal fun plateText(st: PlayState, panelOpen: Boolean, s: (Int, Array<Any>) -> String): String = when {
    st.phase == Phase.LOADING -> ""
    !panelOpen -> s(R.string.plate_pinch, emptyArray())
    st.phase == Phase.RESULT || st.phase == Phase.EXITING -> s(R.string.plate_solved, arrayOf(st.steps))
    st.restored && st.notice == Notice.RESTORED -> s(R.string.plate_restored, emptyArray())
    else -> s(R.string.plate_moves, arrayOf(st.steps, st.min))
}

/** Hit entity (or an ancestor) -> the holder name the game knows (tiles, cells, the S10 box); the box model takes no hits. */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.klotskibox.ui.board.input.resolvePath(path, ::targetOf)

        /** The square a target stands for (input-log `square=`): a tile's top-left cell, a cell, the box, or "-". */
        fun squareOf(target: String?, board: io.embodit.klotskibox.domain.Board): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.TileHit -> board.at.getOrNull(h.id)?.toString() ?: "-"
            is BoardNames.CellHit -> h.cell.toString()
            BoardNames.BoxHit -> "box"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap / drag goes through [routeTap] / [GameFlow] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    private var dragging = false

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value.board)}")
        routeTap(graph, target)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value.board)}")
        if (!graph.window.panelOpen) { graph.window.request("pinch"); return true } // S10: the first pinch only brings the panel back
        val id = BoardNames.tileOf(target) ?: return false
        dragging = graph.flow.dragStart(id)
        return true
    }

    override fun onDragMove(totalM: Offset3M) { if (dragging) graph.flow.dragMove(V3(totalM.x, totalM.y, totalM.z)) }

    override fun onDragEnd() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.dragCancel(); dragging = false }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name -> the one flow call (S10: the first pinch reopens the panel and is consumed). */
internal fun routeTap(graph: Graph, name: String?) {
    if (!graph.window.panelOpen) { Log.i(TAG, "S10 pinch $name -> reopen panel"); graph.window.request("pinch"); return }
    routeFlow(graph.flow, name)
}

/** [routeTap] without the Android graph (hit-replay unit tests drive a bare GameFlow through it). */
internal fun routeFlow(flow: GameFlow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.TileHit -> flow.tapTile(h.id)
        is BoardNames.CellHit -> flow.tapCell(h.cell)
        BoardNames.BoxHit, BoardNames.NoHit -> Unit
    }
}

