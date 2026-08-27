package com.lispace.wzryncauto

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lispace.wzryncauto.device.BrightnessController
import com.lispace.wzryncauto.device.BrightnessPreference
import com.lispace.wzryncauto.device.BrightnessLeaseStore
import com.lispace.wzryncauto.device.RootCommandExecutor
import com.lispace.wzryncauto.device.RootScreenshotProvider
import com.lispace.wzryncauto.device.RunBrightnessMode
import com.lispace.wzryncauto.permission.LaunchPermissionPolicy
import com.lispace.wzryncauto.permission.LaunchPermissionStep
import com.lispace.wzryncauto.permission.PermissionSnapshot
import com.lispace.wzryncauto.schedule.RuntimeStateStore
import com.lispace.wzryncauto.service.OverlayService
import com.lispace.wzryncauto.ui.WzryHomeScreen
import com.lispace.wzryncauto.ui.theme.WzryFarmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var overlayGranted by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(false)
    private var exactAlarmGranted by mutableStateOf(false)
    private var batteryOptimizationIgnored by mutableStateOf(false)
    private var backgroundRestricted by mutableStateOf(false)
    private var miuiAutoStartGranted by mutableStateOf(false)
    private var rootStatus by mutableStateOf("尚未检测 ROOT")
    private var deviceStatus by mutableStateOf("尚未执行设备自检")
    private var alarmStatus by mutableStateOf("尚未检查定时唤醒权限")
    private var nextRunAtEpochMs by mutableStateOf<Long?>(null)
    private var brightnessMode by mutableStateOf(RunBrightnessMode.KEEP)
    private val rootExecutor = RootCommandExecutor()
    private val attemptedLaunchPermissions = mutableSetOf<LaunchPermissionStep>()
    private var permissionRequestInFlight = false
    private var pendingOpenOverlay = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionStatus()
        if (pendingOpenOverlay) {
            pendingOpenOverlay = false
            requestScreenCapture()
        } else {
            continueLaunchPermissionFlow()
        }
    }
    private val permissionSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionRequestInFlight = false
        refreshPermissionStatus()
        continueLaunchPermissionFlow()
    }
    private val screenCapturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        startOverlayService(
            resultCode = result.resultCode,
            projectionData = result.data,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionStatus()
        brightnessMode = BrightnessPreference.load(this)
        if (BrightnessLeaseStore(this).load() != null) restoreBrightness()
        setContent {
            WzryFarmTheme {
                WzryHomeScreen(
                    overlayGranted = overlayGranted,
                    notificationGranted = notificationGranted,
                    exactAlarmGranted = exactAlarmGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    backgroundRestricted = backgroundRestricted,
                    miuiAutoStartGranted = miuiAutoStartGranted,
                    rootStatus = rootStatus,
                    deviceStatus = deviceStatus,
                    alarmStatus = alarmStatus,
                    nextRunAtEpochMs = nextRunAtEpochMs,
                    brightnessMode = brightnessMode,
                    onBrightnessModeChanged = {
                        brightnessMode = it
                        BrightnessPreference.save(this, it)
                    },
                    onTestRoot = ::testRoot,
                    onTestDevice = ::testDevice,
                    onRestoreBrightness = ::restoreBrightness,
                    onRequestExactAlarm = ::requestExactAlarm,
                    onRequestPermissions = ::restartLaunchPermissionFlow,
                    onOpenOverlay = ::openOverlay,
                )
            }
        }
        window.decorView.post(::continueLaunchPermissionFlow)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        rootExecutor.cancelCurrent()
        super.onDestroy()
    }

    private fun testRoot() {
        runRootTest()
    }

    private fun runRootTest(onComplete: () -> Unit = {}) {
        rootStatus = "正在请求 Magisk ROOT…"
        lifecycleScope.launch {
            rootStatus = runCatching {
                val result = rootExecutor.executeText("id", timeoutMs = 10_000)
                when {
                    result.isRoot -> {
                        if (isMiuiDevice()) {
                            val autoStart = rootExecutor.executeText(
                                "appops get $packageName $MIUI_OP_AUTO_START",
                                timeoutMs = 10_000,
                            )
                            miuiAutoStartGranted =
                                autoStart.stdout.contains(": allow")
                        }
                        "ROOT 可用：uid=0(root)"
                    }
                    result.timedOut -> "ROOT 检测超时，请检查 Magisk 弹窗"
                    else -> "ROOT 不可用：${result.stderr.ifBlank { result.stdout }.trim()}"
                }
            }.getOrElse { "ROOT 检测失败：${it.message}" }
            onComplete()
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
            launchPermissionSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationGranted
        ) {
            pendingOpenOverlay = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCapturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun restoreBrightness() {
        deviceStatus = "正在恢复上次运行前亮度…"
        lifecycleScope.launch {
            val store = BrightnessLeaseStore(this@MainActivity)
            val lease = store.load()
            if (lease == null) {
                deviceStatus = "没有待恢复的亮度记录"
                return@launch
            }
            deviceStatus = runCatching {
                val errors = BrightnessController(rootExecutor).restoreVerified(lease.snapshot)
                if (errors.isEmpty()) {
                    store.clear()
                    "亮度已恢复并验证"
                } else {
                    "亮度恢复未完成：${errors.joinToString()}"
                }
            }.getOrElse { "亮度恢复失败：${it.message}" }
        }
    }

    private fun refreshPermissionStatus() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }
        alarmStatus = if (exactAlarmGranted) {
            "定时唤醒：精确模式可用"
        } else {
            "定时唤醒：未授权精确闹钟"
        }

        val powerManager = getSystemService(PowerManager::class.java)
        batteryOptimizationIgnored = powerManager
            .isIgnoringBatteryOptimizations(packageName)
        backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            getSystemService(ActivityManager::class.java).isBackgroundRestricted
        miuiAutoStartGranted = !isMiuiDevice() || isMiuiAutoStartAllowed()
        nextRunAtEpochMs = RuntimeStateStore(this).load().nextRunAtEpochMs
    }

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            refreshPermissionStatus()
            return
        }
        launchPermissionSettings(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun restartLaunchPermissionFlow() {
        attemptedLaunchPermissions.clear()
        permissionRequestInFlight = false
        refreshPermissionStatus()
        continueLaunchPermissionFlow()
    }

    private fun continueLaunchPermissionFlow() {
        if (permissionRequestInFlight || isFinishing || isDestroyed) return
        refreshPermissionStatus()
        val step = LaunchPermissionPolicy.nextStep(
            permissionSnapshot(),
            attemptedLaunchPermissions,
        ) ?: return
        attemptedLaunchPermissions += step

        when (step) {
            LaunchPermissionStep.NOTIFICATION -> {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LaunchPermissionStep.OVERLAY -> launchPermissionSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            LaunchPermissionStep.EXACT_ALARM -> requestExactAlarm()
            LaunchPermissionStep.BATTERY_OPTIMIZATION -> {
                requestBatteryOptimizationExemption()
            }
            LaunchPermissionStep.MIUI_AUTO_START -> openMiuiAutoStartSettings()
            LaunchPermissionStep.ROOT -> runRootTest(::continueLaunchPermissionFlow)
        }
    }

    private fun permissionSnapshot() = PermissionSnapshot(
        notificationGranted = notificationGranted,
        overlayGranted = overlayGranted,
        exactAlarmGranted = exactAlarmGranted,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        miuiDevice = isMiuiDevice(),
        miuiAutoStartGranted = miuiAutoStartGranted,
    )

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        if (intent.resolveActivity(packageManager) != null) {
            launchPermissionSettings(intent)
        } else {
            launchPermissionSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun openMiuiAutoStartSettings() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                ),
            ).putExtra("extra_pkgname", packageName),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
        val intent = candidates.firstOrNull {
            it.resolveActivity(packageManager) != null
        }
        if (intent == null) {
            Toast.makeText(this, "无法打开自启动设置，请手动进入系统应用管理", Toast.LENGTH_LONG)
                .show()
            continueLaunchPermissionFlow()
            return
        }
        Toast.makeText(
            this,
            "请允许“王者农场助手”自启动和后台运行",
            Toast.LENGTH_LONG,
        ).show()
        launchPermissionSettings(intent)
    }

    private fun launchPermissionSettings(intent: Intent) {
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        runCatching { permissionSettings.launch(intent) }
            .onFailure {
                permissionRequestInFlight = false
                Toast.makeText(this, "无法打开系统授权页面：${it.message}", Toast.LENGTH_LONG)
                    .show()
                continueLaunchPermissionFlow()
            }
    }

    private fun isMiuiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("POCO", ignoreCase = true)

    @Suppress("DEPRECATION")
    private fun isMiuiAutoStartAllowed(): Boolean = runCatching {
        val method = AppOpsManager::class.java.getDeclaredMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(
            getSystemService(AppOpsManager::class.java),
            MIUI_OP_AUTO_START,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun startOverlayService(resultCode: Int, projectionData: Intent?) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).apply {
                if (resultCode == Activity.RESULT_OK && projectionData != null) {
                    action = OverlayService.ACTION_ENABLE_FRAME_STREAM
                    putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_PROJECTION_DATA, projectionData)
                }
            },
        )
        moveTaskToBack(true)
    }

    private companion object {
        const val MIUI_OP_AUTO_START = 10008
    }
}

