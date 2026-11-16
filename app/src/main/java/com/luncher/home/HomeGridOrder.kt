package com.luncher.home

/** The saved sequence covers free grid cells; widgets reserve the other cells. */
object HomeGridOrder {
    const val EMPTY = "__luncher_empty_cell__"

    fun normalize(saved: List<String>, available: List<String>): List<String> {
        val allowed = available.toSet()
        val used = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (key in saved) {
            if (key == EMPTY) result += EMPTY
            else if (key in allowed && used.add(key)) result += key
        }
        result += available.filter { used.add(it) }
        return trim(result)
    }

    fun moveToEmpty(order: List<String>, key: String, target: Int): List<String> {
        if (target < 0 || key == EMPTY) return order
        val result = order.toMutableList()
        val old = result.indexOf(key)
        if (old >= 0) result[old] = EMPTY
        while (result.size <= target) result += EMPTY
        // Do not overwrite an app if the displayed layout changed during the drag.
        if (result[target] != EMPTY) return order
        result[target] = key
        return trim(result)
    }

    fun moveAround(order: List<String>, key: String, target: String, after: Boolean): List<String> {
        if (key == target || target == EMPTY) return order
        val result = order.toMutableList()
        result.remove(key)
        val index = result.indexOf(target)
        if (index < 0) return order
        result.add(index + if (after) 1 else 0, key)
        return trim(result)
    }

    private fun trim(order: MutableList<String>): List<String> {
        while (order.lastOrNull() == EMPTY) order.removeAt(order.lastIndex)
        return order
    }
}
