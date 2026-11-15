package io.legado.app.help.gsyVideo

internal object IntroOutroSkipPolicy {
    fun isValid(duration: Long, introSeconds: Int, outroSeconds: Int, live: Boolean): Boolean {
        if (live || duration <= 0 || introSeconds < 0 || outroSeconds < 0) return false
        return (introSeconds.toLong() + outroSeconds) * 1000L < duration
    }

    fun markedSeconds(duration: Long, position: Long, intro: Boolean): Int? {
        if (duration <= 0 || position !in 0 until duration) return null
        val seconds = (if (intro) position else duration - position) / 1000L
        return seconds.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
}
