package dk.azp.jellybook.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun bytesUseDecimalUnits() {
        assertEquals("850 KB", formatBytes(850_000))
        assertEquals("312 MB", formatBytes(312_400_000))
        assertEquals("1.2 GB", formatBytes(1_240_000_000))
    }
}
