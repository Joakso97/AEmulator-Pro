
package app.aemu.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R

class SupportedFirmwareActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { SupportedFirmwareScreen(onBack = ::finish) } }
    }
}



private val SKINS = listOf(
    "AOSP", "MIUI", "HyperOS", "TouchWiz", "One UI", "Sense", "Xperia", "ColorOS", "EMUI",
    "Funtouch", "OriginOS", "Flyme", "MotoBlur", "CyanogenMod", "LineageOS", "LG",
    "Wear OS", "Android TV", "Glass", "Desktop",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SupportedFirmwareScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.help_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(stringResource(R.string.sf_versions), stringResource(R.string.sf_versions_sub)) {
                    FwRow("Android 2.3", stringResource(R.string.sf_v23_sub))
                    FwRow("Android 4.0–4.4", stringResource(R.string.sf_v40_sub))
                    FwRow("Android 5.x–6.x", stringResource(R.string.sf_v50_sub))
                    FwRow("Android 7.x", stringResource(R.string.sf_v70_sub))
                    FwRow("Android 8.0+", stringResource(R.string.sf_v80_sub))
                }
            }

            item {
                Section(stringResource(R.string.sf_arch), stringResource(R.string.sf_arch_sub)) {
                    FwRow("ARM 32-bit", stringResource(R.string.sf_arch_arm_sub))
                    FwRow("ARM 64-bit", stringResource(R.string.sf_arch_arm64_sub))
                    FwRow("x86 (32-bit)", stringResource(R.string.sf_arch_x86_sub))
                    FwRow("x86-64", stringResource(R.string.sf_arch_x64_sub))
                }
            }

            item {
                Section(stringResource(R.string.sf_formats), stringResource(R.string.sf_formats_sub)) {
                    FwRow(stringResource(R.string.sf_fmt_zip), stringResource(R.string.sf_fmt_zip_sub))
                    FwRow(stringResource(R.string.sf_fmt_odin), stringResource(R.string.sf_fmt_odin_sub))
                    FwRow(stringResource(R.string.sf_fmt_factory), stringResource(R.string.sf_fmt_factory_sub))
                    FwRow(stringResource(R.string.sf_fmt_system), stringResource(R.string.sf_fmt_system_sub))
                    FwRow(stringResource(R.string.sf_fmt_twrp), stringResource(R.string.sf_fmt_twrp_sub))
                    FwRow(stringResource(R.string.sf_fmt_ota), stringResource(R.string.sf_fmt_ota_sub))
                    FwRow(stringResource(R.string.sf_fmt_squash), stringResource(R.string.sf_fmt_squash_sub))
                    FwRow(stringResource(R.string.sf_fmt_boot), stringResource(R.string.sf_fmt_boot_sub))
                    FwRow(stringResource(R.string.sf_fmt_iso), stringResource(R.string.sf_fmt_iso_sub))
                    FwRow(stringResource(R.string.sf_fmt_vm), stringResource(R.string.sf_fmt_vm_sub))
                    FwRow(stringResource(R.string.sf_fmt_arch), stringResource(R.string.sf_fmt_arch_sub))
                }
            }

            item {
                Section(stringResource(R.string.sf_skins), stringResource(R.string.sf_skins_sub)) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SKINS.forEach { name -> AssistChip(onClick = {}, label = { Text(name) }) }
                    }
                }
            }
        }
    }
}



@Composable
private fun FwRow(title: String, sub: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}
