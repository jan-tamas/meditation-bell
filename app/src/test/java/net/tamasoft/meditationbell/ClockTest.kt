package net.tamasoft.meditationbell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000L
    private val sitting = Clock(startMs = t0, totalMs = 90 * min, intervalOffsetMs = 45 * min)

    @Test fun withoutPausesTheBellsRingAtTheirPlannedTimes() {
        assertEquals(t0 + 45 * min, sitting.intervalAt)
        assertEquals(t0 + 90 * min, sitting.endAt)
        assertEquals(30 * min, sitting.remaining(t0 + 60 * min))
    }

    @Test fun aPauseBeforeTheIntervalMovesBothBellsLater() {
        val (paused, stretch) = sitting.pause(t0 + 20 * min)
        assertEquals(Stretch(t0, t0 + 20 * min), stretch)
        // The countdown stands still while paused.
        assertEquals(70 * min, paused.remaining(t0 + 20 * min))
        assertEquals(70 * min, paused.remaining(t0 + 24 * min))
        val resumed = paused.resume(t0 + 25 * min)
        assertEquals(t0 + 50 * min, resumed.intervalAt)
        assertEquals(t0 + 95 * min, resumed.endAt)
        assertTrue(resumed.intervalPending(t0 + 49 * min))
    }

    @Test fun aPauseAfterTheIntervalMovesOnlyTheEnd() {
        val (paused, _) = sitting.pause(t0 + 60 * min)
        val resumed = paused.resume(t0 + 70 * min)
        assertFalse(resumed.intervalPending(t0 + 70 * min))
        assertEquals(t0 + 100 * min, resumed.endAt)
    }

    @Test fun severalPausesAddUp() {
        var c = sitting.pause(t0 + 10 * min).first.resume(t0 + 12 * min)
        val (paused, stretch) = c.pause(t0 + 30 * min)
        assertEquals(Stretch(t0 + 12 * min, t0 + 30 * min), stretch)
        c = paused.resume(t0 + 33 * min)
        assertEquals(t0 + 95 * min, c.endAt)
        assertEquals(Stretch(t0 + 33 * min, t0 + 95 * min), c.finalStretch(t0 + 95 * min))
    }

    @Test fun theStretchesAddUpToTheSitting() {
        val (paused, first) = sitting.pause(t0 + 20 * min)
        val resumed = paused.resume(t0 + 25 * min)
        val last = resumed.finalStretch(resumed.endAt)!!
        assertEquals(90 * min, (first.endMs - first.startMs) + (last.endMs - last.startMs))
    }

    @Test fun endingDuringAPauseSavesNothingMore() {
        val (paused, _) = sitting.pause(t0 + 20 * min)
        assertNull(paused.finalStretch(t0 + 22 * min))
    }

    @Test fun endingEarlySavesUpToNow() {
        assertEquals(Stretch(t0, t0 + 30 * min), sitting.finalStretch(t0 + 30 * min))
    }

    @Test fun aForgottenPauseExpiresAfterAnHour() {
        val (paused, _) = sitting.pause(t0 + 20 * min)
        assertFalse(paused.pauseExpired(t0 + 79 * min))
        assertTrue(paused.pauseExpired(t0 + 80 * min))
        assertFalse(sitting.pauseExpired(t0 + 200 * min))
    }

    @Test fun noIntervalBellMeansNone() {
        val plain = Clock(startMs = t0, totalMs = 30 * min)
        assertEquals(0L, plain.intervalAt)
        assertFalse(plain.intervalPending(t0))
    }
}
