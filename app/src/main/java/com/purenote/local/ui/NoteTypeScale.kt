package com.purenote.local.ui

import com.purenote.local.NoteTextSize

/**
 * “文字大小”只缩放用户写下的内容，不放大设置页、导航栏等应用框架。
 * 三档数值集中在这里，避免编辑器与首页卡片各自维护一套不一致的字号。
 */
internal data class NoteTypeScale(
    val editorTitleSp: Float,
    val editorTitleLineHeightSp: Float,
    val editorBodySp: Float,
    val editorBodyLineHeightSp: Float,
    val checklistSp: Float,
    val checklistLineHeightSp: Float,
    val cardTitleSp: Float,
    val cardTitleLineHeightSp: Float,
    val cardBodySp: Float,
    val cardBodyLineHeightSp: Float,
)

internal fun NoteTextSize.typeScale(): NoteTypeScale = when (this) {
    NoteTextSize.SMALL -> NoteTypeScale(
        editorTitleSp = 28f,
        editorTitleLineHeightSp = 34f,
        editorBodySp = 16f,
        editorBodyLineHeightSp = 24f,
        checklistSp = 15.5f,
        checklistLineHeightSp = 22f,
        cardTitleSp = 15.5f,
        cardTitleLineHeightSp = 20f,
        cardBodySp = 13.5f,
        cardBodyLineHeightSp = 20f,
    )

    NoteTextSize.DEFAULT -> NoteTypeScale(
        editorTitleSp = 31f,
        editorTitleLineHeightSp = 38f,
        editorBodySp = 18f,
        editorBodyLineHeightSp = 28f,
        checklistSp = 17f,
        checklistLineHeightSp = 24f,
        cardTitleSp = 17f,
        cardTitleLineHeightSp = 22f,
        cardBodySp = 15f,
        cardBodyLineHeightSp = 22f,
    )

    NoteTextSize.LARGE -> NoteTypeScale(
        editorTitleSp = 35f,
        editorTitleLineHeightSp = 43f,
        editorBodySp = 21f,
        editorBodyLineHeightSp = 32f,
        checklistSp = 20f,
        checklistLineHeightSp = 29f,
        cardTitleSp = 19f,
        cardTitleLineHeightSp = 25f,
        cardBodySp = 17.5f,
        cardBodyLineHeightSp = 26f,
    )
}
