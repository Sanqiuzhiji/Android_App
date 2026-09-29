package com.example.myapplication.feature.dailyrecord.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Preview reordering in memory; persist once on release, including failure acknowledgement. */
@Composable
fun rememberTaskDragReorder(
    list: LazyListState,
    ids: List<String>,
    enabled: Boolean,
    onDrop: (String, String, (Boolean) -> Unit) -> Unit,
): TaskDragReorder {
    val currentIds = rememberUpdatedState(ids)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentDrop = rememberUpdatedState(onDrop)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val drag = remember(list) { TaskDragReorder(list) }
    SideEffect {
        drag.canStart = { currentEnabled.value }
        drag.sourceIds = { currentIds.value }
        drag.drop = { id, target, done -> currentDrop.value(id, target, done) }
        drag.feedback = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        drag.edgeSize = with(density) { 72.dp.toPx() }
        drag.maxScrollSpeed = with(density) { 900.dp.toPx() }
        drag.elevation = with(density) { 8.dp.toPx() }
    }
    LaunchedEffect(ids, enabled) { drag.synchronize(ids, enabled) }
    LaunchedEffect(drag.activeId) {
        if (drag.isDragging) {
            var previousFrame = withFrameNanos { it }
            while (drag.isDragging) {
                val frame = withFrameNanos { it }
                val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
                previousFrame = frame
                drag.advanceFrame(seconds)
            }
        }
    }
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    return drag
}

class TaskDragReorder internal constructor(private val list: LazyListState) {
    internal var canStart: () -> Boolean = { false }
    internal var sourceIds: () -> List<String> = { emptyList() }
    internal var drop: (String, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) }
    internal var feedback: () -> Unit = {}
    internal var edgeSize = 72f
    internal var maxScrollSpeed = 900f
    internal var elevation = 8f
    var activeId by mutableStateOf<String?>(null)
        private set
    private var preview by mutableStateOf<List<String>?>(null)
    private var original = emptyList<String>()
    private var fingerY by mutableFloatStateOf(0f)
    private var grabOffset = 0f
    private var itemHeight = 0
    private var firstSortableIndex = 0
    private var savingDrop by mutableStateOf(false)
    private var saveSucceeded = false
    private var session = 0

    val isDragging: Boolean get() = activeId != null
    val isBusy: Boolean get() = isDragging || savingDrop

    /** Both screens render this order, so neighbours move before the finger is released. */
    fun <T> ordered(items: List<T>, id: (T) -> String): List<T> {
        val order = preview ?: return items
        val byId = items.associateBy(id)
        if (byId.keys != order.toSet()) return items
        return order.map { byId.getValue(it) }
    }

    // The real lazy item remains a placeholder; the floating copy survives item recycling.
    fun itemModifier(id: String): Modifier = Modifier.graphicsLayer {
        alpha = if (id == activeId) 0f else 1f
    }

    internal fun overlayModifier(): Modifier = Modifier.zIndex(1f).graphicsLayer {
        translationY = floatingTop()
        shadowElevation = elevation
    }.clearAndSetSemantics { }

    private fun top(item: LazyListItemInfo): Float =
        (item.offset - list.layoutInfo.viewportStartOffset).toFloat()

    private fun floatingTop() = fingerY - grabOffset

    fun gestureModifier(): Modifier = Modifier.pointerInput(this) {
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                if (canStart() && !isBusy) {
                    val ids = sourceIds()
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
                        point.y >= top(it) && point.y < top(it) + it.size && ids.contains(it.key as? String)
                    }
                    if (item != null) {
                        session++
                        original = ids.toList()
                        preview = original
                        activeId = item.key as String
                        fingerY = point.y
                        grabOffset = point.y - top(item)
                        itemHeight = item.size
                        firstSortableIndex = item.index - ids.indexOf(activeId)
                        feedback()
                    }
                }
            },
            onDrag = { change, amount ->
                if (isDragging) {
                    change.consume()
                    fingerY += amount.y
                    updatePreview()
                }
            },
            onDragEnd = ::finish,
            onDragCancel = { if (isDragging) cancel() },
        )
    }

    /** Wait for preview keys to reach layout before measuring another neighbour. */
    private fun layoutReady(): Boolean {
        val order = preview ?: return false
        return list.layoutInfo.visibleItemsInfo.all { item ->
            val position = order.indexOf(item.key as? String)
            position < 0 || item.index == firstSortableIndex + position
        }
    }

    private fun updatePreview() {
        val id = activeId ?: return
        if (!layoutReady()) return
        val order = preview ?: return
        val items = list.layoutInfo.visibleItemsInfo
        val center = floatingTop() + itemHeight / 2f
        val index = order.indexOf(id)
        val candidates = items.filter { order.contains(it.key as? String) && it.key != id }
        val target = candidates.filter { order.indexOf(it.key as String) > index && center >= top(it) + it.size / 2f }
            .maxByOrNull { it.index }
            ?: candidates.filter { order.indexOf(it.key as String) < index && center <= top(it) + it.size / 2f }
                .minByOrNull { it.index }
            ?: return
        val targetIndex = order.indexOf(target.key as String)
        // Prevent stable-key anchoring from scrolling the viewport during a reorder.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        preview = order.toMutableList().apply { add(targetIndex, removeAt(index)) }
    }

    internal suspend fun advanceFrame(seconds: Float) {
        if (!isDragging) return
        updatePreview()
        if (!layoutReady()) return
        val id = activeId ?: return
        val order = preview ?: return
        val index = order.indexOf(id)
        val info = list.layoutInfo
        val height = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val edge = minOf(edgeSize, height / 3f)
        if (edge <= 0f) return
        val speed = when {
            fingerY < edge && index > 0 -> -((edge - fingerY) / edge).coerceIn(0f, 1f)
            fingerY > height - edge && index < order.lastIndex ->
                ((fingerY - height + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (speed != 0f) {
            list.scrollBy(speed * maxScrollSpeed * seconds)
            updatePreview()
        }
    }

    private fun finish() {
        val id = activeId ?: return
        val order = preview ?: return
        val index = order.indexOf(id)
        val target = original.getOrNull(index)
        activeId = null
        if (target == null || target == id) {
            cancel()
            return
        }
        savingDrop = true
        val token = session
        drop(id, target) { success ->
            if (token == session) {
                saveSucceeded = success
                if (!success || sourceIds() == preview) cancel()
            }
        }
    }

    internal fun synchronize(ids: List<String>, enabled: Boolean) {
        if (isDragging && (ids != original || !enabled)) cancel()
        else if (savingDrop && (ids != original || saveSucceeded && ids == preview)) cancel()
    }

    internal fun cancel() {
        session++
        activeId = null
        preview = null
        original = emptyList()
        savingDrop = false
        saveSucceeded = false
    }
}

/** Place beside the LazyColumn in the same clipped Box, with matching horizontal padding. */
@Composable
fun TaskDragOverlay(drag: TaskDragReorder, content: @Composable (String) -> Unit) {
    drag.activeId?.let { id ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).then(drag.overlayModifier())) {
            content(id)
        }
    }
}
