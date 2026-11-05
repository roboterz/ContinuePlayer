package com.aerolite.continueplayer.data.lyrics

data class LyricEntry(
    val timeMs: Long,
    val text: String
)

data class LyricData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val offsetMs: Long = 0L,
    val entries: List<LyricEntry> = emptyList()
) {
    fun findCurrentIndex(positionMs: Long): Int {
        if (entries.isEmpty()) return -1
        val targetTime = positionMs + offsetMs
        if (targetTime < entries.first().timeMs) return -1

        var low = 0
        var high = entries.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (entries[mid].timeMs <= targetTime) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
