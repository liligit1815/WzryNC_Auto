package com.lili.wzryfarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lili.wzryfarm.device.BrightnessController
import com.lili.wzryfarm.device.BrightnessPreference
import com.lili.wzryfarm.device.RootCommandExecutor
import com.lili.wzryfarm.device.RootScreenshotProvider
import com.lili.wzryfarm.device.RunBrightnessMode
import com.lili.wzryfarm.service.OverlayService
import com.lili.wzryfarm.ui.theme.WzryFarmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var overlayGranted by mutableStateOf(false)
    private var rootStatus by mutableStateOf("尚未检测 ROOT")
    private var deviceStatus by mutableStateOf("尚未执行设备自检")
    private var brightnessMode by mutableStateOf(RunBrightnessMode.KEEP)
    private val rootExecutor = RootCommandExecutor()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayGranted = Settings.canDrawOverlays(this)
        brightnessMode = BrightnessPreference.load(this)
        setContent {
            WzryFarmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProjectReadyScreen(
                        overlayGranted = overlayGranted,
                        rootStatus = rootStatus,
                        deviceStatus = deviceStatus,
                        brightnessMode = brightnessMode,
                        onBrightnessModeChanged = {
                            brightnessMode = it
                            BrightnessPreference.save(this, it)
                        },
                        onTestRoot = ::testRoot,
                        onTestDevice = ::testDevice,
                        onOpenOverlay = ::openOverlay,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    override fun onDestroy() {
        rootExecutor.cancelCurrent()
        super.onDestroy()
    }

    private fun testRoot() {
        rootStatus = "正在请求 Magisk ROOT…"
        lifecycleScope.launch {
            rootStatus = runCatching {
                val result = rootExecutor.executeText("id", timeoutMs = 10_000)
                when {
                    result.isRoot -> "ROOT 可用：uid=0(root)"
                    result.timedOut -> "ROOT 检测超时，请检查 Magisk 弹窗"
                    else -> "ROOT 不可用：${result.stderr.ifBlank { result.stdout }.trim()}"
                }
            }.getOrElse { "ROOT 检测失败：${it.message}" }
        }
    }

    private fun testDevice() {
        deviceStatus = "正在执行截图和亮度只读自检…"
        lifecycleScope.launch {
            deviceStatus = runCatching {
                val root = rootExecutor.executeText("id", timeoutMs = 10_000)
                if (!root.isRoot) {
                    return@runCatching "设备自检失败：ROOT 不可用"
                }
                val screenshot = RootScreenshotProvider(rootExecutor).capture()
                if (!screenshot.isSuccess) {
                    return@runCatching "设备自检失败：截图 ${screenshot.stderr}"
                }
                val brightness = BrightnessController(rootExecutor)
                    .captureSnapshot()
                    .getOrThrow()
                "设备自检通过：PNG ${screenshot.stdout.size / 1024}KB，" +
                    "亮度 ${brightness.systemBrightness}，" +
                    "背光节点 ${brightness.backlightValues.size}"
            }.getOrElse { "设备自检失败：${it.message}" }
        }
    }

    private fun openOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java),
        )
        moveTaskToBack(true)
    }
}

@Composable
private fun ProjectReadyScreen(
    overlayGranted: Boolean,
    rootStatus: String,
    deviceStatus: String,
    brightnessMode: RunBrightnessMode,
    onBrightnessModeChanged: (RunBrightnessMode) -> Unit,
    onTestRoot: () -> Unit,
    onTestDevice: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "王者农场助手",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = "重要：程序运行期间请勿操作设备，以免自动点击位置错乱。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = "程序运行后的亮度设置",
            style = MaterialTheme.typography.titleMedium,
        )
        RunBrightnessMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = brightnessMode == mode,
                    onClick = { onBrightnessModeChanged(mode) },
                )
                Text(mode.label)
            }
        }
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = if (overlayGranted) {
                "悬浮窗权限已授予"
            } else {
                "需要授予悬浮窗权限"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            text = rootStatus,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onTestRoot) {
            Text("测试 ROOT")
        }
        Text(
            modifier = Modifier.padding(vertical = 8.dp),
            text = deviceStatus,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onTestDevice) {
            Text("设备自检")
        }
        Button(onClick = onOpenOverlay) {
            Text(if (overlayGranted) "打开悬浮窗" else "授予悬浮窗权限")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectReadyPreview() {
    WzryFarmTheme {
        ProjectReadyScreen(
            overlayGranted = true,
            rootStatus = "ROOT 可用：uid=0(root)",
            deviceStatus = "设备自检通过",
            brightnessMode = RunBrightnessMode.KEEP,
            onBrightnessModeChanged = {},
            onTestRoot = {},
            onTestDevice = {},
            onOpenOverlay = {},
        )
    }
}
