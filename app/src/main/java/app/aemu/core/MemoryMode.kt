package app.aemu.core


enum class MemoryMode(
    val id: String,
    val minHostMb: Long,
    val minHostLowRamMb: Long,
    val tightFactor: Double,
    val maxCached: Int,
    val maxCachedLowRam: Int,
    val keepCached: Int,
    val hardHostMb: Long,
    val budgetFactor: Double,
    val intervalMs: Long,
    val pauseMs: Long,
) {
    
    LIGHT("light", 800, 1500, 1.0, 16, 8, 8, 250, 3.0, 4000, 8000),

    
    MEDIUM("medium", 1500, 2500, 2.0, 10, 4, 3, 400, 2.0, 2000, 3000),

    
    AGGRESSIVE("aggressive", 3000, 4500, 2.0, 6, 3, 2, 800, 1.5, 1000, 1500);

    
    fun shouldKill(lowRam: Boolean, ramMb: Int, avail: Long, cachedCount: Int, rssSumMb: Long): Boolean {
        val minHost = if (lowRam) minHostLowRamMb else minHostMb
        val cachedLimit = if (ramMb > 0) (ramMb / 128).coerceIn(2, maxCached) else if (lowRam) maxCachedLowRam else maxCached
        val overBudget = ramMb > 0 && rssSumMb > ramMb * budgetFactor
        val tight = avail < minHost * tightFactor
        
        if (avail >= minHost && !(tight && (cachedCount > cachedLimit || overBudget))) return false
        
        if (cachedCount <= keepCached && avail >= hardHostMb && !overBudget) return false
        return true
    }

    companion object {
        val DEFAULT = MEDIUM

        
        fun parse(id: String?): MemoryMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
