package com.lispace.wzryncauto.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AccountBox
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lispace.wzryncauto.BuildConfig
import com.lispace.wzryncauto.R
import com.lispace.wzryncauto.device.RunBrightnessMode
import com.lispace.wzryncauto.ui.theme.WzryFarmTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FrostBackground = Color(0xFFF7FCFF)
private val IcePanel = Color(0xFFFDFEFF)
private val IceTint = Color(0xFFEAF7FC)
private val IceBlue = Color(0xFF2F8FDB)
private val IceCyan = Color(0xFF7DDCFF)
private val IceNavy = Color(0xFF173B57)

@Composable
fun WzryHomeScreen(
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    exactAlarmGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    backgroundRestricted: Boolean,
    miuiAutoStartGranted: Boolean,
    rootStatus: String,
    deviceStatus: String,
    alarmStatus: String,
    nextRunAtEpochMs: Long?,
    brightnessMode: RunBrightnessMode,
    onBrightnessModeChanged: (RunBrightnessMode) -> Unit,
    onTestRoot: () -> Unit,
    onTestDevice: () -> Unit,
    onRestoreBrightness: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    val rootGranted = rootStatus.contains("可用") || rootStatus.contains("通过")
    val backgroundReady = batteryOptimizationIgnored && !backgroundRestricted
    val canStart = overlayGranted && notificationGranted && exactAlarmGranted &&
        backgroundReady && miuiAutoStartGranted && rootGranted
    val wake = rememberWakePresentation(nextRunAtEpochMs)
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FrostBackground),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppHeader(canStart = canStart)
            HeroPanel(
                canStart = canStart,
                onClick = if (canStart) onOpenOverlay else onRequestPermissions,
            )
            NextWakePanel(wake)
            RunConditionsPanel(
                items = listOf(
                    ConditionItem("通知", Icons.Rounded.Notifications, notificationGranted),
                    ConditionItem("悬浮窗", Icons.Rounded.AccountBox, overlayGranted),
                    ConditionItem("ROOT", Icons.Rounded.Build, rootGranted),
                    ConditionItem("定时唤醒", Icons.Rounded.DateRange, exactAlarmGranted),
                    ConditionItem("后台运行", Icons.Rounded.Refresh, backgroundReady),
                    ConditionItem("自启动", Icons.AutoMirrored.Rounded.ExitToApp, miuiAutoStartGranted),
                ),
            )
            BrightnessPanel(
                selected = brightnessMode,
                onSelected = onBrightnessModeChanged,
            )
            DiagnosticsPanel(
                expanded = diagnosticsExpanded,
                onExpandedChange = { diagnosticsExpanded = it },
                rootStatus = rootStatus,
                deviceStatus = deviceStatus,
                alarmStatus = alarmStatus,
                onRequestPermissions = onRequestPermissions,
                onTestRoot = onTestRoot,
                onTestDevice = onTestDevice,
                onRequestExactAlarm = onRequestExactAlarm,
                onRestoreBrightness = onRestoreBrightness,
            )
            Text(
                text = "自动化运行期间请勿操作设备，以免影响识别和点击位置。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AppHeader(canStart: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ice_crystal_emblem),
                contentDescription = null,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "王者农场助手",
                    style = MaterialTheme.typography.titleLarge,
                    color = IceNavy,
                    maxLines = 1,
                )
                Text(
                    text = "WzryNCAuto  ·  v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            ReadyPill(canStart)
        }
    }
}

@Composable
private fun ReadyPill(ready: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Build,
                contentDescription = null,
                tint = if (ready) IceBlue else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (ready) "设备就绪" else "需要授权",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroPanel(canStart: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (canStart) "自动务农已就绪" else "完成授权后即可运行",
                style = MaterialTheme.typography.headlineMedium,
                color = IceNavy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (canStart) "所有基础能力已就绪" else "按顺序完成运行所需权限",
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .padding(vertical = 18.dp)
                    .size(172.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { if (canStart) 0.88f else 0.56f },
                    modifier = Modifier.fillMaxSize(),
                    color = IceBlue,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                    strokeWidth = 10.dp,
                )
                Image(
                    painter = painterResource(R.drawable.ice_crystal_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(132.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IceBlue),
                shape = RoundedCornerShape(18.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
            ) {
                Icon(
                    imageVector = if (canStart) Icons.Rounded.PlayArrow else Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = if (canStart) "开始自动务农" else "继续完成运行授权",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun NextWakePanel(wake: WakePresentation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box {
            Image(
                painter = painterResource(R.drawable.ice_facet_texture),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.24f),
                alignment = Alignment.CenterEnd,
                contentScale = ContentScale.Crop,
            )
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(17.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DateRange,
                        contentDescription = null,
                        tint = IceBlue,
                        modifier = Modifier
                            .padding(12.dp)
                            .size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "下一轮唤醒",
                        style = MaterialTheme.typography.titleMedium,
                        color = IceNavy,
                    )
                    Text(
                        text = wake.countdown,
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = wake.time,
                    style = MaterialTheme.typography.headlineMedium,
                    color = IceBlue,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RunConditionsPanel(items: List<ConditionItem>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(27.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = IceBlue,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("运行条件", style = MaterialTheme.typography.titleMedium, color = IceNavy)
            }
            Spacer(Modifier.height(12.dp))
            items.chunked(2).forEachIndexed { index, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { item ->
                        ConditionCell(
                            item = item,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (index < items.chunked(2).lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ConditionCell(item: ConditionItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        color = if (item.ready) IceTint else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        border = BorderStroke(
            1.dp,
            if (item.ready) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.ready) IceBlue else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = IceNavy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (item.ready) Icons.Rounded.CheckCircle else Icons.Rounded.Build,
                contentDescription = if (item.ready) "就绪" else "待处理",
                tint = if (item.ready) IceBlue else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun BrightnessPanel(
    selected: RunBrightnessMode,
    onSelected: (RunBrightnessMode) -> Unit,
) {
    val options = listOf(
        RunBrightnessMode.ROOT_LOW to "极低",
        RunBrightnessMode.SYSTEM_LOW to "低",
        RunBrightnessMode.KEEP to "保持",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = IceBlue,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text("屏幕亮度", style = MaterialTheme.typography.titleMedium, color = IceNavy)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IceTint)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { (mode, label) ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelected(mode) },
                        shape = RoundedCornerShape(13.dp),
                        color = if (selected == mode) IceBlue else Color.Transparent,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selected == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    rootStatus: String,
    deviceStatus: String,
    alarmStatus: String,
    onRequestPermissions: () -> Unit,
    onTestRoot: () -> Unit,
    onTestDevice: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRestoreBrightness: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = IcePanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Build, contentDescription = null, tint = IceBlue)
                Spacer(Modifier.width(8.dp))
                Text("高级诊断", modifier = Modifier.weight(1f), color = IceNavy)
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                val actions = listOf(
                    "检查并申请全部运行权限" to onRequestPermissions,
                    "测试 ROOT" to onTestRoot,
                    "设备自检" to onTestDevice,
                    "设置定时唤醒权限" to onRequestExactAlarm,
                    "恢复上次亮度" to onRestoreBrightness,
                )
                actions.forEach { (label, action) ->
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text(label)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(rootStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alarmStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    deviceStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberWakePresentation(nextRunAtEpochMs: Long?): WakePresentation {
    var nowEpochMs by remember(nextRunAtEpochMs) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(nextRunAtEpochMs) {
        while (true) {
            delay(1_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    if (nextRunAtEpochMs == null) {
        return WakePresentation(time = "--:--", countdown = "尚未安排下一轮")
    }
    val time = Instant.ofEpochMilli(nextRunAtEpochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    val remainingSeconds = ((nextRunAtEpochMs - nowEpochMs) / 1_000).coerceAtLeast(0)
    val countdown = if (remainingSeconds == 0L) {
        "等待系统唤醒"
    } else {
        val hours = remainingSeconds / 3_600
        val minutes = remainingSeconds % 3_600 / 60
        val seconds = remainingSeconds % 60
        "%02d:%02d:%02d 后".format(hours, minutes, seconds)
    }
    return WakePresentation(time = time, countdown = countdown)
}

private data class ConditionItem(
    val label: String,
    val icon: ImageVector,
    val ready: Boolean,
)

private data class WakePresentation(
    val time: String,
    val countdown: String,
)

@Preview(showBackground = true, widthDp = 420, heightDp = 880)
@Composable
private fun WzryHomeScreenPreview() {
    WzryFarmTheme {
        WzryHomeScreen(
            overlayGranted = true,
            notificationGranted = true,
            exactAlarmGranted = true,
            batteryOptimizationIgnored = true,
            backgroundRestricted = false,
            miuiAutoStartGranted = true,
            rootStatus = "ROOT 可用：uid=0(root)",
            deviceStatus = "设备自检通过",
            alarmStatus = "定时唤醒：精确模式可用",
            nextRunAtEpochMs = System.currentTimeMillis() + 5_318_000,
            brightnessMode = RunBrightnessMode.KEEP,
            onBrightnessModeChanged = {},
            onTestRoot = {},
            onTestDevice = {},
            onRestoreBrightness = {},
            onRequestExactAlarm = {},
            onRequestPermissions = {},
            onOpenOverlay = {},
        )
    }
}
