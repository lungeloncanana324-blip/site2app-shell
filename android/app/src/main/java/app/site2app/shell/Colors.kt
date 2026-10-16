package app.site2app.shell

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour arithmetic on packed ARGB ints, free of android.graphics so it runs
 * in plain JVM tests. The shell picks every text and icon colour from the
 * customer's two or three brand colours, so readability is computed, not
 * assumed.
 */
object Colors {
    val WHITE = 0xFFFFFFFF.toInt()
    val INK = 0xFF1A1A1A.toInt()

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /** "#rrggbb" to an opaque ARGB int. */
    fun parse(hex: String): Int {
        require(HEX.matches(hex)) { "Not a #rrggbb colour: $hex" }
        return (0xFF000000L or hex.substring(1).toLong(16)).toInt()
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (color and 0xFFFFFF)

    /** WCAG relative luminance, 0 (black) to 1 (white). Alpha is ignored. */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Near-black or white, whichever reads better on [background]. */
    fun onColor(background: Int): Int =
        if (contrast(INK, background) >= contrast(WHITE, background)) INK else WHITE

    /**
     * [preferred] when it stands out from [background] by at least [minRatio]
     * (3:1 is WCAG's bar for icons and large text); otherwise the plain
     * readable colour, because a brand colour nobody can see is no accent.
     */
    fun accentOn(background: Int, preferred: Int, minRatio: Double = 3.0): Int =
        if (contrast(preferred, background) >= minRatio) preferred else onColor(background)

    fun isLight(color: Int): Boolean = luminance(color) > 0.5
}
