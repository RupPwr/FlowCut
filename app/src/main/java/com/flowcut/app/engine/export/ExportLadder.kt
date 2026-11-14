package com.flowcut.app.engine.export

object ExportLadder {
    /**
     * Generates an ordered list of fallback specs starting with the requested spec,
     * stepping down to alternate codec, lower FPS, and descending resolutions.
     */
    fun candidates(req: OutputSpec): List<OutputSpec> {
        val out = LinkedHashSet<OutputSpec>()
        val other = if (req.codec == OutCodec.AVC) OutCodec.HEVC else OutCodec.AVC
        val lowFps = if (req.fps >= 50) req.fps / 2 else req.fps // 60->30, 50->25

        out += req
        out += req.copy(codec = other)
        if (lowFps != req.fps) {
            out += req.copy(fps = lowFps)
            out += req.copy(fps = lowFps, codec = other)
        }
        for (side in Resolutions.SHORT_SIDES.reversed().filter { it < req.shortSide }) {
            out += req.copy(shortSide = side, fps = lowFps)
            out += req.copy(shortSide = side, fps = lowFps, codec = other)
        }
        return out.toList()
    }
}
