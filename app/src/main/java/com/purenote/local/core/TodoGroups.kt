package com.purenote.local.core

import com.purenote.local.data.Todo

/** 与小米待办 getScheduleType 一致的六分组 */
enum class TodoGroup { EXPIRED, TODAY, TOMORROW, FUTURE, UNSCHEDULED, DONE }

object TodoGrouper {

    /**
     * 已完成 → 已过期(到期≤now，滚动判定) → 今天 → 明天 → 更远 → 未计划。
     * 只对顶层待办分组；子任务跟随父待办展示。
     */
    fun groupOf(todo: Todo, now: Long, tomorrowStart: Long, dayAfterStart: Long): TodoGroup = when {
        todo.done -> TodoGroup.DONE
        todo.dueAt == null -> TodoGroup.UNSCHEDULED
        todo.dueAt <= now -> TodoGroup.EXPIRED
        todo.dueAt < tomorrowStart -> TodoGroup.TODAY
        todo.dueAt < dayAfterStart -> TodoGroup.TOMORROW
        else -> TodoGroup.FUTURE
    }

    fun group(todos: List<Todo>, now: Long = System.currentTimeMillis()): LinkedHashMap<TodoGroup, List<Todo>> {
        val tomorrowStart = TodoDates.startOfDay(now, 1)
        val dayAfterStart = TodoDates.startOfDay(now, 2)

        val map = linkedMapOf<TodoGroup, MutableList<Todo>>()
        TodoGroup.entries.forEach { map[it] = mutableListOf() }
        todos.forEach { t ->
            if (t.parentId == null) {
                map.getValue(groupOf(t, now, tomorrowStart, dayAfterStart)).add(t)
            }
        }

        // 各组排序与小米一致：过期/今天/明天按到期升序；更远按天；未计划按创建倒序；已完成按完成时间倒序
        map.getValue(TodoGroup.EXPIRED).sortBy { it.dueAt ?: Long.MAX_VALUE }
        map.getValue(TodoGroup.TODAY).sortWith(
            compareBy<Todo> { it.allDay }.thenBy { it.dueAt ?: Long.MAX_VALUE },
        )
        map.getValue(TodoGroup.TOMORROW).sortWith(
            compareBy<Todo> { it.allDay }.thenBy { it.dueAt ?: Long.MAX_VALUE },
        )
        map.getValue(TodoGroup.FUTURE).sortBy { it.dueAt ?: Long.MAX_VALUE }
        map.getValue(TodoGroup.UNSCHEDULED).sortByDescending { it.createdAt }
        map.getValue(TodoGroup.DONE).sortByDescending { it.doneAt ?: it.updatedAt }

        return LinkedHashMap(map.mapValues { it.value.toList() })
    }

    fun subsOf(parentId: Long, all: List<Todo>): List<Todo> =
        all.filter { it.parentId == parentId }
}
