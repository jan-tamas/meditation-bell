package net.tamasoft.meditationbell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PastTest {
    private val prague = ZoneId.of("Europe/Prague")

    @Test fun theTimesAreThePhonesLocalTime() {
        val p = Past(LocalDate.of(2026, 10, 20), 14, 5, 90)
        assertEquals(Instant.parse("2026-10-20T12:05:00Z"), p.start(prague))
        assertEquals(Instant.parse("2026-10-20T13:35:00Z"), p.end(prague))
    }

    @Test fun aSittingAcrossTheClockChangeLastsItsRealLength() {
        // 25 October 2026: clocks go back at 03:00 summer time.
        val p = Past(LocalDate.of(2026, 10, 25), 2, 0, 120)
        assertEquals(120L * 60, p.end(prague).epochSecond - p.start(prague).epochSecond)
    }

    @Test fun aSittingThatWouldEndInTheFutureIsRefused() {
        val p = Past(LocalDate.of(2026, 10, 21), 21, 0, 90)
        assertNotNull(p.problem(prague, Instant.parse("2026-10-21T19:47:00Z")))
        assertNull(p.problem(prague, Instant.parse("2026-10-21T20:30:00Z")))
    }

    @Test fun aSittingOfNoLengthIsRefused() {
        assertNotNull(Past(LocalDate.of(2026, 10, 20), 9, 0, 0).problem(prague, Instant.parse("2026-10-21T00:00:00Z")))
    }

    @Test fun theQueueKeepsWhichSittingsWereEnteredByHand() {
        val hand = Unsaved(Instant.ofEpochMilli(1000), Instant.ofEpochMilli(61_000), manual = true)
        val timed = Unsaved(Instant.ofEpochMilli(1000), Instant.ofEpochMilli(61_000))
        assertTrue(Unsaved.of(hand.key)!!.manual)
        assertFalse(Unsaved.of(timed.key)!!.manual)
        assertEquals(hand, Unsaved.of(hand.key))
    }

    @Test fun aKeyFromBeforeHandEntryStillReads() {
        assertEquals(Unsaved(Instant.ofEpochMilli(5), Instant.ofEpochMilli(9)), Unsaved.of("5-9"))
        assertNull(Unsaved.of("nonsense"))
    }
}
