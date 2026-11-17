
package app.aemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.VmSettings


@Composable
internal fun CdmaToggle(s: VmSettings, firmwareLteOnCdma: Boolean, set: (VmSettings) -> Unit) {
    val on = s.cdma ?: firmwareLteOnCdma
    val note = stringResource(if (firmwareLteOnCdma) R.string.vs_cdma_fw_yes else R.string.vs_cdma_fw_no)
    Toggle(stringResource(R.string.vs_cdma), stringResource(R.string.vs_cdma_sub) + " " + note, on) { v ->
        set(s.copy(cdma = if (v == firmwareLteOnCdma) null else v))
    }
}
