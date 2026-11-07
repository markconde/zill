package dev.mrkcnd.sillclient

import dev.mrkcnd.sillclient.net.ClockSync
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * ClockSync against a real time server, from this computer: its clock is NTP-kept, so the offset
 * found should be within a few ms of 0 (macOS's `sntp time.google.com` agrees). Skipped offline.
 */
class ClockSyncTest {
    @Test fun measures_this_computers_clock() {
        ClockSync.refreshIfStale()
        val off = ClockSync.offsetMs
        assumeTrue("no time server answered (offline?)", off != null)
        println("offset ${off} ms ± ${ClockSync.errorMs}")
        assertTrue("offset $off ms", kotlin.math.abs(off!!) < 25)
        assertTrue("error ${ClockSync.errorMs}", ClockSync.errorMs!! < 100)
    }
}
