
package app.aemu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R

class CreditsActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { CreditsScreen(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreditsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    
    val notices = remember {
        listOf("NOTICE.md", "license-audit.md", "LICENSE").joinToString("\n\n") { file ->
            ctx.assets.open("legal/$file").bufferedReader().use { it.readText() }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.credits_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(stringResource(R.string.as_links)) {
                    Row_(Icons.Rounded.Person, stringResource(R.string.as_author), "somerandomusername2") { open(Links.AUTHOR) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_orig), "t.me/istratiit_ech") { open(Links.ORIGINAL) }
                    Row_(Icons.Rounded.Code, stringResource(R.string.as_github), "github.com/Joakso97/AEmulator-Pro") { open(Links.GITHUB) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_upstream_row, "AEmulator Sunset"), "github.com/drel4/AEmulator-Sunset") { open(Links.SUNSET) }
                    Row_(Icons.Rounded.History, stringResource(R.string.as_original_row, "AEmulator"), "github.com/uxazu/AEmulator") { open(Links.UPSTREAM) }
                }
            }

            item {
                Section(stringResource(R.string.credits_title)) {
                    
                    CreditRow("AEmulator Super Plus", "GPL-3.0 · 2026-10-12")
                    CreditRow("@regectt", stringResource(R.string.th_group_skins))
                    CreditRow("HTC Desire HD · HTC One M7", stringResource(R.string.as_orig_sub))
                }
            }

            item {
                Section(stringResource(R.string.credits_components)) {
                    CreditRow("QEMU", "GPL-2.0")
                    CreditRow("SwiftShader", "Apache-2.0")
                    CreditRow("Android Open Source Project", "Apache-2.0")
                    CreditRow("Apache Commons Compress", "Apache-2.0")
                    CreditRow("Junrar", "Apache-2.0")
                    CreditRow("Brotli", "MIT")
                    CreditRow("zstd-jni", "BSD-2-Clause")
                    CreditRow("lzo-java", "Apache-2.0")
                }
            }

            item {
                Section(stringResource(R.string.as_license_notices)) {
                    SelectionContainer {
                        Text(notices, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun CreditRow(title: String, sub: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}