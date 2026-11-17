package app.aemu.core


internal object SetupWizardOption {
    fun restoreSonyFlow(skip: Boolean, hasState: Boolean) = !skip && !hasState
    fun runHelper(skip: Boolean, hasState: Boolean) = skip || hasState
}
