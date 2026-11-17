
package app.aemu.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.aemu.R
import app.aemu.core.Keeper
import app.aemu.core.TabRegistry
import app.aemu.core.VmSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


internal object TabNav {
    private fun prepare(ctx: Context, intent: Intent): Intent {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    @Suppress("DEPRECATION")
    private fun noTransition(ctx: Context) { (ctx as? Activity)?.overridePendingTransition(0, 0) }

    
    fun show(ctx: Context, intent: Intent) {
        ctx.startActivity(prepare(ctx, intent))
        noTransition(ctx)
    }

    fun openHome(ctx: Context) = show(ctx, Intent(ctx, MainActivity::class.java))

    
    fun openTab(ctx: Context, tab: TabRegistry.Tab) {
        val intents = tab.ids.mapNotNull { id ->
            val slot = VmSlots.slotOf(ctx, id) ?: return@mapNotNull null
            prepare(ctx, VmActivity.intentFor(ctx, slot, id, overlay = tab.isOverlay(id)))
        }
        when (intents.size) {
            0 -> return
            1 -> ctx.startActivity(intents[0])
            else -> ctx.startActivities(intents.toTypedArray())
        }
        noTransition(ctx)
    }

    
    fun openVm(ctx: Context, id: String) = openTab(ctx, TabRegistry.entryOf(ctx, id) ?: TabRegistry.Tab(listOf(id)))

    
    fun neighbor(ctx: Context, closing: String): TabRegistry.Tab? {
        val all = TabRegistry.entries(ctx)
        val i = all.indexOfFirst { closing in it.ids }
        all.getOrNull(i)?.otherThan(closing)?.let { return TabRegistry.Tab(listOf(it)) }
        val snap = VmSlots.snapshot(ctx)
        val others = all.filterIndexed { idx, t -> idx != i && (!snap.reliable || t.ids.any { snap.slotOf(it) != null }) }
        if (others.isEmpty()) return null
        return all.take(maxOf(i, 0)).lastOrNull { it in others } ?: others.first()
    }

    fun go(ctx: Context, tab: TabRegistry.Tab?) { if (tab == null) openHome(ctx) else openTab(ctx, tab) }
}


internal object TabActions {
    
    fun minimize(ctx: Context, id: String) = TabRegistry.close(ctx, id)

    
    fun shutdown(ctx: Context, id: String) {
        val slot = VmSlots.slotOf(ctx, id)
        TabRegistry.close(ctx, id)
        if (slot != null) Keeper.requestShutdown(ctx, slot)
    }
}


@Composable
internal fun rememberVmTabs(): State<List<TabRegistry.Tab>> {
    val ctx = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf<List<TabRegistry.Tab>>(emptyList()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.value = withContext(Dispatchers.IO) { runCatching { TabRegistry.live(ctx) }.getOrDefault(state.value) }
                delay(1000)
            }
        }
    }
    return state
}


@Composable
internal fun VmTabStrip(
    tabs: List<TabRegistry.Tab>,
    current: String?,
    topInset: Boolean,
    modifier: Modifier = Modifier,
    allowSplitDrag: Boolean = false,
    inSplit: Boolean = false,
    onCloseCurrent: ((shutdown: Boolean) -> Unit)? = null,
    onHide: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    var closing by remember { mutableStateOf<TabRegistry.Tab?>(null) }

    Surface(modifier.fillMaxWidth(), color = cs.surfaceContainer) {
        Column(if (topInset) Modifier.statusBarsPadding() else Modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = if (onHide == null) 12.dp else 4.dp).animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    
                    FilterChip(
                        selected = current == null,
                        onClick = { if (current != null) TabNav.openHome(ctx) },
                        label = { Icon(Icons.Rounded.Home, stringResource(R.string.tab_home), Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                    for (tab in tabs) {
                        val selected = current != null && current in tab.ids
                        val dragName = tab.names[0]
                        val dragBg = cs.secondaryContainer.toArgb()
                        val dragFg = cs.onSecondaryContainer.toArgb()
                        val dragModifier = if (allowSplitDrag && !tab.isGroup && !selected) {
                            
                            Modifier.pointerInput(tab.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { DragTab.start(view, tab.id, TabRegistry.shortName(dragName), dragBg, dragFg) },
                                    onDrag = { _, _ -> },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                )
                            }
                        } else Modifier
                        InputChip(
                            selected = selected,
                            onClick = { if (!selected) TabNav.openTab(ctx, tab) },
                            modifier = dragModifier,
                            label = {
                                Text(
                                    if (tab.isGroup) stringResource(R.string.tab_group_title) else TabRegistry.shortName(tab.names[0]),
                                    maxLines = 1, softWrap = false,
                                )
                            },
                            trailingIcon = {
                                
                                Box(
                                    Modifier.size(24.dp).clip(CircleShape)
                                        .clickable { if (tab.isGroup || inSplit) TabDialogActivity.open(ctx, tab.id) else closing = tab },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, stringResource(if (tab.isGroup) R.string.tab_split_action else R.string.tab_close), Modifier.size(InputChipDefaults.IconSize))
                                }
                            },
                        )
                    }
                }
                if (onHide != null) {
                    
                    Box(
                        Modifier.padding(end = 6.dp).size(width = 28.dp, height = 24.dp).clip(CircleShape).clickable(onClick = onHide),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.tab_bar_hide), Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
        }
    }

    closing?.let { tab ->
        fun answer(shutdown: Boolean) {
            closing = null
            val app = ctx.applicationContext
            
            for (id in tab.ids) {
                if (id == current && onCloseCurrent != null) continue
                if (shutdown) TabActions.shutdown(app, id) else TabActions.minimize(app, id)
            }
            if (current != null && current in tab.ids) onCloseCurrent?.invoke(shutdown)
        }
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.tab_close_title, tab.names[0])) },
            text = { Text(stringResource(R.string.tab_close_text)) },
            confirmButton = {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { answer(true) }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) {
                        Text(stringResource(R.string.tab_shutdown))
                    }
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.tab_minimize))
                    }
                }
            },
        )
    }
}


@Composable
internal fun TabBarRevealArrow(onShow: () -> Unit, modifier: Modifier = Modifier) {
    
    Box(modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(end = 8.dp)) {
        Surface(
            modifier = Modifier.alpha(0.7f).clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)).clickable(onClick = onShow),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
        ) {
            Box(Modifier.size(width = 30.dp, height = 18.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.tab_bar_show), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
