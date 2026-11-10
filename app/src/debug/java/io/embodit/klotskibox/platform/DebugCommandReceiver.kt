package io.embodit.klotskibox.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.klotskibox.domain.Cell
import io.embodit.klotskibox.domain.Dir
import io.embodit.klotskibox.domain.Language
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.Puzzles
import io.embodit.klotskibox.domain.Settings
import io.embodit.klotskibox.domain.Slide
import io.embodit.klotskibox.ui.Containers
import io.embodit.klotskibox.ui.Page
import io.embodit.klotskibox.ui.Ui
import io.embodit.klotskibox.ui.board.BoardListener
import io.embodit.klotskibox.ui.board.BoardTargets
import io.embodit.klotskibox.ui.board.SceneHolder
import io.embodit.klotskibox.ui.board.input.BoardInputLog
import io.embodit.klotskibox.ui.board.input.InputSource
import io.embodit.klotskibox.ui.board.input.resolveUp
import io.embodit.klotskibox.ui.board.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.klotskibox.DEBUG -p io.embodit.klotskibox --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S14          reach a named state (AGENTS.md lists what each sets up; S03 / S04 / S05 / S08 are held)
 *   drag "<tile> <dir> <cells>"     a real drag (dragStart / dragMove / dragEnd) of tile 0..9, dir left|right|up|down
 *   push "<tile> <dir> <cm> <ms>"   drag to the end of the run and keep pushing <cm> past it for <ms> (T2)
 *   tap <holder>           a pinch on tile:6 / cell:1:4 / box      arrow "<tile> <dir>" tap a tile, then its arrow
 *   undo · hint on|off · reset · confirm · no · letout · solve · stop · puzzle N · setup "<5 rows joined by />"
 *   hold S03|S04|S05|S08|off · lang zh|en|system · tilt 0|30|45|60|90 · sound on|off · volume 0-2 · reduced on|off
 *   coach on|off · page play|rules|settings · exitask · welcome · reopen · closepanel · closevolume · reopenvolume
 *   s00hold on|off · replay "<holder> [hand-ray|eye-hand|controller]" · hover <holder>|off · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        /** Classic tiles with Cao Cao one step above the gap; [atGap] slides him down (a real step, S07 / S08 / S09). */
        const val PRE_GAP = "张关关赵/张卒卒赵/马曹曹黄/马曹曹黄/卒..卒"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun fresh(index: Int = 0) { flow.freeze(null); flow.stopSolve(); flow.gotoPuzzle(index); Ui.page = Page.PLAY }
        fun atGap() { fresh(); flow.debugSetup(PRE_GAP); flow.tapSlide(Slide(0, Dir.DOWN, 1)) }
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.freeze(null); flow.gotoPuzzle(0); flow.start() }
                "S02" -> fresh()
                "S03" -> { fresh(); later(300) { flow.holdDrag(6, Dir.DOWN, 0.5f) } }
                "S04" -> { fresh(); later(300) { flow.holdDrag(8, Dir.RIGHT, 1.35f, blocked = true) } }
                "S05" -> { fresh(); later(300) { flow.freeze(60); flow.tapSlide(Slide(6, Dir.DOWN, 1)) } }
                "S06" -> { fresh(); later(300) { flow.setHint(true) } }
                "S07" -> atGap()
                "S08" -> { atGap(); later(600) { flow.freeze(GameFlowHold.EXIT_MID_MS); flow.exitNow() } }
                "S09" -> { atGap(); later(600) { flow.exitNow() } }
                "S10" -> graph.window.closeControlOnly()
                "S10B" -> graph.window.closeVolumeOnly()
                "S11" -> Ui.page = Page.SETTINGS
                "S12" -> Ui.page = Page.RULES
                "S13" -> { fresh(); later(300) { flow.tapSlide(Slide(6, Dir.DOWN, 1)) }; later(900) { flow.requestReset() } }
                "S14" -> Log.i(TAG, "S14: make a move, force-stop, relaunch")
                else -> Log.w(TAG, "unknown state $a")
            }
            "drag" -> { val t = f.getOrNull(0)?.toIntOrNull(); val d = f.getOrNull(1)?.let { Dir.parse(it) }; val n = f.getOrNull(2)?.toFloatOrNull() ?: 1f
                if (t != null && d != null) flow.debugDrag(t, d, n) }
            "push" -> { val t = f.getOrNull(0)?.toIntOrNull(); val d = f.getOrNull(1)?.let { Dir.parse(it) }
                if (t != null && d != null) flow.debugPush(t, d, f.getOrNull(2)?.toFloatOrNull() ?: 2f, f.getOrNull(3)?.toLongOrNull() ?: 400L) }
            "tap" -> routeTap(graph, a)
            "arrow" -> { val t = f.getOrNull(0)?.toIntOrNull(); val d = f.getOrNull(1)?.let { Dir.parse(it) }
                if (t != null && d != null) {
                    routeTap(graph, "tile:$t")
                    val st = flow.state.value
                    val cell = flow.arrowCells(st.board, t, d).firstOrNull()
                    if (cell != null) later(150) { routeTap(graph, "cell:${cell.c}:${cell.r}") }
                } }
            "cell" -> { val c = f.getOrNull(0)?.toIntOrNull(); val r = f.getOrNull(1)?.toIntOrNull(); if (c != null && r != null) flow.tapCell(Cell(c, r)) }
            "undo" -> flow.undo()
            "hint" -> flow.setHint(on)
            "reset" -> flow.requestReset()
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "letout" -> flow.exitNow()
            "solve" -> flow.autoSolve(a.toLongOrNull() ?: 300L)
            "stop" -> flow.stopSolve()
            "puzzle" -> a.toIntOrNull()?.let { fresh((it - 1).coerceIn(0, Puzzles.ALL.lastIndex)) }
            "setup" -> flow.debugSetup(a)
            "best" -> { val n = a.toIntOrNull(); settings { it.copy(best = if (n == null) emptyMap() else it.best + (flow.state.value.puzzle.id to n)) } }
            "hold" -> when (a.uppercase()) {
                "OFF" -> flow.releaseHold()
                else -> onReceive(context, Intent(intent).putExtra("cmd", "goto").putExtra("a", a))
            }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in Layout.TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> settings { it.copy(coach = on) }
            "page" -> Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "welcome" -> flow.welcomeDone()
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "closevolume" -> graph.window.closeVolumeOnly()
            "reopenvolume" -> graph.window.reopenVolume()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            // replay <holder> [src]: the real scene entities a ray lands on for that holder (holder, marker, deepest GLB node)
            // through the template's resolveUp, then the deepest one into the same listener.onTap
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, BoardTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> square=${BoardTargets.squareOf(t, flow.state.value.board)}")
                    last = t
                }
                if (ents.isNotEmpty()) BoardListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState} volume=${graph.window.volumeState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"} containers=${Containers.BOARD}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}

/** S08 is held at the top of the fall (slide 300 ms + half the 350 ms fall). */
object GameFlowHold { const val EXIT_MID_MS = 470L }
