package io.legado.app.model.localBook

import java.util.Locale

/** File-name policy shared by directory discovery and chapter ordering. */
object LocalMediaNames {
    enum class Kind { IMAGE, VIDEO }

    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif")
    private val videos = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts", "flv", "3gp")

    fun kind(name: String): Kind? {
        if (name.startsWith('.')) return null
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            in images -> Kind.IMAGE
            in videos -> Kind.VIDEO
            else -> null
        }
    }

    // Compare digit runs without integer conversion, including arbitrarily large numbers.
    val comparator = Comparator<String> { left, right ->
        var a = 0
        var b = 0
        var result = 0
        while (a < left.length && b < right.length && result == 0) {
            if (left[a] in '0'..'9' && right[b] in '0'..'9') {
                val aStart = a
                val bStart = b
                while (a < left.length && left[a] in '0'..'9') a++
                while (b < right.length && right[b] in '0'..'9') b++
                val x = left.substring(aStart, a).trimStart('0').ifEmpty { "0" }
                val y = right.substring(bStart, b).trimStart('0').ifEmpty { "0" }
                result = x.length.compareTo(y.length)
                if (result == 0) result = x.compareTo(y)
            } else {
                result = left[a].lowercaseChar().compareTo(right[b].lowercaseChar())
                a++
                b++
            }
        }
        if (result == 0) result = (left.length - a).compareTo(right.length - b)
        if (result == 0) result = left.compareTo(right)
        result
    }
}
