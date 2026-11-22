package app.aemu.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.core.GuestImage
import org.json.JSONArray
import org.json.JSONObject

internal data class PinnedLayout(val pinned: List<String> = emptyList()) {
    fun isPinned(id: String): Boolean = id in pinned

    fun toggle(id: String): PinnedLayout = copy(
        pinned = if (id in pinned) pinned.filter { it != id } else pinned + id
    )
}

internal data class PinnedActions(
    val onStart: (String) -> Unit,
    val onSettings: (String) -> Unit,
    val onRename: (String) -> Unit,
    val onNewMachine: (String) -> Unit,
    val onReset: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onTogglePin: (String) -> Unit,
)

internal object PinnedStore {
    fun load(ctx: Context): PinnedLayout = runCatching {
        val json = AppPrefs.pinnedGroups(ctx)?.let { JSONObject(it) } ?: return PinnedLayout()
        val arr = json.optJSONArray("pinned") ?: return PinnedLayout()
        PinnedLayout((0 until arr.length()).mapNotNull { arr.optString(it, null) })
    }.getOrDefault(PinnedLayout())

    fun save(ctx: Context, layout: PinnedLayout) {
        AppPrefs.setPinnedGroups(ctx, JSONObject().put("pinned", JSONArray(layout.pinned)).toString())
    }
}

@Composable
internal fun PinnedBar(
    layout: PinnedLayout,
    images: List<GuestImage>,
    actions: PinnedActions,
    modifier: Modifier = Modifier,
) {
    val byId = images.associateBy { it.id }
    val pinnedImages = layout.pinned.mapNotNull { byId[it] }
    if (pinnedImages.isNotEmpty()) {
        Column(modifier) {
            Text(
                text = stringResource(R.string.pinned),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pinnedImages.forEach { img ->
                    Box(Modifier.width(160.dp)) {
                        MachineCard(
                            img = img,
                            baseName = images.firstOrNull { it.id == img.baseId }?.name,
                            isPinned = true,
                            onStart = { actions.onStart(img.id) },
                            onSettings = { actions.onSettings(img.id) },
                            onRename = { actions.onRename(img.id) },
                            onNewMachine = { actions.onNewMachine(img.id) },
                            onReset = { actions.onReset(img.id) },
                            onDelete = { actions.onDelete(img.id) },
                            onTogglePin = { actions.onTogglePin(img.id) },
                        )
                    }
                }
            }
        }
    }
}
