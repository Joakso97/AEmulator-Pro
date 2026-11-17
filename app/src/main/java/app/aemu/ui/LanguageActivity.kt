package app.aemu.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R

class LanguageActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AemuTheme {
                LanguageScreen(
                    selectedLanguage = AppPrefs.language(this),
                    onBack = ::finish,
                    onSelect = { tag ->
                        if (tag != AppPrefs.language(this)) {
                            AppPrefs.setLanguage(this, tag)
                            setResult(RESULT_OK)
                        }
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageScreen(selectedLanguage: String, onBack: () -> Unit, onSelect: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.as_language)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).selectableGroup(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(AppPrefs.LANGUAGES, key = { it.first }) { (tag, name) ->
                val selected = tag == selectedLanguage
                ListItem(
                    headlineContent = {
                        Text(
                            name.ifEmpty { stringResource(R.string.as_lang_system) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(tag) },
                    ),
                )
            }
        }
    }
}
