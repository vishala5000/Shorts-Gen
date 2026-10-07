package com.shortsgen.app.video

/** Output format required for every clip. */
object VideoSpec {
    /** 1080 x 1920 (9:16 vertical short) */
    const val WIDTH = 1080
    const val HEIGHT = 1920

    /** The text is wrapped and centred inside this box. */
    const val TEXT_BOX_WIDTH = 680
    const val TEXT_BOX_HEIGHT = 1320

    const val FPS = 30
    const val VIDEO_BITRATE = 8_000_000
    const val I_FRAME_INTERVAL_SECONDS = 1
    const val AUDIO_BITRATE = 128_000

    /**
     * The video track is made this much longer than the speech so the audio is never
     * cut off by the last AAC frame / the last video frame.
     */
    const val TAIL_US = 100_000L

    const val MIME_AVC = "video/avc"
    const val MIME_AAC = "audio/mp4a-latm"
}
