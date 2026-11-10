package io.embodit.klotskibox.domain

/**
 * The 4 x 5 Klotski box (design-package gameSpec). Columns 0..3 left -> right, rows 0..4 far -> near; the exit gap is in
 * the near wall under columns 1..2. Pure Kotlin, no Android: the rules - state - render split (board-game template §4).
 */
enum class Shape(val w: Int, val h: Int, val code: Int) {
    BIG(2, 2, 4), WIDE(2, 1, 3), TALL(1, 2, 2), SMALL(1, 1, 1);
}

/** Who a tile is. Generals can stand upright (TALL) or lie (WIDE) depending on the layout. */
enum class Who(val zh: String) { CAO("曹"), GUAN("关"), ZHANG("张"), ZHAO("赵"), MA("马"), HUANG("黄"), ZU("卒") }

/** One wooden tile: [id] 0..9 is stable for a layout (0 = Cao Cao, 1 = Guan Yu, 2..5 generals, 6..9 soldiers). */
data class Tile(val id: Int, val who: Who, val shape: Shape)

data class Cell(val c: Int, val r: Int) {
    override fun toString() = "$c,$r"
}

enum class Dir(val dc: Int, val dr: Int) {
    LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1);

    val axisX: Boolean get() = dc != 0
    val opposite: Dir get() = when (this) { LEFT -> RIGHT; RIGHT -> LEFT; UP -> DOWN; DOWN -> UP }

    companion object {
        fun parse(s: String): Dir? = when (s.lowercase()) {
            "left", "l" -> LEFT; "right", "r" -> RIGHT; "up", "u", "far" -> UP; "down", "d", "near" -> DOWN; else -> null
        }
    }
}

/** A single-tile slide in one direction by [cells] (the unit the drag and the arrows produce). */
data class Slide(val tile: Int, val dir: Dir, val cells: Int)

/** Tile positions: [at][id] = the tile's top-left cell. */
data class Board(val tiles: List<Tile>, val at: List<Cell>) {
    init { require(tiles.size == at.size) }

    fun tile(id: Int) = tiles[id]

    fun covers(id: Int, cell: Cell): Boolean {
        val p = at[id]; val s = tiles[id].shape
        return cell.c in p.c until p.c + s.w && cell.r in p.r until p.r + s.h
    }

    /** id of the tile covering [cell], or -1. */
    fun tileAt(cell: Cell): Int = tiles.indices.firstOrNull { covers(it, cell) } ?: -1

    fun grid(): IntArray {
        val g = IntArray(Klotski.COLS * Klotski.ROWS) { -1 }
        for (id in tiles.indices) {
            val p = at[id]; val s = tiles[id].shape
            for (dc in 0 until s.w) for (dr in 0 until s.h) g[(p.r + dr) * Klotski.COLS + p.c + dc] = id
        }
        return g
    }

    fun empty(): List<Cell> {
        val g = grid()
        return (0 until Klotski.ROWS).flatMap { r -> (0 until Klotski.COLS).map { c -> Cell(c, r) } }.filter { g[it.r * Klotski.COLS + it.c] < 0 }
    }

    /** How many cells [id] can slide toward [d] before it hits a wall or another tile. */
    fun freeRun(id: Int, d: Dir, g: IntArray = grid()): Int {
        val s = tiles[id].shape
        var p = at[id]
        var n = 0
        while (true) {
            val q = Cell(p.c + d.dc, p.r + d.dr)
            if (q.c < 0 || q.r < 0 || q.c + s.w > Klotski.COLS || q.r + s.h > Klotski.ROWS) return n
            for (dc in 0 until s.w) for (dr in 0 until s.h) {
                val cc = q.c + dc; val rr = q.r + dr
                val o = g[rr * Klotski.COLS + cc]
                if (o >= 0 && o != id) return n
            }
            p = q; n++
        }
    }

    fun runs(id: Int): Map<Dir, Int> { val g = grid(); return Dir.entries.associateWith { freeRun(id, it, g) } }

    fun canMove(id: Int): Boolean = runs(id).values.any { it > 0 }

    fun movable(): List<Int> = tiles.indices.filter { canMove(it) }

    fun apply(s: Slide): Board {
        require(s.cells >= 0 && s.cells <= freeRun(s.tile, s.dir)) { "illegal $s on ${Klotski.encode(this)}" }
        val p = at[s.tile]
        return copy(at = at.toMutableList().also { it[s.tile] = Cell(p.c + s.dir.dc * s.cells, p.r + s.dir.dr * s.cells) })
    }

    fun moveTo(id: Int, to: Cell): Board = copy(at = at.toMutableList().also { it[id] = to })

    /** Cao Cao stands in front of the gap (design S07). */
    val atExit: Boolean get() = at[Klotski.CAO] == Klotski.EXIT

    /** Canonical key: the shape whose top-left anchor is in each cell (equal tiles are interchangeable, design solver.kind). */
    fun key(): Long = Klotski.key(tiles.map { it.shape }, at)
}

object Klotski {
    const val COLS = 4
    const val ROWS = 5
    const val CAO = 0
    /** Cao Cao's top-left cell when he stands in front of the gap. */
    val EXIT = Cell(1, 3)

    fun key(shapes: List<Shape>, at: List<Cell>): Long {
        var k = 0L
        for (i in shapes.indices) {
            val idx = at[i].r * COLS + at[i].c
            k = k or (shapes[i].code.toLong() shl (idx * 3))
        }
        return k
    }

    /** Anchor shape codes of a key, cell by cell. */
    fun anchors(key: Long): IntArray = IntArray(COLS * ROWS) { ((key shr (it * 3)) and 7L).toInt() }

    fun shapeOf(code: Int): Shape = Shape.entries.first { it.code == code }

    /** Occupancy (true = covered) of a key. */
    fun occupied(key: Long): BooleanArray {
        val occ = BooleanArray(COLS * ROWS)
        val a = anchors(key)
        for (i in a.indices) if (a[i] != 0) {
            val s = shapeOf(a[i]); val c = i % COLS; val r = i / COLS
            for (dc in 0 until s.w) for (dr in 0 until s.h) occ[(r + dr) * COLS + c + dc] = true
        }
        return occ
    }

    /**
     * A layout written as five rows of four glyphs (far row first): 曹 关 张 赵 马 黄 = the named tile (2 cells each, 4 for
     * Cao Cao), 卒 = one soldier per cell, '.' = empty. Generals lie or stand by how their two cells sit.
     */
    fun parse(rows: List<String>): Board {
        require(rows.size == ROWS && rows.all { it.length == COLS }) { "bad layout $rows" }
        val cells = HashMap<Char, MutableList<Cell>>()
        val soldiers = ArrayList<Cell>()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val ch = rows[r][c]
            when (ch) {
                '.' -> Unit
                '卒' -> soldiers += Cell(c, r)
                else -> cells.getOrPut(ch) { ArrayList() } += Cell(c, r)
            }
        }
        val tiles = ArrayList<Tile>()
        val at = ArrayList<Cell>()
        fun add(who: Who, cs: List<Cell>) {
            val c0 = cs.minOf { it.c }; val r0 = cs.minOf { it.r }
            val w = cs.maxOf { it.c } - c0 + 1; val h = cs.maxOf { it.r } - r0 + 1
            require(w * h == cs.size) { "$who is not a rectangle" }
            val shape = Shape.entries.first { it.w == w && it.h == h }
            tiles += Tile(tiles.size, who, shape); at += Cell(c0, r0)
        }
        val order = listOf(Who.CAO, Who.GUAN, Who.ZHANG, Who.ZHAO, Who.MA, Who.HUANG)
        for (who in order) add(who, cells[who.zh.first()] ?: error("layout has no ${who.zh}"))
        require(soldiers.size == 4) { "need 4 soldiers" }
        soldiers.forEach { add(Who.ZU, listOf(it)) }
        require(tiles[CAO].shape == Shape.BIG)
        val b = Board(tiles, at)
        require(b.empty().size == 2) { "a layout leaves exactly two cells empty" }
        return b
    }

    /** Debug / save text: the same five-row picture, rows joined by '/'. */
    fun encode(b: Board): String {
        val g = b.grid()
        return (0 until ROWS).joinToString("/") { r -> (0 until COLS).joinToString("") { c -> g[r * COLS + c].let { if (it < 0) "." else b.tiles[it].who.zh } } }
    }
}

/** The classic layouts shipped in v1 (gate B question 1, recommended default: 6-8 public-domain layouts, easy -> hard). */
data class Puzzle(val id: String, val zh: String, val en: String, val rows: List<String>, val minSteps: Int) {
    val board: Board by lazy { Klotski.parse(rows) }
}

object Puzzles {
    /**
     * Layouts from the traditional Chinese collection (許蒓舫《華容道》等公版排法). [Puzzle.minSteps] is what the solver
     * finds under the design's counting rule (one tile moved any distance, turns included, is one step); SolverTest
     * checks every number against the solver and against the published minimum where it is known (横刀立马 81).
     */
    val ALL: List<Puzzle> = listOf(
        // 第 1 局 is the classic (design fiveSecond / S01: the box opens on 横刀立马); [下一局] then goes easy -> hard.
        Puzzle("classic", "横刀立马", "Heng Dao Li Ma", listOf("张曹曹赵", "张曹曹赵", "马关关黄", "马卒卒黄", "卒..卒"), 81),
        Puzzle("zuoyou", "左右布兵", "Soldiers on Both Sides", listOf("卒曹曹卒", "卒曹曹卒", "张马黄赵", "张马黄赵", ".关关."), 54),
        Puzzle("qitou", "齐头并进", "Side by Side", listOf("张曹曹赵", "张曹曹赵", "卒卒卒卒", "马关关黄", "马..黄"), 60),
        Puzzle("zhihui", "指挥若定", "In Command", listOf("张曹曹赵", "张曹曹赵", "卒关关卒", "马卒卒黄", "马..黄"), 70),
        Puzzle("taohua", "桃花园中", "Peach Garden", listOf("卒曹曹卒", "张曹曹赵", "张关关赵", "马..黄", "马卒卒黄"), 70),
        Puzzle("bingfen", "兵分三路", "Three Roads", listOf("卒曹曹卒", "张曹曹赵", "张关关赵", "马卒卒黄", "马..黄"), 72),
    )

    fun byId(id: String): Puzzle? = ALL.firstOrNull { it.id == id }
    fun indexOf(id: String): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)
}
