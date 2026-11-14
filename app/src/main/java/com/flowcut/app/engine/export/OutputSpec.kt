package com.flowcut.app.engine.export

enum class OutCodec { AVC, HEVC }

enum class Quality(val bitsPerPixel: Double) {
    LOW(0.06),
    MEDIUM(0.10),
    HIGH(0.16),
    ULTRA(0.22),
    EXTREME(0.30)
}

data class Aspect(val w: Int, val h: Int) {
    init {
        require(w > 0 && h > 0) { "Aspect dimensions must be > 0: $w x $h" }
    }

    companion object {
        val R9_16 = Aspect(9, 16)
        val R16_9 = Aspect(16, 9)
        val R4_3 = Aspect(4, 3)
        val R3_4 = Aspect(3, 4)
        val R1_1 = Aspect(1, 1)
        val R4_5 = Aspect(4, 5)
        val R5_4 = Aspect(5, 4)
        val R3_2 = Aspect(3, 2)
        val R2_3 = Aspect(2, 3)
        val R2_1 = Aspect(2, 1)
        val R1_2 = Aspect(1, 2)
        val R21_9 = Aspect(21, 9)
    }
}

object Resolutions {
    val SHORT_SIDES = intArrayOf(144, 240, 360, 480, 540, 720, 1080, 1440, 2160)
    val FPS = intArrayOf(24, 25, 30, 50, 60)

    fun label(shortSide: Int): String = when (shortSide) {
        1440 -> "2K"
        2160 -> "4K"
        else -> "${shortSide}p"
    }
}

data class OutSize(val w: Int, val h: Int)

data class OutputSpec(
    val aspect: Aspect,
    val shortSide: Int,
    val fps: Int,
    val quality: Quality,
    val codec: OutCodec
) {
    /** alignW/alignH: encoder widthAlignment/heightAlignment (power of 2). */
    fun size(alignW: Int = 2, alignH: Int = 2): OutSize =
        if (aspect.w <= aspect.h) {
            OutSize(aligned(shortSide, 1, 1, alignW), aligned(shortSide, aspect.h, aspect.w, alignH))
        } else {
            OutSize(aligned(shortSide, aspect.w, aspect.h, alignW), aligned(shortSide, 1, 1, alignH))
        }

    fun key(): String = "${aspect.w}x${aspect.h}_${shortSide}_${fps}_${quality}_${codec}"

    private fun aligned(base: Int, num: Int, den: Int, align: Int): Int {
        require(align > 0 && (align and (align - 1)) == 0) { "align must be a power of 2: $align" }
        // Nearest multiple of align to base * num / den (round half up)
        val q = (base.toLong() * num * 2 + den.toLong() * align) / (2L * den * align)
        return maxOf(align.toLong(), q * align).toInt()
    }
}
