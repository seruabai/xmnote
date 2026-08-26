package com.purenote.local.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.purenote.local.NoteViewModel
import com.purenote.local.ThemeMode
import com.purenote.local.notify.QuickCaptureService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: NoteViewModel) {
    val mode by vm.themeMode.collectAsState()
    val context = LocalContext.current
    var quickOn by remember {
        mutableStateOf(QuickCaptureService.running && Settings.canDrawOverlays(context))
    }

    BackHandler { vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                title = {
                    Text("设置", style = MaterialTheme.typography.titleMedium)
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader("速记")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("屏幕边缘速记把手", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "开启后右缘显示窄把手，拖动调位、点按展开面板，可快速存笔记或待办。需要“显示在其他应用上层”权限。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = quickOn,
                        onCheckedChange = { want ->
                            if (want) {
                                if (Settings.canDrawOverlays(context)) {
                                    QuickCaptureService.start(context)
                                    quickOn = true
                                } else {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + context.packageName),
                                        ),
                                    )
                                }
                            } else {
                                QuickCaptureService.stop(context)
                                quickOn = false
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "部分系统会清理后台；若把手消失，回到这里重新打开即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("外观")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeSegment("跟随系统", ThemeMode.SYSTEM, mode, 0, 3) { vm.setThemeMode(it) }
                        ThemeSegment("浅色", ThemeMode.LIGHT, mode, 1, 3) { vm.setThemeMode(it) }
                        ThemeSegment("深色", ThemeMode.DARK, mode, 2, 3) { vm.setThemeMode(it) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("关于")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("纯记", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "版本 1.2.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这是一款纯本地笔记应用：不申请网络权限，没有云空间与账号同步，所有内容只保存在本机数据库中；" +
                            "可在任意 Android 7.0 及以上设备安装，不限手机品牌。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SingleChoiceSegmentedButtonRowScope.ThemeSegment(
    label: String,
    value: ThemeMode,
    current: ThemeMode,
    index: Int,
    count: Int,
    onPick: (ThemeMode) -> Unit,
) {
    SegmentedButton(
        selected = current == value,
        onClick = { onPick(value) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
    ) {
        Text(label)
    }
}
