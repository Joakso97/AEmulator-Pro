package app.aemu.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.update.ChangelogEntry

class ChangelogActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { ChangelogScreen(onBack = ::finish) } }
    }
}


private sealed interface ChangelogState {
    object Loading : ChangelogState
    object Error : ChangelogState
    data class Loaded(val entries: List<ChangelogEntry>) : ChangelogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangelogScreen(onBack: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ChangelogState>(ChangelogState.Loading) }

    LaunchedEffect(revision) {
        state = ChangelogState.Loading
        state = ChangelogState.Loaded(listOf(
            ChangelogEntry(
                id = 1L,
                tagName = "v0.0.0.3.15",
                title = "Release 0.0.0.3.15",
                body = "- **Added: Changelog the version latest**\n" +
                        "A new Changelog section to keep track of the latest updates.\n" +
                        "- **Added: Android 1.0 Supported boot the HTC**\n" +
                        "Feature new supported for booting Android 1.x versions.\n" +
                        "- **Added: Fab Toggle Background**\n" +
                        "New a background toggle option the Action Button\n" +
                        "- **Updated: APIs**\n" +
                        "Changed API with the update API functionality.\n" +
                        "- **Removed VM Tab Bar**\n" +
                        "Layout the VM session tab bar from the interface.\n" +
                        "- **Updated Settings**\n" +
                        "Added changelog the settings."
            )
        ))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { revision++ }, enabled = state !is ChangelogState.Loading) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.catalog_refresh))
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                ChangelogState.Loading -> item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(Modifier.size(48.dp))
                    }
                }
                ChangelogState.Error -> item {
                    Column(
                        modifier = Modifier.fillParentMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        Text(
                            stringResource(R.string.changelog_error),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { revision++ }) { Text(stringResource(R.string.retry)) }
                    }
                }
                is ChangelogState.Loaded -> {
                    if (current.entries.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.changelog_empty), textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        items(current.entries, key = { it.id }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                SelectionContainer {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(entry.title, style = MaterialTheme.typography.titleLarge)
                                        if (entry.title != entry.tagName) {
                                            Text(
                                                entry.tagName,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            if (entry.body.isBlank()) AnnotatedString(stringResource(R.string.changelog_no_notes))
                                            else parseMarkdown(entry.body),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseMarkdown(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val lines = text.lines()
    for ((index, line) in lines.withIndex()) {
        var trimmed = line.trim()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            trimmed = "• " + trimmed.removePrefix("- ").removePrefix("* ")
        }
        val parts = trimmed.split("**")
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty()) continue
            if (i % 2 == 1) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(part)
                builder.pop()
            } else {
                builder.append(part)
            }
        }
        if (index < lines.size - 1) {
            builder.append("\n")
        }
    }
    return builder.toAnnotatedString()
}

