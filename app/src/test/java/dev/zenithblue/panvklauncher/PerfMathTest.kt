package dev.zenithblue.panvklauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerfMathTest {
    @Test fun lowsAndPercentiles() {
        // 99 frames of 10 ms and one 50 ms spike.
        val f = PerfMath.frames(LongArray(100) { if (it == 37) 50_000 else 10_000 })!!
        assertEquals(100, f.count)
        assertEquals(100 * 1e6 / 1_040_000, f.avgFps, 1e-6)
        assertEquals(20.0, f.low1, 1e-9)      // worst 1 frame = 50 ms
        assertEquals(20.0, f.low01, 1e-9)
        assertEquals(10.0, f.p50Ms, 1e-9)
        assertEquals(10.0, f.p99Ms, 1e-9)     // nearest rank: 99th of 100
        assertEquals(1, f.stutters)           // > 2x median
        assertEquals(20.0, f.minFps, 1e-9)
        assertEquals(100.0, f.maxFps, 1e-9)
    }

    @Test fun lowAveragesWorstShare() {
        // 200 frames, worst 2 (1%) are 40 ms and 20 ms -> mean 30 ms -> 33.33 fps
        val a = LongArray(200) { 10_000 }; a[0] = 40_000; a[1] = 20_000
        assertEquals(1e6 / 30_000, PerfMath.frames(a)!!.low1, 1e-9)
    }

    @Test fun emptyAndParse() {
        assertNull(PerfMath.frames(LongArray(0)))
        val s = PerfMath.parseDxvk(sequenceOf("# hdr", "F,1000,16000", "bad", "F,2000,17000", "S,2000,55,10,20,1,2,100,5,3,40,7,1"))
        assertEquals(2, s.frameUs.size)
        assertEquals(55, s.rows.single().gpuLoad)
        assertEquals(true, s.rows.single().compiling)
    }
}
