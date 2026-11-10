package io.embodit.klotskibox.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Named states of design-package states[] that live in the game (S10 / S10b / S11 / S12 are window states). */
enum class Phase(val id: String) {
    LOADING("S00"), IDLE("S02"), DRAGGING("S03"), BLOCKED("S04"), SNAPPING("S05"), AT_EXIT("S07"), EXITING("S08"), RESULT("S09"),
}

enum class Sound { SLIDE, BLOCK, SNAP, DROP, SOLVED, HINT, UNDO }

/** The one-line status of the play page (design content.tone examples). */
enum class Notice { START, DRAG, BLOCKED, STUCK, AT_EXIT, UNDID, RESTORED, HINT_WAIT }

enum class ConfirmKind { RESET }

/** A tile gliding between two box-frame centres (snap / slide to the end / undo / spring back). */
data class TileAnim(val tile: Int, val from: V3, val to: V3, val startedAt: Long, val durationMs: Long)

/** The 150 ms shake of S04 along [dir]'s axis (null = both axes: a tile that cannot move at all). */
data class Shake(val tile: Int, val dir: Dir?, val at: Long)

/** Arrows on the free neighbours of [tile] (design entities3d.arrows); [grey] = the direction just pushed against. */
data class Arrows(val tile: Int, val dirs: List<Dir>, val until: Long, val grey: Dir? = null, val exit: Boolean = false)

/** S08: Cao Cao leaves the box. */
data class ExitAnim(val startedAt: Long)

/** S09 card data. */
data class Result(val steps: Int, val min: Int, val best: Int, val newRecord: Boolean, val hints: Int)

/** The one state every surface reads (scene, control window, counter plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val puzzleIndex: Int = 0,
    val board: Board = Puzzles.ALL[0].board,
    val history: List<Step> = emptyList(),
    val min: Int = Puzzles.ALL[0].minSteps,
    val hintOn: Boolean = false,
    val hint: HintStep? = null,
    /** Which straight segment of [hint] the ghost shows (L-shaped steps come in two parts). */
    val hintSeg: Int = 0,
    val hintCount: Int = 0,
    /** The tile held by a drag (lifted 2 mm, lit). */
    val held: Int? = null,
    val anim: TileAnim? = null,
    val shake: Shake? = null,
    val arrows: Arrows? = null,
    val notice: Notice = Notice.START,
    val noticeTile: Int? = null,
    val noticeAt: Long = 0,
    val exit: ExitAnim? = null,
    val result: Result? = null,
    val confirm: ConfirmKind? = null,
    val welcome: Boolean = false,
    val restored: Boolean = false,
    val coachOff: Boolean = false,
    /** S01 gap pulse start. */
    val pulseAt: Long = 0,
    /** S09 -> S02: the tiles fade into the new layout (400 ms). */
    val fadeAt: Long = 0,
    /** Debug hold (S03 / S04 / S05 / S08 are < 3 s): animations stop this long after their start. */
    val freezeMs: Long? = null,
) {
    val puzzle: Puzzle get() = Puzzles.ALL[puzzleIndex]
    val steps: Int get() = history.size
    val inputLocked: Boolean get() = phase in setOf(Phase.LOADING, Phase.SNAPPING, Phase.EXITING, Phase.RESULT) || confirm != null || anim != null
    val canUndo: Boolean get() = history.isNotEmpty() && phase in setOf(Phase.IDLE, Phase.AT_EXIT) && anim == null && confirm == null
    val canHint: Boolean get() = phase in setOf(Phase.IDLE, Phase.AT_EXIT) && confirm == null
    val hasProgress: Boolean get() = history.isNotEmpty() && phase != Phase.RESULT
    /** Design S06 is "hint on" on top of S02 / S07. */
    val stateId: String get() = when {
        confirm != null -> "S13"
        phase == Phase.IDLE && hintOn -> "S06"
        phase == Phase.IDLE && restored -> "S14"
        else -> phase.id
    }
    /** The first three steps of a game: the movable tiles glow (design S02 coach). */
    fun coachActive(settings: Settings): Boolean = settings.coach && !coachOff && steps < 3 && phase == Phase.IDLE
}

/** The live drag offset (box frame metres). Read by the scene every frame; never routed through Compose state. */
class LiveDrag {
    @Volatile var tile: Int = -1
    @Volatile var x: Float = 0f
    @Volatile var z: Float = 0f
}

/**
 * The middle layer (rules - state - render). Main-thread confined; the solver builds on [engine].
 * The volume input, the panel buttons and the debug broadcast all call these same functions (profile D29 / build.md).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engine: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val SNAP_MS = 120L
        const val SLIDE_MS_PER_CELL = 70L
        const val UNDO_MS = 220L
        const val SHAKE_MS = 150L
        const val ARROWS_MS = 3000L
        const val EXIT_SLIDE_MS = 300L
        const val EXIT_FALL_MS = 350L
        const val EXIT_BOUNCE_MS = 200L
        const val EXIT_REDUCED_SLIDE_MS = 600L
        const val FADE_MS = 400L
        const val NOTICE_MS = 2500L
        const val COACH_STEPS = 3
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value
    val live = LiveDrag()

    private val solvers = HashMap<String, Solver>()
    private var drag: SlideDrag? = null
    private var animJob: Job? = null
    private var noticeJob: Job? = null
    private var solveJob: Job? = null
    /** Debug: the drag in progress is held by `hold S03|S04`. */
    var dragHeld = false

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion
    private fun frozen(): Boolean = s.freezeMs != null

    // ---------------------------------------------------------------- lifecycle

    /** S00 exits: first run -> S01 (rules page, classic layout ready); a save -> S14; otherwise S02. */
    fun start() {
        val saved = store.load()
        val st = settings()
        if (saved != null && !saved.solved && st.firstRunDone) { restore(saved); return }
        newPuzzle(if (saved != null) Puzzles.indexOf(saved.puzzleId) else 0, welcome = !st.firstRunDone)
    }

    fun solver(p: Puzzle): Solver? = synchronized(solvers) { solvers[p.id] }

    private fun ensureSolver(p: Puzzle, then: (Solver) -> Unit = {}) {
        solver(p)?.let { then(it); return }
        scope.launch {
            val sv = withContext(engine) { Solver.build(p.board) }
            synchronized(solvers) { solvers[p.id] = sv }
            log("SOLVER ${p.id} states=${sv.states} min=${sv.minSteps}")
            then(sv)
        }
    }

    private fun newPuzzle(index: Int, welcome: Boolean = false, fade: Boolean = false) {
        cancelJobs()
        val p = Puzzles.ALL[index.coerceIn(0, Puzzles.ALL.lastIndex)]
        val now = clock()
        set(PlayState(
            phase = Phase.IDLE, puzzleIndex = Puzzles.ALL.indexOf(p), board = p.board, min = p.minSteps, welcome = welcome,
            pulseAt = now, fadeAt = if (fade) now else 0, notice = Notice.START, noticeAt = now, freezeMs = s.freezeMs,
        ))
        drag = null; live.tile = -1
        save()
        log("STATE ${if (welcome) "S01" else "S02"} puzzle=${p.id} ${Klotski.encode(p.board)}")
        ensureSolver(p) { sv -> if (sv.minSteps != p.minSteps) log("SOLVER mismatch ${p.id}: table ${p.minSteps} solver ${sv.minSteps}") }
    }

    private fun restore(g: SavedGame) {
        cancelJobs()
        val idx = Puzzles.indexOf(g.puzzleId)
        val p = Puzzles.ALL[idx]
        val b = p.board.copy(at = g.at)
        val now = clock()
        set(PlayState(
            phase = if (b.atExit) Phase.AT_EXIT else Phase.IDLE, puzzleIndex = idx, board = b, history = g.history, min = p.minSteps,
            hintOn = g.hintOn, hintCount = g.hintCount, restored = true, coachOff = true,
            notice = if (b.atExit) Notice.AT_EXIT else Notice.RESTORED, noticeAt = now,
        ))
        log("GAME restored puzzle=${p.id} steps=${g.history.size} S14")
        ensureSolver(p) { refreshHint() }
    }

    /** [开始] on the rules page: the welcome is over (the layout was already in the box). */
    fun welcomeDone() {
        if (s.welcome) set(s.copy(welcome = false))
        if (!settings().firstRunDone) onSettings(settings().copy(firstRunDone = true))
        log("WELCOME done")
    }

    private fun save() {
        val st = s
        if (st.phase == Phase.LOADING) return
        store.save(SavedGame(st.puzzle.id, st.board.at, st.history, st.hintCount, st.hintOn, st.phase == Phase.RESULT || st.phase == Phase.EXITING))
    }

    /** Leaving the foreground: a held tile springs back to the start of its step, then the game is saved (design lifecycle). */
    fun onBackground() {
        if (drag != null) { drag = null; live.tile = -1; set(s.copy(phase = phaseAfterMove(s.board), held = null, arrows = null)) }
        save()
    }

    private fun cancelJobs() { animJob?.cancel(); animJob = null; solveJob?.cancel(); solveJob = null }

    // ---------------------------------------------------------------- notices

    private fun notice(n: Notice, tile: Int? = null) {
        val now = clock()
        set(s.copy(notice = n, noticeTile = tile, noticeAt = now))
        noticeJob?.cancel()
        if (n in setOf(Notice.BLOCKED, Notice.STUCK, Notice.UNDID, Notice.HINT_WAIT)) noticeJob = scope.launch {
            delay(NOTICE_MS)
            if (frozen()) return@launch
            val st = s
            if (st.noticeAt == now) set(st.copy(notice = if (st.phase == Phase.AT_EXIT) Notice.AT_EXIT else Notice.START, noticeTile = null))
        }
    }

    private fun phaseAfterMove(b: Board) = if (b.atExit) Phase.AT_EXIT else Phase.IDLE

    // ---------------------------------------------------------------- input: drag (T1 / T2)

    /** A pinch / trigger held on [tile] started moving. Returns true when the drag is taken. */
    fun dragStart(tile: Int): Boolean {
        val st = s
        if (st.inputLocked || tile !in st.board.tiles.indices) return false
        if (st.phase !in setOf(Phase.IDLE, Phase.AT_EXIT)) return false
        val runs = st.board.runs(tile)
        val canExit = tile == Klotski.CAO && st.board.atExit
        val d = SlideDrag(tile, runs, canExit = canExit, startMs = clock())
        if (d.stuck) { stuck(tile); return true }
        drag = d
        live.tile = tile; live.x = 0f; live.z = 0f
        val dirs = runs.filter { it.value > 0 }.keys.toList()
        set(st.copy(phase = Phase.DRAGGING, held = tile, arrows = Arrows(tile, dirs, Long.MAX_VALUE, exit = canExit), restored = false, welcome = false).let { it })
        notice(Notice.DRAG, tile)
        onSound(Sound.SLIDE)
        log("DRAG start tile=$tile runs=$runs S03")
        if (st.welcome) welcomeDone()
        return true
    }

    /** Pointer displacement since the press, volume axes (+X right, +Y up, +Z toward the user). */
    fun dragMove(d: V3, tilt: Int = settings().tilt, at: Long = clock()) {
        val dr = drag ?: return
        if (dragHeld) return
        val (x, z) = Layout(tilt).project(d)
        val fired = dr.update(x, z, at)
        val (ox, oz) = dr.offsetXZ()
        live.x = ox; live.z = oz
        if (dr.exitPulled) { drag = null; live.tile = -1; set(s.copy(held = null, arrows = null)); beginExit(); return }
        if (fired) {
            set(s.copy(phase = Phase.BLOCKED, shake = Shake(dr.tile, dr.blockedDir, clock()), arrows = s.arrows?.copy(grey = dr.blockedDir)))
            notice(Notice.BLOCKED, dr.tile)
            onSound(Sound.BLOCK)
            log("BLOCKED tile=${dr.tile} dir=${dr.blockedDir} S04")
        } else if (s.phase == Phase.BLOCKED && s.shake?.let { clock() - it.at > SHAKE_MS } == true) {
            set(s.copy(phase = Phase.DRAGGING))
        }
    }

    fun dragEnd() {
        val dr = drag ?: return
        if (dragHeld) return
        drag = null
        val slide = dr.release()
        val (ox, oz) = dr.offsetXZ()
        live.tile = -1
        val st = s
        val tile = st.board.tile(dr.tile)
        val from = Layout.tileCentre(tile.shape, st.board.at[dr.tile]) + V3(ox, 0f, oz)
        if (slide.cells == 0) {
            // less than half a cell: back to where it was (no step)
            val to = Layout.tileCentre(tile.shape, st.board.at[dr.tile])
            set(st.copy(phase = phaseAfterMove(st.board), held = null, arrows = null, shake = null))
            runAnim(TileAnim(dr.tile, from, to, clock(), SNAP_MS), sound = null) { }
            log("DRAG end tile=${dr.tile} back")
            return
        }
        commit(slide, from, SNAP_MS, Sound.SNAP)
    }

    fun dragCancel() {
        val dr = drag ?: return
        drag = null; live.tile = -1
        set(s.copy(phase = phaseAfterMove(s.board), held = null, arrows = null, shake = null))
        log("DRAG cancel tile=${dr.tile}")
    }

    // ---------------------------------------------------------------- input: taps (T3 / T4)

    /** A tap on a tile: arrows on its free neighbours for 3 s (or a shake + "这块现在动不了"). */
    fun tapTile(tile: Int) {
        val st = s
        if (st.inputLocked || tile !in st.board.tiles.indices) return
        if (st.phase !in setOf(Phase.IDLE, Phase.AT_EXIT)) return
        if (st.welcome) welcomeDone()
        val runs = st.board.runs(tile)
        val canExit = tile == Klotski.CAO && st.board.atExit
        if (runs.values.all { it == 0 } && !canExit) { stuck(tile); return }
        val dirs = runs.filter { it.value > 0 }.keys.toList()
        set(s.copy(arrows = Arrows(tile, dirs, clock() + ARROWS_MS, exit = canExit), restored = false))
        log("TAP tile=$tile arrows=$dirs exit=$canExit")
        scope.launch {
            delay(ARROWS_MS + 50)
            val a = s.arrows
            if (a != null && a.tile == tile && a.until != Long.MAX_VALUE && clock() >= a.until) set(s.copy(arrows = null))
        }
    }

    /** A tap on an empty cell: if arrows are up and it is one of them, slide to the end; if it is in line and free, slide there. */
    fun tapCell(cell: Cell) {
        val st = s
        if (st.inputLocked || st.phase !in setOf(Phase.IDLE, Phase.AT_EXIT)) return
        val a = st.arrows
        if (a == null) { log("TAP cell=$cell (no tile picked)"); return }
        val b = st.board
        val p = b.at[a.tile]
        for (d in a.dirs) {
            val run = b.freeRun(a.tile, d)
            for (k in 1..run) {
                val q = Cell(p.c + d.dc * k, p.r + d.dr * k)
                val moved = b.moveTo(a.tile, q)
                if (moved.covers(a.tile, cell) && !b.covers(a.tile, cell)) {
                    // the adjacent arrow cell slides all the way (design T3); a farther cell slides exactly there
                    val cells = if (k == 1 && isArrowCell(b, a.tile, d, cell)) run else k
                    tapSlide(Slide(a.tile, d, cells)); return
                }
            }
        }
        set(st.copy(arrows = null))
        log("TAP cell=$cell not reachable for tile ${a.tile}")
    }

    /** The cells right next to the tile in direction [d] (where its arrow lies). */
    fun arrowCells(b: Board, tile: Int, d: Dir): List<Cell> {
        val s = b.tile(tile).shape; val p = b.at[tile]
        return when (d) {
            Dir.LEFT -> (0 until s.h).map { Cell(p.c - 1, p.r + it) }
            Dir.RIGHT -> (0 until s.h).map { Cell(p.c + s.w, p.r + it) }
            Dir.UP -> (0 until s.w).map { Cell(p.c + it, p.r - 1) }
            Dir.DOWN -> (0 until s.w).map { Cell(p.c + it, p.r + s.h) }
        }
    }

    private fun isArrowCell(b: Board, tile: Int, d: Dir, cell: Cell) = cell in arrowCells(b, tile, d)

    /** An arrow / cell tap or the debug `arrow` command: slide the tile [slide.cells] in one go. */
    fun tapSlide(slide: Slide) {
        val st = s
        if (st.inputLocked) return
        val run = st.board.freeRun(slide.tile, slide.dir)
        if (slide.cells !in 1..run) { stuck(slide.tile); return }
        val shape = st.board.tile(slide.tile).shape
        onSound(Sound.SLIDE)
        commit(slide, Layout.tileCentre(shape, st.board.at[slide.tile]), SNAP_MS + SLIDE_MS_PER_CELL * slide.cells, Sound.SNAP)
    }

    private fun stuck(tile: Int) {
        set(s.copy(phase = Phase.BLOCKED, shake = Shake(tile, null, clock()), arrows = null))
        notice(Notice.STUCK, tile)
        onSound(Sound.BLOCK)
        log("STUCK tile=$tile S04")
        if (!frozen()) scope.launch { delay(SHAKE_MS + 30); if (s.phase == Phase.BLOCKED && drag == null) set(s.copy(phase = phaseAfterMove(s.board), shake = null)) }
    }

    // ---------------------------------------------------------------- steps

    /** Commits a slide (the result goes into the state and the save before the animation, Cloud Air rule). */
    private fun commit(slide: Slide, fromV: V3, durationMs: Long, sound: Sound?) {
        val st = s
        val b0 = st.board
        val from = b0.at[slide.tile]
        val b1 = b0.apply(slide)
        val to = b1.at[slide.tile]
        val hist = mergeStep(st.history, Step(slide.tile, from, to))
        val shape = b0.tile(slide.tile).shape
        // hint: the L-shaped second half keeps the same hint (design T6); anything else recomputes
        val h = st.hint
        val keepHint = st.hintOn && h != null && h.tile == slide.tile && st.hintSeg + 1 < h.segments.size && segmentEnd(b0.at[h.tile], h, st.hintSeg) == to
        set(st.copy(
            phase = Phase.SNAPPING, board = b1, history = hist, held = null, arrows = null, shake = null, restored = false,
            hintSeg = if (keepHint) st.hintSeg + 1 else 0, hint = if (keepHint) h else null, welcome = false,
        ))
        if (st.welcome) welcomeDone()
        save()
        log("STEP tile=${slide.tile} $from->$to steps=${hist.size} S05 ${Klotski.encode(b1)}")
        runAnim(TileAnim(slide.tile, fromV, Layout.tileCentre(shape, to), clock(), if (reduced) SNAP_MS else durationMs), sound) {
            set(s.copy(phase = phaseAfterMove(s.board), notice = if (s.board.atExit) Notice.AT_EXIT else Notice.START))
            if (s.board.atExit) log("STATE S07 at the gap")
            if (!keepHint) refreshHint()
        }
    }

    /** Where segment [seg] of [h] ends, from the tile's position at the start of the step. */
    private fun segmentEnd(start: Cell, h: HintStep, seg: Int): Cell {
        var p = start
        for (i in 0..seg) { val sl = h.segments[i]; p = Cell(p.c + sl.dir.dc * sl.cells, p.r + sl.dir.dr * sl.cells) }
        return p
    }

    private fun runAnim(a: TileAnim, sound: Sound?, then: () -> Unit) {
        animJob?.cancel()
        set(s.copy(anim = a))
        animJob = scope.launch {
            delay(a.durationMs)
            if (frozen()) return@launch
            sound?.let(onSound)
            set(s.copy(anim = null))
            then()
        }
    }

    /** T5 [悔一步]: the whole last step goes back (a turned step comes back as one). */
    fun undo() {
        val st = s
        if (!st.canUndo) return
        val last = st.history.last()
        val shape = st.board.tile(last.tile).shape
        val b1 = st.board.moveTo(last.tile, last.from)
        set(st.copy(phase = Phase.SNAPPING, board = b1, history = st.history.dropLast(1), arrows = null, hint = null, hintSeg = 0, restored = false))
        save()
        notice(Notice.UNDID, last.tile)
        onSound(Sound.UNDO)
        log("UNDO tile=${last.tile} ${last.to}->${last.from} steps=${st.history.size - 1}")
        runAnim(TileAnim(last.tile, Layout.tileCentre(shape, last.to), Layout.tileCentre(shape, last.from), clock(), if (reduced) SNAP_MS else UNDO_MS), null) {
            set(s.copy(phase = phaseAfterMove(s.board)))
            refreshHint()
        }
    }

    // ---------------------------------------------------------------- hint (T6)

    fun setHint(on: Boolean) {
        val st = s
        if (on == st.hintOn) return
        if (on && !st.canHint) return
        set(st.copy(hintOn = on, hint = null, hintSeg = 0, hintCount = if (on) st.hintCount + 1 else st.hintCount))
        if (on) { onSound(Sound.HINT); refreshHint() }
        save()
        log("HINT ${if (on) "on S06" else "off"} count=${s.hintCount}")
    }

    private fun refreshHint() {
        if (!s.hintOn) return
        val p = s.puzzle
        val sv = solver(p)
        if (sv == null) { notice(Notice.HINT_WAIT); ensureSolver(p) { refreshHint() }; return }
        val h = sv.hint(s.board)
        set(s.copy(hint = h, hintSeg = 0))
        log("HINT tile=${h?.tile} to=${h?.to} segs=${h?.segments} dist=${h?.distance}")
    }

    // ---------------------------------------------------------------- the gap (T7)

    /** [帮他出去] or a pull past the gap. */
    fun exitNow() {
        val st = s
        if (st.phase != Phase.AT_EXIT || st.inputLocked) return
        beginExit()
    }

    private fun beginExit() {
        val st = s
        if (!st.board.atExit) return
        set(st.copy(phase = Phase.EXITING, exit = ExitAnim(clock()), arrows = null, held = null, hint = null))
        save()
        log("STATE S08 exiting")
        val slideMs = if (reduced) EXIT_REDUCED_SLIDE_MS else EXIT_SLIDE_MS
        animJob?.cancel()
        animJob = scope.launch {
            delay(slideMs + EXIT_FALL_MS)
            if (frozen()) return@launch
            onSound(Sound.DROP)
            delay(if (reduced) 100 else EXIT_BOUNCE_MS)
            if (frozen()) return@launch
            solved()
        }
    }

    private fun solved() {
        val st = s
        val p = st.puzzle
        val prev = settings().best[p.id]
        // a solve always takes steps; 0 only happens from a debug setup and must not become a record
        val counts = st.steps > 0
        val best = if (prev == null) st.steps else if (counts) minOf(prev, st.steps) else prev
        val record = counts && (prev == null || st.steps < prev)
        if (counts) onSettings(settings().copy(best = settings().best + (p.id to best)))
        set(st.copy(phase = Phase.RESULT, result = Result(st.steps, st.min, best, record, st.hintCount), hintOn = false))
        save()
        onSound(Sound.SOLVED)
        log("STATE S09 solved steps=${st.steps} min=${st.min} best=$best record=$record")
    }

    /** S09 [再来一次] / [下一局] (T8): the tiles fade into the layout, steps back to 0. */
    fun again() { if (s.phase == Phase.RESULT) newPuzzle(s.puzzleIndex, fade = true) }

    fun next() { if (s.phase == Phase.RESULT) newPuzzle((s.puzzleIndex + 1) % Puzzles.ALL.size, fade = true) }

    // ---------------------------------------------------------------- reset (S13)

    fun requestReset() {
        val st = s
        if (st.phase !in setOf(Phase.IDLE, Phase.AT_EXIT, Phase.RESULT) || st.anim != null) return
        if (!st.hasProgress) { newPuzzle(st.puzzleIndex, fade = true); return }
        set(st.copy(confirm = ConfirmKind.RESET, arrows = null))
        log("CONFIRM reset S13")
    }

    fun confirm() {
        if (s.confirm == ConfirmKind.RESET) { log("CONFIRM yes"); newPuzzle(s.puzzleIndex, fade = true) }
    }

    fun cancelConfirm() { if (s.confirm != null) { set(s.copy(confirm = null)); log("CONFIRM no") } }

    // ---------------------------------------------------------------- debug (same calls as the input)

    /** Debug `drag <tile> <dir> <cells>`: a real drag through [dragStart] / [dragMove] / [dragEnd]. */
    fun debugDrag(tile: Int, dir: Dir, cells: Float, tilt: Int = settings().tilt, release: Boolean = true) {
        if (!dragStart(tile)) return
        val lay = Layout(tilt)
        val m = cells * Layout.PITCH
        val dx = dir.dc * m
        val dz = dir.dr * m
        // box (dx, dz) -> volume axes: x stays, z along the slope
        val v = V3(dx, 0f, 0f) + lay.boxZ * dz
        // synthetic samples 40 ms apart so the one-euro filter settles exactly as it does on a real hand
        val t0 = clock()
        for (i in 1..16) dragMove(v * (minOf(i, 8) / 8f), tilt, t0 + 40L * i)
        if (release) dragEnd()
        log("DEBUG drag tile=$tile $dir $cells")
    }

    /** Debug `push <tile> <dir> <cm> <ms>`: drag and keep pushing past the end (T2 check). */
    fun debugPush(tile: Int, dir: Dir, overCm: Float, holdMs: Long, now: () -> Long = clock) {
        if (!dragStart(tile)) return
        val run = s.board.freeRun(tile, dir)
        val lay = Layout(settings().tilt)
        val m = run * Layout.PITCH + overCm / 100f
        val v = V3(dir.dc * m, 0f, 0f) + lay.boxZ * (dir.dr * m)
        scope.launch {
            val t0 = now()
            while (now() - t0 < holdMs) { dragMove(v); delay(30) }
            dragMove(v)
            dragEnd()
        }
    }

    /** Debug `solve`: the solver's shortest path, one step every [stepMs], up to the gap. */
    fun autoSolve(stepMs: Long = 300L) {
        solveJob?.cancel()
        ensureSolver(s.puzzle) { sv ->
            solveJob = scope.launch {
                while (true) {
                    val st = s
                    if (st.board.atExit || st.phase == Phase.RESULT) break
                    if (st.anim != null || st.phase !in setOf(Phase.IDLE, Phase.AT_EXIT)) { delay(40); continue }
                    val h = sv.hint(st.board) ?: break
                    var p = st.board.at[h.tile]
                    for (seg in h.segments) {
                        while (s.anim != null) delay(20)
                        tapSlide(Slide(h.tile, seg.dir, seg.cells))
                        p = Cell(p.c + seg.dir.dc * seg.cells, p.r + seg.dir.dr * seg.cells)
                    }
                    delay(stepMs)
                }
                log("SOLVE done steps=${s.steps} atExit=${s.board.atExit}")
            }
        }
    }

    fun stopSolve() { solveJob?.cancel(); solveJob = null }

    /** Debug `setup <rows>`: a position of the current layout (rows "张曹曹赵/..."). */
    fun debugSetup(rows: String) {
        val b = runCatching { Klotski.parse(rows.split("/")) }.getOrNull() ?: return
        val cur = s.board
        require(b.tiles.map { it.shape } == cur.tiles.map { it.shape })
        cancelJobs()
        set(s.copy(phase = phaseAfterMove(b), board = b, history = emptyList(), anim = null, arrows = null, hint = null, result = null, exit = null, confirm = null))
        save()
        log("DEBUG setup ${Klotski.encode(b)}")
        refreshHint()
    }

    fun gotoPuzzle(index: Int) = newPuzzle(index)

    /** Debug freeze: animations and notices hold (S03 / S04 / S05 / S08 captures). */
    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms)); if (ms == null) dragHeld = false }

    /** Debug `hold S03`: a drag held at half a cell (the tile mid-slot). */
    fun holdDrag(tile: Int, dir: Dir, cells: Float, blocked: Boolean = false) {
        dragHeld = false
        if (!dragStart(tile)) return
        val lay = Layout(settings().tilt)
        val m = cells * Layout.PITCH
        val v = V3(dir.dc * m, 0f, 0f) + lay.boxZ * (dir.dr * m)
        val t0 = clock()
        for (i in 1..16) dragMove(v * (minOf(i, 8) / 8f), settings().tilt, t0 + 40L * i)
        if (blocked) {
            set(s.copy(phase = Phase.BLOCKED, shake = Shake(tile, dir, clock()), arrows = s.arrows?.copy(grey = dir)))
            notice(Notice.BLOCKED, tile)
        }
        dragHeld = true
        freeze(60_000)
        dragHeld = true
    }

    fun releaseHold() {
        dragHeld = false
        freeze(null)
        if (drag != null) { dragEnd(); return }
        val st = s
        when (st.phase) {
            Phase.SNAPPING -> { set(st.copy(anim = null, phase = phaseAfterMove(st.board))); refreshHint() }
            Phase.EXITING -> { onSound(Sound.DROP); solved() }
            Phase.BLOCKED -> set(st.copy(phase = phaseAfterMove(st.board), shake = null))
            else -> Unit
        }
    }

    fun dump(): String {
        val st = s
        return "state=${st.stateId} phase=${st.phase} puzzle=${st.puzzle.id} steps=${st.steps} min=${st.min} hint=${st.hintOn}/${st.hint?.tile}->${st.hint?.to} " +
            "hints=${st.hintCount} held=${st.held} anim=${st.anim?.tile} arrows=${st.arrows?.tile}:${st.arrows?.dirs} confirm=${st.confirm} " +
            "notice=${st.notice} result=${st.result} board=${Klotski.encode(st.board)} solver=${solver(st.puzzle)?.distance(st.board.key())}"
    }
}

/** Design stepCounting: a slide of the tile that moved last extends that step; back to its start = the step is gone. */
fun mergeStep(history: List<Step>, s: Step): List<Step> {
    val last = history.lastOrNull()
    if (last != null && last.tile == s.tile) {
        val merged = Step(s.tile, last.from, s.to)
        return if (merged.from == merged.to) history.dropLast(1) else history.dropLast(1) + merged
    }
    return history + s
}
