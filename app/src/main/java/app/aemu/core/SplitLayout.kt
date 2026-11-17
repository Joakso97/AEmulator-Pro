
package app.aemu.core


object SplitLayout {
    const val MATCH = -1 

    fun isSide(c: Char) = c == 'T' || c == 'B' || c == 'L' || c == 'R'

    fun opposite(pos: Char): Char = when (pos) { 'T' -> 'B'; 'B' -> 'T'; 'L' -> 'R'; else -> 'L' }

    
    fun zoneAt(x: Float, y: Float, w: Float, h: Float, edge: Float = 0.3f): Char? {
        if (w <= 0f || h <= 0f) return null
        val left = x / w
        val right = 1f - left
        val top = y / h
        val bottom = 1f - top
        val nearest = minOf(left, right, top, bottom)
        if (nearest > edge) return null
        return when (nearest) { top -> 'T'; bottom -> 'B'; left -> 'L'; else -> 'R' }
    }

    
    fun paneSize(pos: Char?, w: Int, h: Int, gap: Int): Pair<Int, Int> = when (pos) {
        'T', 'B' -> MATCH to (h / 2 - gap / 2)
        'L', 'R' -> (w / 2 - gap / 2) to MATCH
        else -> MATCH to MATCH
    }

    
    fun paneRect(pos: Char, w: Float, h: Float): FloatArray = when (pos) {
        'T' -> floatArrayOf(0f, 0f, w, h / 2)
        'B' -> floatArrayOf(0f, h / 2, w, h)
        'L' -> floatArrayOf(0f, 0f, w / 2, h)
        else -> floatArrayOf(w / 2, 0f, w, h)
    }
}
