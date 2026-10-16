package app.site2app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorsTest {
    @Test
    fun parsesHexToOpaqueArgb() {
        assertEquals(0xFF0D0C0B.toInt(), Colors.parse("#0d0c0b"))
        assertEquals(0xFFFFFFFF.toInt(), Colors.parse("#FFFFFF"))
        assertThrows(IllegalArgumentException::class.java) { Colors.parse("#fff") }
        assertThrows(IllegalArgumentException::class.java) { Colors.parse("0d0c0b") }
    }

    @Test
    fun contrastMatchesWcag() {
        assertEquals(21.0, Colors.contrast(Colors.parse("#000000"), Colors.parse("#ffffff")), 0.01)
        assertEquals(1.0, Colors.contrast(Colors.parse("#777777"), Colors.parse("#777777")), 0.0001)
        // WebAIM's checker gives 4.48 for #777777 on white.
        assertEquals(4.48, Colors.contrast(Colors.parse("#777777"), Colors.parse("#ffffff")), 0.01)
    }

    @Test
    fun picksReadableTextColour() {
        assertEquals(Colors.WHITE, Colors.onColor(Colors.parse("#0d0c0b")))
        assertEquals(Colors.INK, Colors.onColor(Colors.parse("#ffffff")))
        assertEquals(Colors.INK, Colors.onColor(Colors.parse("#fde68a")))
    }

    @Test
    fun keepsABrandAccentOnlyWhenItShows() {
        val white = Colors.parse("#ffffff")
        val navy = Colors.parse("#1d3557")
        val paleGrey = Colors.parse("#eaecf0")
        assertEquals(navy, Colors.accentOn(white, navy))
        assertEquals(Colors.INK, Colors.accentOn(white, paleGrey))
    }

    @Test
    fun withAlphaKeepsTheColour() {
        assertEquals(0x80123456.toInt(), Colors.withAlpha(0xFF123456.toInt(), 0.5f))
    }
}
