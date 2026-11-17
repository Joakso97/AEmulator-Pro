package app.aemu.core


object GtiGralloc {
    const val ASSET = "gralloc.default_gti5500.so"

    
    fun applies(firmware: ByteArray?, engine: ByteArray?): Boolean =
        firmware != null && engine != null &&
            GrallocProbe.verdict(engine) == GrallocProbe.Verdict.UNGUARDED &&
            GrallocProbe.verdict(firmware) == GrallocProbe.Verdict.GUARDED
}
