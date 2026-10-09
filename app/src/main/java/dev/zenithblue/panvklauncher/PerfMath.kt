// SPDX-License-Identifier: MIT
package dev.zenithblue.panvklauncher

import kotlin.math.ceil

/**
 * Pure frame-time math and DXVK stats CSV parsing (no Android imports: unit-testable on the JVM).
 *
 * Convention (CapFrameX style): "1% low" = FPS of the *average of the slowest 1% of frame times*
 * (at least one frame), "0.1% low" likewise for 0.1%. Frame-time percentiles are nearest-rank.
 */
object PerfMath {
    data class Frames(
        val count: Int, val avgFps: Double, val low1: Double, val low01: Double,
        val minFps: Double, val maxFps: Double,
        val p50Ms: Double, val p95Ms: Double, val p99Ms: Double,
        val stutters: Int, val totalSec: Double
    )

    /** Nearest-rank percentile of an ascending array, p in 0..1. */
    fun percentile(sortedAsc: LongArray, p: Double): Long =
        sortedAsc[(ceil(p * sortedAsc.size).toInt() - 1).coerceIn(0, sortedAsc.size - 1)]

    /** FPS from the mean of the worst [frac] share of frame times (sortedAsc), at least one frame. */
    fun lowFps(sortedAsc: LongArray, frac: Double): Double {
        val n = maxOf(1, ceil(sortedAsc.size * frac).toInt())
        var sum = 0.0
        for (i in sortedAsc.size - n until sortedAsc.size) sum += sortedAsc[i]
        return 1e6 / (sum / n)
    }

    /** [frametimesUs]: per-frame times in microseconds (non-positive entries ignored). */
    fun frames(frametimesUs: LongArray): Frames? {
        val ft = frametimesUs.filter { it > 0 }.toLongArray().also { it.sort() }
        if (ft.isEmpty()) return null
        val total = ft.sumOf { it.toDouble() }
        val med = percentile(ft, 0.5)
        return Frames(
            count = ft.size,
            avgFps = ft.size * 1e6 / total,
            low1 = lowFps(ft, 0.01), low01 = lowFps(ft, 0.001),
            minFps = 1e6 / ft.last(), maxFps = 1e6 / ft.first(),
            p50Ms = med / 1000.0, p95Ms = percentile(ft, 0.95) / 1000.0, p99Ms = percentile(ft, 0.99) / 1000.0,
            stutters = ft.count { it > 2 * med },
            totalSec = total / 1e6
        )
    }

    /** One "S" row of the DXVK stats file. */
    data class DxvkSummary(
        val tUs: Long, val gpuLoad: Int, val vidUsedMb: Long, val vidAllocMb: Long, val sysUsedMb: Long, val sysAllocMb: Long,
        val draws: Long, val submits: Long, val passes: Long, val barriers: Long, val pipelines: Long, val compiling: Boolean
    )

    class DxvkStats(val frameT: LongArray, val frameUs: LongArray, val rows: List<DxvkSummary>)

    /** Parses the DXVK_STATS_FILE CSV (see patch panplay-stats.patch). Bad lines are skipped. */
    fun parseDxvk(lines: Sequence<String>): DxvkStats {
        val t = ArrayList<Long>(); val f = ArrayList<Long>(); val s = ArrayList<DxvkSummary>()
        for (l in lines) {
            if (l.length < 3 || l[0] == '#') continue
            try {
                val c = l.split(',')
                when (c[0]) {
                    "F" -> { t += c[1].toLong(); f += c[2].toLong() }
                    "S" -> s += DxvkSummary(
                        c[1].toLong(), c[2].toInt(), c[3].toLong(), c[4].toLong(), c[5].toLong(), c[6].toLong(),
                        c[7].toLong(), c[8].toLong(), c[9].toLong(), c[10].toLong(), c[11].toLong(), c[12] == "1"
                    )
                }
            } catch (_: Exception) {}
        }
        return DxvkStats(t.toLongArray(), f.toLongArray(), s)
    }

    /** Sys CSV (header line first) -> column name to values; empty / non-numeric cells become NaN. */
    fun parseSys(lines: Sequence<String>): Map<String, DoubleArray> {
        val it = lines.iterator()
        if (!it.hasNext()) return emptyMap()
        val names = it.next().split(',')
        val cols = names.map { ArrayList<Double>() }
        while (it.hasNext()) {
            val c = it.next().split(',')
            if (c.size < names.size) continue
            for (i in names.indices) cols[i] += c[i].toDoubleOrNull() ?: Double.NaN
        }
        return names.indices.associate { names[it] to cols[it].toDoubleArray() }
    }

    /** Max-per-bucket downsample for charts (keeps spikes). */
    fun downsampleMax(v: DoubleArray, buckets: Int): DoubleArray {
        if (v.size <= buckets) return v
        return DoubleArray(buckets) { b ->
            var m = 0.0
            for (i in b * v.size / buckets until (b + 1) * v.size / buckets) m = maxOf(m, v[i])
            m
        }
    }
}
