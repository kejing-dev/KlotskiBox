package io.embodit.klotskibox.domain

/**
 * BFS solver (design gameSpec.solver). A state is the canonical key (equal shapes interchangeable); one step = one tile
 * moved any distance, turns included (gameSpec.stepCounting, the "81 moves" count). The whole reachable component of a
 * layout is enumerated once and a multi-source BFS from every "Cao Cao at the gap" state gives the distance to the goal
 * for every state, so the minimum, the hint and "always solvable" are lookups.
 */
class Solver private constructor(val startKey: Long, private val dist: HashMap<Long, Int>) {
    val states: Int get() = dist.size
    val minSteps: Int get() = dist[startKey] ?: -1

    /** Distance to the gap from [key], or -1 if not in this layout's component. */
    fun distance(key: Long): Int = dist[key] ?: -1

    fun allReachGoal(): Boolean = dist.values.all { it >= 0 }

    /**
     * The first step of a shortest path from [b]: which tile goes where, and the unit path it takes (straight segments
     * for the ghost, design hint "L 形一步分两段"). null at the goal.
     */
    fun hint(b: Board): HintStep? {
        val d = distance(b.key())
        if (d <= 0) return null
        for (id in b.tiles.indices) {
            for ((to, path) in reach(b, id)) {
                val nb = b.moveTo(id, to)
                if (distance(nb.key()) == d - 1) return HintStep(id, to, segments(b.at[id], path), d)
            }
        }
        return null
    }

    companion object {
        fun build(start: Board): Solver {
            val shapes = start.tiles.map { it.shape }
            // 1. the component: BFS over macro moves from the start
            val seen = HashSet<Long>()
            val order = ArrayList<Long>()
            val adj = HashMap<Long, LongArray>()
            val sk = start.key()
            seen += sk; order += sk
            var i = 0
            while (i < order.size) {
                val k = order[i++]
                val next = neighbours(k)
                adj[k] = next
                for (n in next) if (seen.add(n)) order += n
            }
            // 2. distance to the goal: multi-source BFS from every state with Cao Cao at the gap (moves are reversible)
            val dist = HashMap<Long, Int>(order.size * 2)
            val queue = ArrayDeque<Long>()
            val goalAnchor = Klotski.EXIT.r * Klotski.COLS + Klotski.EXIT.c
            for (k in order) if (((k shr (goalAnchor * 3)) and 7L).toInt() == Shape.BIG.code) { dist[k] = 0; queue.addLast(k) }
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val dk = dist[k]!!
                for (n in adj[k]!!) if (n !in dist) { dist[n] = dk + 1; queue.addLast(n) }
            }
            for (k in order) if (k !in dist) dist[k] = -1
            check(shapes.isNotEmpty())
            return Solver(sk, dist)
        }

        /** Every state one macro move away from [key]: each anchored tile flood-fills its own reachable spots. */
        fun neighbours(key: Long): LongArray {
            val anchors = Klotski.anchors(key)
            val occ = Klotski.occupied(key)
            val out = ArrayList<Long>()
            for (i in anchors.indices) {
                val code = anchors[i]
                if (code == 0) continue
                val s = Klotski.shapeOf(code)
                val c0 = i % Klotski.COLS; val r0 = i / Klotski.COLS
                // free this tile's own cells
                val free = occ.copyOf()
                for (dc in 0 until s.w) for (dr in 0 until s.h) free[(r0 + dr) * Klotski.COLS + c0 + dc] = false
                fun fits(c: Int, r: Int): Boolean {
                    if (c < 0 || r < 0 || c + s.w > Klotski.COLS || r + s.h > Klotski.ROWS) return false
                    for (dc in 0 until s.w) for (dr in 0 until s.h) if (free[(r + dr) * Klotski.COLS + c + dc]) return false
                    return true
                }
                val visited = HashSet<Int>(); visited += i
                val q = ArrayDeque<Int>(); q += i
                val base = key and (7L shl (i * 3)).inv()
                while (q.isNotEmpty()) {
                    val p = q.removeFirst()
                    val c = p % Klotski.COLS; val r = p / Klotski.COLS
                    for (d in Dir.entries) {
                        val nc = c + d.dc; val nr = r + d.dr
                        if (!fits(nc, nr)) continue
                        val np = nr * Klotski.COLS + nc
                        if (visited.add(np)) { q += np; out += base or (code.toLong() shl (np * 3)) }
                    }
                }
            }
            return out.toLongArray()
        }

        /** Every spot tile [id] can reach on its own, with the unit path (cells after the start) that gets it there. */
        fun reach(b: Board, id: Int): List<Pair<Cell, List<Cell>>> {
            val s = b.tiles[id].shape
            val g = b.grid()
            fun fits(c: Int, r: Int): Boolean {
                if (c < 0 || r < 0 || c + s.w > Klotski.COLS || r + s.h > Klotski.ROWS) return false
                for (dc in 0 until s.w) for (dr in 0 until s.h) { val o = g[(r + dr) * Klotski.COLS + c + dc]; if (o >= 0 && o != id) return false }
                return true
            }
            val start = b.at[id]
            val prev = HashMap<Cell, Cell>()
            val q = ArrayDeque<Cell>(); q += start
            val out = ArrayList<Cell>()
            prev[start] = start
            while (q.isNotEmpty()) {
                val p = q.removeFirst()
                for (d in Dir.entries) {
                    val n = Cell(p.c + d.dc, p.r + d.dr)
                    if (n in prev || !fits(n.c, n.r)) continue
                    prev[n] = p; q += n; out += n
                }
            }
            return out.map { to ->
                val path = ArrayList<Cell>()
                var x = to
                while (x != start) { path += x; x = prev[x]!! }
                to to path.reversed()
            }
        }

        /** A unit path -> straight slides. */
        fun segments(from: Cell, path: List<Cell>): List<Slide> {
            val out = ArrayList<Slide>()
            var p = from
            for (n in path) {
                val d = Dir.entries.first { it.dc == n.c - p.c && it.dr == n.r - p.r }
                val last = out.lastOrNull()
                if (last != null && last.dir == d) out[out.size - 1] = last.copy(cells = last.cells + 1) else out += Slide(-1, d, 1)
                p = n
            }
            return out
        }
    }
}

/** A hinted step: tile [tile] goes to [to] through [segments] (each a straight slide); [distance] before the step. */
data class HintStep(val tile: Int, val to: Cell, val segments: List<Slide>, val distance: Int) {
    val first: Slide get() = segments.first().copy(tile = tile)
}
