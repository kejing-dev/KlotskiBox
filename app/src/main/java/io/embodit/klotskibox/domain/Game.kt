package io.embodit.klotskibox.domain

enum class Language { SYSTEM, ZH, EN }

/** App settings (SharedPreferences, design dataModel.settings). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    /** Box tilt; 0 / 30 / 45 / 60 / 90 (board-game template "倾角选项", 2026-11-06). */
    val tilt: Int = DEFAULT_TILT,
    val firstRunDone: Boolean = false,
    /** First-game coach: the movable tiles glow for the first 3 steps (design S02). */
    val coach: Boolean = true,
    /** Best steps per layout id. */
    val best: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** Design default 25°; the nearest of the five tilt stops. */
        const val DEFAULT_TILT = 30
    }
}

/** One step in the history (design dataModel.game.history): consecutive slides of one tile merge into one step. */
data class Step(val tile: Int, val from: Cell, val to: Cell)

/** What a game looks like on disk (files/game.txt). */
data class SavedGame(
    val puzzleId: String,
    val at: List<Cell>,
    val history: List<Step>,
    val hintCount: Int,
    val hintOn: Boolean,
    val solved: Boolean,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/** `kb1|<puzzle>|<c,r;...10>|<tile:c,r>c,r;...>|hints|hintOn|solved` */
object SaveCodec {
    private fun cell(s: String): Cell { val f = s.split(","); return Cell(f[0].toInt(), f[1].toInt()) }

    fun encode(g: SavedGame): String = listOf(
        "kb1", g.puzzleId, g.at.joinToString(";") { "${it.c},${it.r}" },
        g.history.joinToString(";") { "${it.tile}:${it.from.c},${it.from.r}>${it.to.c},${it.to.r}" },
        g.hintCount.toString(), if (g.hintOn) "1" else "0", if (g.solved) "1" else "0",
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size != 7 || f[0] != "kb1") return null
        val p = Puzzles.byId(f[1]) ?: return null
        val at = f[2].split(";").map { cell(it) }
        require(at.size == p.board.tiles.size)
        val hist = if (f[3].isBlank()) emptyList() else f[3].split(";").map { e ->
            val (t, rest) = e.split(":"); val (a, b) = rest.split(">")
            Step(t.toInt(), cell(a), cell(b))
        }
        val g = SavedGame(p.id, at, hist, f[4].toInt(), f[5] == "1", f[6] == "1")
        // the positions must be a legal arrangement of this layout's tiles
        val b = p.board.copy(at = at)
        require(b.grid().count { it < 0 } == 2)
        g
    }.getOrNull()
}
