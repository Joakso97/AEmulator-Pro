
package app.aemu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.OperatorPreset
import app.aemu.core.SimCard
import app.aemu.core.SimCards
import app.aemu.core.VmPaths
import app.aemu.core.VmSettings


@Composable
internal fun SimSection(img: GuestImage, s: VmSettings, set: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    
    val apns = remember(img.id) { SimCards.apnOperators(VmPaths(ctx, img.id).root) }
    Text(stringResource(R.string.vs_sim_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    SimFields(stringResource(R.string.vs_sim_present), s.sim, apns, { set(s.copy(sim = it)) },
        afterPresent = {
            Toggle(stringResource(R.string.vs_dual_sim), stringResource(R.string.vs_dual_sim_sub), s.dualSim) { set(s.copy(dualSim = it)) }
        })
    if (s.dualSim) SimFields(stringResource(R.string.vs_sim2_present), s.sim2, apns, set = { set(s.copy(sim2 = it)) })
}

@Composable
private fun SimFields(
    title: String,
    sim: SimCard,
    apns: Set<Pair<String, String>>,
    set: (SimCard) -> Unit,
    afterPresent: @Composable () -> Unit = {},
) {
    Toggle(title, stringResource(R.string.vs_sim_present_sub), sim.present) { set(sim.copy(present = it)) }
    afterPresent()
    if (!sim.present) return
    OperatorPicker(sim, apns) { p ->
        set(if (p == null) sim.copy(operator = "", mcc = "", mnc = "") else sim.copy(operator = p.name, mcc = p.mcc, mnc = p.mnc))
    }
    EditableSetting(
        label = stringResource(R.string.vs_sim_name),
        value = sim.operator,
        placeholder = SimCard.DEFAULT_OPERATOR,
        sanitize = { SimCard.cleanOperator(it) },
    ) { set(sim.copy(operator = it)) }
    val partial = (sim.mcc.isNotEmpty() || sim.mnc.isNotEmpty()) && !SimCard.codeValid(sim.mcc, sim.mnc)
    val noApn = SimCard.codeValid(sim.mcc, sim.mnc) && apns.isNotEmpty() && (sim.mcc to sim.mnc) !in apns
    EditableSetting("MCC", sim.mcc,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        sanitize = { SimCard.cleanMcc(it) },
    ) { set(sim.copy(mcc = it)) }
    EditableSetting("MNC", sim.mnc,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        sanitize = { SimCard.cleanMnc(it) },
    ) { set(sim.copy(mnc = it)) }
    when {
        partial -> Text(stringResource(R.string.vs_sim_code_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        noApn -> Text(stringResource(R.string.vs_sim_no_apn), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    EditableSetting(
        label = stringResource(R.string.vs_sim_number),
        value = sim.number,
        placeholder = "+351912345678",
        supportingText = stringResource(R.string.vs_sim_number_sub),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        sanitize = { SimCard.cleanNumber(it) },
    ) { set(sim.copy(number = it)) }
}


@Composable
private fun OperatorPicker(sim: SimCard, apns: Set<Pair<String, String>>, pick: (OperatorPreset?) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = SimCards.PRESETS.firstOrNull { it.mcc == sim.mcc && it.mnc == sim.mnc }
    val presets = remember(apns) { SimCards.PRESETS.sortedBy { if ((it.mcc to it.mnc) in apns) 0 else 1 } }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_sim_preset)) },
        supportingContent = { Text(stringResource(R.string.vs_sim_preset_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) {
                    Text(current?.name ?: if (sim.mcc.isEmpty() && sim.mnc.isEmpty()) stringResource(R.string.vs_sim_default) else "${sim.mcc}-${sim.mnc}")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.vs_sim_default)) }, onClick = { open = false; pick(null) })
                    presets.forEach { p ->
                        val ok = (p.mcc to p.mnc) in apns
                        DropdownMenuItem(text = { Text("${if (ok) "✓ " else ""}${SimCards.countryLabel(ctx, p)} · ${p.name} (${p.mcc}-${p.mnc})") },
                            onClick = { open = false; pick(p) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}
