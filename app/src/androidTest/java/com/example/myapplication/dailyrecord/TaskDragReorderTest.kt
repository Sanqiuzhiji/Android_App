package com.example.myapplication.dailyrecord

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.myapplication.feature.dailyrecord.ui.components.TaskDragReorder
import com.example.myapplication.feature.dailyrecord.ui.components.TaskDragOverlay
import com.example.myapplication.feature.dailyrecord.ui.components.rememberTaskDragReorder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class TaskDragReorderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var drag: TaskDragReorder
    private lateinit var list: LazyListState
    private var ids by mutableStateOf(emptyList<String>())
    private val drops = mutableListOf<Pair<String, String>>()
    private var acceptSave = true
    private var deferSave = false
    private var commitSave: (() -> Unit)? = null

    private fun start(count: Int) {
        ids = (0 until count).map { "task-$it" }
        compose.setContent {
            list = rememberLazyListState()
            drag = rememberTaskDragReorder(list, ids, true) { id, target, done ->
                drops += id to target
                val commit = {
                    if (acceptSave) {
                        val position = ids.indexOf(target)
                        ids = ids.toMutableList().apply { remove(id); add(position, id) }
                    }
                    done(acceptSave)
                }
                if (deferSave) commitSave = commit else commit()
            }
            Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds()) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("list").then(drag.gestureModifier()),
                    state = list, contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Text("固定任务，不参与临时任务排序", Modifier.height(32.dp).testTag("fixed")) }
                    items(drag.ordered(ids) { it }, key = { it }) { id ->
                        // Different heights exercise hit-testing and pointer anchoring after swaps.
                        Box(Modifier.fillMaxWidth().height(if (id == "task-1") 104.dp else 72.dp)
                            .animateItem(placementSpec = if (drag.activeId == id) null else androidx.compose.animation.core.spring())
                            .testTag(id).then(drag.itemModifier(id))) { Text(id) }
                    }
                    item { Text("已完成，不参与排序", Modifier.height(80.dp).testTag("completed")) }
                }
                TaskDragOverlay(drag) { id ->
                    Box(Modifier.fillMaxWidth().height(if (id == "task-1") 104.dp else 72.dp)) { Text(id) }
                }
            }
        }
        compose.waitForIdle()
        // The drag's frame loop intentionally stays active while the finger is held.
        compose.mainClock.autoAdvance = false
    }

    private fun centerInList(id: String): Offset =
        compose.onNodeWithTag(id).fetchSemanticsNode().boundsInRoot.center -
            compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot.topLeft

    private fun hold(id: String) {
        val point = centerInList(id)
        compose.onNodeWithTag("list").performTouchInput {
            down(point)
            advanceEventTime(1_000)
        }
        compose.mainClock.advanceTimeBy(1_000)
        pump()
    }

    private fun move(point: Offset) {
        compose.onNodeWithTag("list").performTouchInput { moveTo(point, delayMillis = 100) }
        pump()
    }

    private fun pump() {
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    private fun preview() = compose.runOnIdle { drag.ordered(ids) { it } }

    @Test fun neighboursMoveBeforeReleaseAndCancelRestoresOrder() {
        start(3)
        val original = ids.toList()
        val target = centerInList("task-2")
        val neighbourTop = compose.onNodeWithTag("task-1").fetchSemanticsNode().boundsInRoot.top
        hold("task-0")
        move(target)
        repeat(20) { pump() }
        assertEquals(listOf("task-1", "task-2", "task-0"), preview())
        assertTrue(compose.onNodeWithTag("task-1").fetchSemanticsNode().boundsInRoot.top < neighbourTop)
        compose.runOnIdle { assertEquals(original, ids); assertTrue(drops.isEmpty()) }
        compose.onNodeWithTag("list").performTouchInput { cancel() }
        pump()
        assertEquals(original, preview())
        compose.runOnIdle { assertTrue(drops.isEmpty()) }
    }

    @Test fun holdingAtEdgesScrollsToBothEndsAndSavesOncePerDrop() {
        start(24)
        hold("task-0")
        val bounds = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        move(Offset(bounds.width / 2f, bounds.height - 2f))
        // No additional move events: a stationary finger at the edge must keep scrolling.
        for (frame in 0 until 500) {
            pump()
            if (preview().last() == "task-0") break
        }
        assertEquals("task-0", preview().last())
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > 0); assertTrue(drops.isEmpty()) }
        compose.onNodeWithTag("list").performTouchInput { up() }
        repeat(20) { pump() }
        compose.runOnIdle { assertEquals("task-0", ids.last()); assertEquals(1, drops.size) }
        hold("task-0")
        move(Offset(bounds.width / 2f, 2f))
        for (frame in 0 until 500) {
            pump()
            if (preview().first() == "task-0") break
        }
        assertEquals("task-0", preview().first())
        compose.onNodeWithTag("list").performTouchInput { up() }
        repeat(20) { pump() }
        compose.runOnIdle { assertEquals("task-0", ids.first()); assertEquals(2, drops.size) }
    }

    @Test fun failedSaveRestoresOrderAndExcludedRowsCannotStartDragging() {
        start(3)
        val original = ids.toList()
        val target = centerInList("task-2")
        hold("fixed")
        compose.runOnIdle { assertFalse(drag.isDragging) }
        compose.onNodeWithTag("list").performTouchInput { up() }
        pump()
        hold("task-0")
        move(target)
        assertNotEquals(original, preview())
        compose.runOnIdle { acceptSave = false }
        compose.onNodeWithTag("list").performTouchInput { up() }
        repeat(20) { pump() }
        assertEquals(original, preview())
        compose.runOnIdle { assertFalse(drag.isBusy); assertEquals(original, ids) }
    }

    @Test fun previewRemainsUntilDelayedSaveIsAcknowledged() {
        start(3)
        compose.runOnIdle { deferSave = true }
        val original = ids.toList()
        val target = centerInList("task-2")
        hold("task-0")
        move(target)
        val expected = preview()
        compose.onNodeWithTag("list").performTouchInput { up() }
        repeat(20) { pump() }
        assertEquals(expected, preview())
        compose.runOnIdle { assertEquals(original, ids); assertTrue(drag.isBusy); commitSave!!.invoke() }
        repeat(20) { pump() }
        compose.runOnIdle { assertEquals(expected, ids); assertFalse(drag.isBusy); assertEquals(1, drops.size) }
    }
}
