package com.purenote.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.purenote.local.core.TodoDates
import com.purenote.local.data.RepeatRule
import java.util.Calendar

fun repeatLabel(rule: RepeatRule): String = when (rule) {
    RepeatRule.NONE -> "不重复"
    RepeatRule.DAILY -> "每天"
    RepeatRule.WEEKLY -> "每周"
    RepeatRule.WEEKDAYS -> "周一至周五"
    RepeatRule.WORKDAYS -> "工作日"
    RepeatRule.MONTHLY -> "每月"
    RepeatRule.YEARLY -> "每年"
}

/** 提醒时间对话框：整天开关 + 日期/时间 + 重复规则（对应小米 RemindTimePickerDialog） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemindPickerDialog(
    initialDue: Long?,
    initialAllDay: Boolean,
    initialRepeat: RepeatRule,
    onApply: (Long?, Boolean, RepeatRule) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val base = initialDue ?: TodoDates.dayRemindFromToday(1)

    var pickedDate by remember { mutableStateOf(TodoDates.startOfDay(base)) }
    var minutes by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                timeInMillis = base
            }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) },
        )
    }
    var allDay by remember { mutableStateOf(initialAllDay) }
    var repeat by remember { mutableStateOf(initialRepeat) }
    var repeatPickOpen by remember { mutableStateOf(false) }
    var datePickOpen by remember { mutableStateOf(false) }
    var timePickOpen by remember { mutableStateOf(false) }

    fun buildDue(): Long {
        if (allDay) return TodoDates.endOfDay(pickedDate)
        return Calendar.getInstance().apply {
            timeInMillis = pickedDate
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val dateText = remember(pickedDate) {
        val c = Calendar.getInstance().apply { timeInMillis = pickedDate }
        "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }
    val timeText = "%02d:%02d".format(minutes / 60, minutes % 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒时间") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("整天", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = allDay,
                        onCheckedChange = { allDay = it },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { datePickOpen = true }
                        .padding(vertical = 12.dp),
                ) {
                    Text("日期", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                if (!allDay) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { timePickOpen = true }
                            .padding(vertical = 12.dp),
                    ) {
                        Text("时间", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(timeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { repeatPickOpen = true }
                        .padding(vertical = 12.dp),
                ) {
                    Text("重复", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(repeatLabel(repeat), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(buildDue(), allDay, repeat) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (initialDue != null) {
                    TextButton(onClick = onClear) { Text("清除提醒", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    if (datePickOpen) {
        val dateState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = pickedDate,
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { datePickOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        // DatePicker 返回 UTC 零点，取 y/m/d 字段重建本地零点，避免时区偏移一天。
                        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = millis
                        }
                        pickedDate = TodoDates.startOfDay(
                            Calendar.getInstance().apply {
                                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
                            }.timeInMillis,
                        )
                    }
                    datePickOpen = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { datePickOpen = false }) { Text("取消") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = dateState)
        }
    }

    if (timePickOpen) {
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { timePickOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    minutes = timeState.hour * 60 + timeState.minute
                    timePickOpen = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { timePickOpen = false }) { Text("取消") }
            },
            text = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TimePicker(state = timeState)
                }
            },
        )
    }

    if (repeatPickOpen) {
        AlertDialog(
            onDismissRequest = { repeatPickOpen = false },
            title = { Text("重复提醒") },
            text = {
                Column {
                    listOf(
                        RepeatRule.NONE to "不重复",
                        RepeatRule.DAILY to "每天",
                        RepeatRule.WEEKLY to "每周",
                        RepeatRule.WEEKDAYS to "周一至周五",
                        RepeatRule.WORKDAYS to "工作日",
                        RepeatRule.MONTHLY to "每月",
                        RepeatRule.YEARLY to "每年",
                    ).forEach { (rule, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repeat = rule
                                    repeatPickOpen = false
                                }
                                .padding(vertical = 7.dp),
                        ) {
                            RadioButton(
                                selected = repeat == rule,
                                onClick = {
                                    repeat = rule
                                    repeatPickOpen = false
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { repeatPickOpen = false }) { Text("取消") }
            },
        )
    }
}
