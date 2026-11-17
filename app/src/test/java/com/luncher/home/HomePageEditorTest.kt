package com.luncher.home

import org.junit.Assert.*
import org.junit.Test

class HomePageEditorTest {
    private fun w(id: String, page: Int, col: Int, row: Int, width: Int, height: Int, visible: Boolean = true) =
        HomePageEditor.Widget(id, WidgetGrid.Placement(page, col, row, width, height), visible)

    @Test fun removingAnEmptyPageShiftsLaterPagesWithoutLosingItems() {
        val plan = HomePageEditor.remove(3, 1, 2, 2,
            listOf(w("photo", 2, 0, 0, 1, 1)), listOf(HomePageEditor.Icon("app", 2, 1)))!!
        assertEquals(2, plan.pageCount)
        assertEquals(1, plan.widgets["photo"]!!.page)
        assertEquals("app", plan.order.last())
    }

    @Test fun populatedPageMovesWidgetsAndIconsWithoutOverlap() {
        val plan = HomePageEditor.remove(2, 1, 2, 2,
            listOf(w("photo", 1, 0, 0, 1, 1)),
            listOf(HomePageEditor.Icon("existing", 0, 1), HomePageEditor.Icon("folder", 1, 1)))!!
        assertEquals(0, plan.widgets["photo"]!!.page)
        assertEquals(setOf("existing", "folder"), plan.order.filter { it != HomeGridOrder.EMPTY }.toSet())
    }

    @Test fun insufficientRoomDoesNotProduceAPlan() {
        val plan = HomePageEditor.remove(2, 1, 2, 2,
            listOf(w("large", 0, 0, 0, 2, 2)), listOf(HomePageEditor.Icon("app", 1, 0)))
        assertNull(plan)
    }

    @Test fun hiddenWidgetsMoveWithoutTakingSpaceAndLastPageCannotBeRemoved() {
        val hidden = w("hidden", 1, 0, 0, 2, 2, false)
        val plan = HomePageEditor.remove(2, 1, 2, 2, listOf(hidden), listOf(HomePageEditor.Icon("app", 0, 0)))!!
        assertEquals(0, plan.widgets["hidden"]!!.page)
        assertEquals(listOf("app"), plan.order)
        assertNull(HomePageEditor.remove(1, 0, 2, 2, emptyList(), emptyList()))
    }
}
