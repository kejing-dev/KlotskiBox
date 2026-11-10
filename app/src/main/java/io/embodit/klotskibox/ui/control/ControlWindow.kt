package io.embodit.klotskibox.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.klotskibox.BuildConfig
import io.embodit.klotskibox.R
import io.embodit.klotskibox.domain.Board
import io.embodit.klotskibox.domain.ConfirmKind
import io.embodit.klotskibox.domain.Language
import io.embodit.klotskibox.domain.Layout
import io.embodit.klotskibox.domain.Notice
import io.embodit.klotskibox.domain.Phase
import io.embodit.klotskibox.domain.PlayState
import io.embodit.klotskibox.domain.Settings
import io.embodit.klotskibox.domain.Shape
import io.embodit.klotskibox.domain.Who
import io.embodit.klotskibox.platform.Graph
import io.embodit.klotskibox.platform.TAG
import io.embodit.klotskibox.ui.Page
import io.embodit.klotskibox.ui.Ui
import io.embodit.klotskibox.ui.theme.KlotskiColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the box, 560 x 640 dp): all 2D UI, three pages switched by the
 * native SpatialUI TabBar (D8): Play / Rules / Settings. No page scrolls (D44); every page puts its content between a
 * header and a last row (SpaceBetween), so the blank outside the content is only the window padding.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the ready box; [开始] (or the first touch on a tile) ends it
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES }
    LaunchedEffect(state.phase) { if (state.phase == Phase.RESULT || state.phase == Phase.EXITING) Ui.page = Page.PLAY }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass
    // plain strings in (ui_gates tabbar-lang; an English cold start shows three English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (56 dp): a title and [静音] on every page (D15: mute is always one glance away). */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_muted), "$tag/mute", on = !settings.sound,
            onClick = { graph.settings.update { it.copy(sound = !it.sound) } })
    }
}

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

@Composable
private fun puzzleTitle(state: PlayState): String {
    val p = state.puzzle
    return stringResource(R.string.title_puzzle, state.puzzleIndex + 1, if (isZh()) p.zh else p.en)
}

@Composable
internal fun tileName(who: Who): String = stringResource(when (who) {
    Who.CAO -> R.string.name_cao; Who.GUAN -> R.string.name_guan; Who.ZHANG -> R.string.name_zhang; Who.ZHAO -> R.string.name_zhao
    Who.MA -> R.string.name_ma; Who.HUANG -> R.string.name_huang; Who.ZU -> R.string.name_zu
})

/** The status sentence (design content.tone): one line that says what you can do next. */
@Composable
internal fun statusText(state: PlayState, settings: Settings): String = when {
    state.notice == Notice.BLOCKED -> stringResource(R.string.status_blocked)
    state.notice == Notice.STUCK -> stringResource(R.string.status_stuck)
    state.notice == Notice.UNDID -> stringResource(R.string.status_undid, state.noticeTile?.let { tileName(state.board.tile(it).who) } ?: "")
    state.notice == Notice.HINT_WAIT -> stringResource(R.string.status_hint_wait)
    state.phase == Phase.EXITING -> stringResource(R.string.status_exiting)
    state.phase == Phase.AT_EXIT -> stringResource(R.string.status_at_exit)
    state.phase == Phase.DRAGGING -> stringResource(R.string.status_drag)
    state.notice == Notice.RESTORED && state.restored -> stringResource(R.string.status_restored, state.steps)
    state.hintOn && state.hint != null -> stringResource(R.string.status_hint_on)
    state.coachActive(settings) -> stringResource(R.string.status_coach)
    else -> stringResource(R.string.status_start)
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.phase == Phase.LOADING) {
        PageColumn {
            Header(stringResource(R.string.app_name), "play", settings, graph)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.loading), "play/loading", style = PicoTheme.typography.titleMedium)
            }
        }
        return
    }
    if (!graph.window.volumeOpen) { VolumeClosedPage(state, settings, graph); return }
    if (state.confirm == ConfirmKind.RESET) {
        ConfirmPage(stringResource(R.string.confirm_reset), stringResource(R.string.confirm_reset_line, state.steps), stringResource(R.string.confirm_yes_reset),
            R.drawable.ic_replay, stringResource(R.string.tab_play), "reset", settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() })
        return
    }
    if (state.phase == Phase.RESULT && state.result != null) { ResultPage(state, settings, graph); return }
    val best = settings.best[state.puzzle.id]
    PageColumn {
        Header(puzzleTitle(state), "play", settings, graph)
        // the sentence (what to do next), on a card
        Box(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(24.dp)).background(KlotskiColors.TagFace).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Txt(statusText(state, settings), "play/status", style = PicoTheme.typography.titleMedium, color = KlotskiColors.Ink, maxLines = 3)
        }
        Txt(stringResource(R.string.steps_line, state.steps, state.min, best?.toString() ?: stringResource(R.string.best_none)), "play/steps",
            style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        // the main button slot: S07 [帮他出去]; otherwise how to move a tile
        if (state.phase == Phase.AT_EXIT || state.phase == Phase.EXITING) {
            PrimaryButton(stringResource(R.string.btn_let_out), "play/letout", onClick = { flow.exitNow() }, enabled = state.phase == Phase.AT_EXIT && state.anim == null, modifier = Modifier.fillMaxWidth())
        } else {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
                Txt(stringResource(R.string.rules_how), "play/how", style = PicoTheme.typography.bodyLarge, color = KlotskiColors.InkSecondary, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = state.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (state.hintOn) R.string.btn_hint_on else R.string.btn_hint_off), "play/hint", on = state.hintOn,
                onClick = { flow.setHint(!state.hintOn) }, enabled = state.hintOn || state.canHint, modifier = Modifier.weight(1.2f))
        }
        SecondaryButton(stringResource(R.string.btn_reset), "play/reset", onClick = { flow.requestReset() }, icon = R.drawable.ic_replay,
            enabled = state.phase in setOf(Phase.IDLE, Phase.AT_EXIT) && state.anim == null, modifier = Modifier.fillMaxWidth())
    }
}

/** S13: a whole-page confirmation card; the volume is locked while it is open (GameFlow.inputLocked). */
@Composable
private fun ConfirmPage(q: String, line: String, yes: String, icon: Int, title: String, tag: String, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit) {
    PageColumn {
        Header(title, "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(KlotskiColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = KlotskiColors.Ink)
            Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = KlotskiColors.Ink, maxLines = 2)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge, color = KlotskiColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(72.dp))
                SecondaryButton(yes, "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(72.dp))
            }
        }
        Txt(stringResource(R.string.lock_line), "confirm/lock", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** S09: the result card (whole page, [静音] still in the header). */
@Composable
private fun ResultPage(state: PlayState, settings: Settings, graph: Graph) {
    val r = state.result ?: return
    val stories = stringArrayResource(R.array.puzzle_story)
    PageColumn {
        Header(puzzleTitle(state), "result", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(28.dp)).background(KlotskiColors.TagFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.result_title, r.steps), "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = KlotskiColors.Ink)
            Txt(stringResource(R.string.result_min, r.min), "result/min", style = PicoTheme.typography.titleMedium, color = KlotskiColors.Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.result_best, r.best), "result/best", style = PicoTheme.typography.bodyLarge, color = KlotskiColors.Ink)
                if (r.newRecord) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(KlotskiColors.Primary).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Txt(stringResource(R.string.result_record), "result/record", style = PicoTheme.typography.labelLarge, color = KlotskiColors.OnPrimary)
                    }
                }
            }
            Txt(stringResource(R.string.result_hints, r.hints), "result/hints", style = PicoTheme.typography.bodyMedium, color = KlotskiColors.InkSecondary)
            Txt(stories.getOrElse(state.puzzleIndex) { "" }, "result/story", style = PicoTheme.typography.bodyMedium, color = KlotskiColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_next), "result/next", onClick = { graph.flow.next() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.rules_source), "result/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

/** S10b: the system [×] closed the box volume; the play buttons wait (D41: controls follow their window). */
@Composable
private fun VolumeClosedPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(puzzleTitle(state), "volume", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(KlotskiColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.volume_closed), "volume/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = KlotskiColors.Ink)
            Txt(stringResource(R.string.volume_closed_line), "volume/line", style = PicoTheme.typography.bodyLarge, color = KlotskiColors.InkSecondary, maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_bring_box), "volume/bring", onClick = { graph.window.reopenVolume() }, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "volume/undo", onClick = {}, enabled = false, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_hint_off), "volume/hint", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(if (state.welcome) R.string.welcome_title else R.string.rules_title), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LayoutFigure(state.puzzle.board)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(KlotskiColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = KlotskiColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { id ->
                    Box(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(KlotskiColors.TagFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = KlotskiColors.Ink, maxLines = 3)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            PrimaryButton(stringResource(if (state.welcome) R.string.btn_start else R.string.btn_back_play), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** The current layout from above, the names on the tiles in the app language (design rulesPage.figure). */
@Composable
private fun LayoutFigure(board: Board) {
    val zh = isZh()
    val names = Who.entries.associateWith { tileName(it) }
    Canvas(Modifier.size(width = 176.dp, height = 220.dp)) {
        val cell = size.width / 4f
        drawRoundRect(KlotskiColors.Walnut, Offset.Zero, size, CornerRadius(10f, 10f))
        // the gap in the near wall
        drawRect(KlotskiColors.Copper, Offset(cell, size.height - 4f), Size(cell * 2, 4f))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }
        for (t in board.tiles) {
            val p = board.at[t.id]
            val o = Offset(p.c * cell + 3f, p.r * cell + 3f)
            val sz = Size(t.shape.w * cell - 6f, t.shape.h * cell - 6f)
            val fill = when (t.who) { Who.CAO -> KlotskiColors.Lacquer; Who.ZU -> KlotskiColors.Plain; else -> KlotskiColors.Maple }
            drawRoundRect(fill, o, sz, CornerRadius(8f, 8f))
            val ink = if (t.who == Who.CAO) KlotskiColors.Gold else KlotskiColors.Ink
            paint.color = ink.toArgb()
            val label = names[t.who].orEmpty()
            val words = if (zh) listOf(label) else if (t.shape == Shape.TALL) label.split(" ") else listOf(label)
            paint.textSize = when { t.shape == Shape.SMALL -> (if (zh) 30f else 22f); zh -> 34f; else -> 26f }
            val maxW = sz.width - 8f
            words.forEach { w -> val m = paint.measureText(w); if (m > maxW) paint.textSize *= maxW / m }
            drawIntoCanvas { c ->
                val cx = o.x + sz.width / 2
                val lh = paint.textSize * 1.15f
                val top = o.y + sz.height / 2 - lh * (words.size - 1) / 2 + paint.textSize * 0.35f
                words.forEachIndexed { i, w -> c.nativeCanvas.drawText(w, cx, top + i * lh, paint) }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.confirm_yes_exit),
            R.drawable.ic_exit, stringResource(R.string.settings_title), "exit", settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.settings_title), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 120.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 120.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 120.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 120.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt", labelWidth = 120.dp) {
            Segments(Layout.TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { t -> upd { it.copy(tilt = t) } }, Modifier.weight(1f), small = true)
        }
        OptionRow(stringResource(R.string.settings_coach), "settings/coach", labelWidth = 120.dp) {
            Segments(onOff, settings.coach, "settings/coach", { v -> upd { it.copy(coach = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.settings_reopen_panel), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.width(200.dp))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.settings_reopen_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.settings_about_label), "settings/about-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
            Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // D26: quit is the last row; the confirmation is a whole-page card
        SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}

