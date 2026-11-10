package ma.odema.core.ops

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One cultivated block of the farm plan ("Karima 1"), drawn as one or more rings of x,y pairs. */
@Serializable
data class PlanBlock(
    val name: String,
    val parcelId: String,
    val areaHa: Double? = null,
    /** Flat [x0, y0, x1, y1, …] rings in plan units (y down). */
    val rings: List<List<Double>>,
)

@Serializable
data class PlanLine(val w: Double = 0.72, val pts: List<Double>)

/**
 * The farm drawn as on the agronomist's assolement plan: blocks, borders, pivots, canal and
 * equipment. It is a drawing, not GPS: it needs no internet and looks like the paper plan.
 */
@Serializable
data class FarmPlan(
    val width: Double,
    val height: Double,
    /** Where north points, in degrees clockwise from "up" on the drawing. */
    val northDeg: Double = 0.0,
    val metersPerUnit: Double? = null,
    val blocks: List<PlanBlock> = emptyList(),
    val lines: List<PlanLine> = emptyList(),
    val water: List<List<Double>> = emptyList(),
    /** Equipment drawn on the plan (wells, postes, basins, pivots), used to seed the asset list. */
    val assets: List<Asset> = emptyList(),
)

object PlanGeometry {
    fun contains(ring: List<Double>, x: Double, y: Double): Boolean {
        val n = ring.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]
            val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun area(ring: List<Double>): Double {
        val n = ring.size / 2
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1]
        }
        return abs(s) / 2
    }

    fun blockAt(plan: FarmPlan, x: Double, y: Double): PlanBlock? =
        plan.blocks.firstOrNull { b -> b.rings.any { contains(it, x, y) } }

    private fun distanceToEdges(ring: List<Double>, x: Double, y: Double): Double {
        val n = ring.size / 2
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ring[2 * i]; val ay = ring[2 * i + 1]; val bx = ring[2 * j]; val by = ring[2 * j + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            best = min(best, hypot(x - (ax + t * dx), y - (ay + t * dy)))
        }
        return best
    }

    /** A point well inside the block's largest ring, for its label (centroids can fall outside L-shapes). */
    fun labelPoint(block: PlanBlock): Pair<Double, Double> {
        val ring = block.rings.maxByOrNull { area(it) } ?: return 0.0 to 0.0
        val xs = ring.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = ring.filterIndexed { i, _ -> i % 2 == 1 }
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        var best = (x0 + x1) / 2 to (y0 + y1) / 2
        var bestD = -1.0
        val steps = 24
        for (i in 0..steps) for (j in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * j / steps
            if (!contains(ring, x, y)) continue
            val d = distanceToEdges(ring, x, y)
            if (d > bestD) { bestD = d; best = x to y }
        }
        return best
    }

    fun bounds(ring: List<Double>): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in ring.indices step 2) { x0 = min(x0, ring[i]); x1 = max(x1, ring[i]); y0 = min(y0, ring[i + 1]); y1 = max(y1, ring[i + 1]) }
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
