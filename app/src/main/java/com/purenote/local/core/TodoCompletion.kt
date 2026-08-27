package com.purenote.local.core

/** 待办清单父子完成状态的唯一推导规则。 */
object TodoCompletion {
    /** 无子项时返回 null，表示父项保持自身状态；否则仅在没有未完成子项时返回 true。 */
    fun parentDone(childCount: Int, incompleteCount: Int): Boolean? =
        if (childCount <= 0) null else incompleteCount <= 0
}
