package net.tamasoft.meditationbell

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {
    @Test fun theEndsOfTheRangeAreZeroAndHundred() {
        assertEquals(0, volumePercent(1, 1, 7))
        assertEquals(100, volumePercent(7, 1, 7))
    }

    @Test fun aStepInTheMiddleIsRounded() {
        // Alarm volume on many phones has steps 1..7: step 4 is half way.
        assertEquals(50, volumePercent(4, 1, 7))
        assertEquals(17, volumePercent(2, 1, 7))
        assertEquals(56, volumePercent(9, 0, 16))
    }

    @Test fun anOddRangeDoesNotDivideByZero() {
        assertEquals(0, volumePercent(3, 3, 3))
    }
}
