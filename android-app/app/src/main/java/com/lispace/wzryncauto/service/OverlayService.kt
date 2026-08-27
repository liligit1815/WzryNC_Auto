package com.lispace.wzryncauto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lispace.wzryncauto.MainActivity
import com.lispace.wzryncauto.R
import com.lispace.wzryncauto.automation.AutomationControl
import com.lispace.wzryncauto.automation.AutomationState
import com.lispace.wzryncauto.automation.DeviceLockPolicy
import com.lispace.wzryncauto.automation.EnterFarmAutomation
import com.lispace.wzryncauto.automation.FarmActionAutomation
import com.lispace.wzryncauto.automation.LockedDeviceAction
import com.lispace.wzryncauto.automation.RootAutomationRuntime
import com.lispace.wzryncauto.automation.PopupCloseTemplateMatcher
import com.lispace.wzryncauto.device.RootCommandExecutor
import com.lispace.wzryncauto.device.MediaProjectionFrameSource
import com.lispace.wzryncauto.device.RootDeviceController
import com.lispace.wzryncauto.device.RootScreenshotProvider
import com.lispace.wzryncauto.device.SafeScreenshotCapture
import com.lispace.wzryncauto.device.BrightnessController
import com.lispace.wzryncauto.device.BrightnessPreference
import com.lispace.wzryncauto.device.BrightnessSnapshot
import com.lispace.wzryncauto.device.BrightnessLeaseStore
import com.lispace.wzryncauto.device.RunBrightnessMode
import com.lispace.wzryncauto.device.SaveBrightnessLeaseResult
import com.lispace.wzryncauto.device.ScreenWakePreference
import com.lispace.wzryncauto.ocr.MaturityOcrEngine
import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.OcrSampleStore
import com.lispace.wzryncauto.schedule.FarmSchedule
import com.lispace.wzryncauto.schedule.FarmScheduleCalculator
import com.lispace.wzryncauto.schedule.FarmStateStore
import com.lispace.wzryncauto.schedule.WakeReason
import com.lispace.wzryncauto.schedule.AutomationAlarmScheduler
import com.lispace.wzryncauto.schedule.RuntimeCheckpoint
import com.lispace.wzryncauto.schedule.RuntimePhase
import com.lispace.wzryncauto.schedule.RuntimeStateStore
import com.lispace.wzryncauto.schedule.PersistentOneClickActionGuard
import com.lispace.wzryncauto.schedule.ScheduledWakeLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal fun prepareUiForScreenshot(
    cancelToast: () -> Unit,
    hideOverlay: () -> Unit,
) {
    cancelToast()
    hideOverlay()
}

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rootExecutor = RootCommandExecutor()
    private val frameSource by lazy { MediaProjectionFrameSource(applicationContext) }
    private val screenshotCapture by lazy {
        SafeScreenshotCapture(
            provider = RootScreenshotProvider(rootExecutor),
            cacheDirectory = cacheDir,
            hideOverlay = ::hideAppUiForScreenshot,
            restoreOverlay = { setOverlayVisibility(View.VISIBLE) },
            streamFrame = { frameSource.latestFrame() },
        )
    }
    private val deviceController by lazy { RootDeviceController(rootExecutor) }
    private val automationRuntime by lazy {
        RootAutomationRuntime(
            deviceController,
            screenshotCapture,
            ocrEngine,
            popupCloseTemplateMatcher = PopupCloseTemplateMatcher(assets),
            hideOverlay = { setOverlayVisibility(View.INVISIBLE) },
            restoreOverlay = { setOverlayVisibility(View.VISIBLE) },
        )
    }
    private val ocrEngineDelegate = lazy { MaturityOcrEngine() }
    private val ocrEngine by ocrEngineDelegate
    private val ocrSampleStore by lazy {
        OcrSampleStore(File(filesDir, "ocr_samples"))
    }
    private val farmStateStore by lazy { FarmStateStore(applicationContext) }
    private val brightnessController by lazy { BrightnessController(rootExecutor) }
    private val brightnessLeaseStore by lazy { BrightnessLeaseStore(applicationContext) }
    private val runtimeStateStore by lazy { RuntimeStateStore(applicationContext) }
    private val alarmScheduler by lazy { AutomationAlarmScheduler(applicationContext) }
    private val scheduledWakeLatch = ScheduledWakeLatch()
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var simulationJob: Job? = null
    private var inProcessWakeJob: Job? = null
    private var brightnessRecoveryJob: Job? = null
    private var currentToast: Toast? = null
    private var brightnessSnapshot: BrightnessSnapshot? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val keyLogLines = ArrayDeque<KeyLogLine>()
    private var currentLogRound = 0
    private var totalExperience = 0
    private val totalCrops = linkedMapOf<String, Int>()

    private var loopCount = 5
    private var isInfinite = false
    private var keepScreenAwake = true
    private var completedRounds = 0
    private var isRunning = false
    private var isPaused = false
    private var remainingSeconds = 0L
    private var nextWakeAt: LocalDateTime? = null
    private var currentOperation = "等待开始"
    private var activeTaskId: String? = null
    private var stopRequested = false

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var countdownText: TextView
    private lateinit var loopText: TextView
    private lateinit var pauseButton: Button
    private lateinit var logText: TextView
    private lateinit var harvestSummaryText: TextView
    private lateinit var operationView: View
    private lateinit var logView: View
    private lateinit var operationTabButton: Button
    private lateinit var logTabButton: Button

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startBaseForeground("悬浮窗已就绪")
        keepScreenAwake = ScreenWakePreference.load(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadPersistedLogs()
        if (Settings.canDrawOverlays(this)) {
            showBubble()
        } else {
            stopSelf()
        }
        if (brightnessLeaseStore.hasUnresolvedLease()) {
            currentOperation = "恢复上次未完成的亮度设置"
            brightnessRecoveryJob = serviceScope.launch {
                runCatching { restoreRunBrightness() }
                    .onFailure { appendLog("启动恢复亮度失败：${it.message}") }
                brightnessRecoveryJob = null
                refreshUi()
            }
        }
        restoreRuntimeCheckpoint()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> shutdown()
            ACTION_EMERGENCY_STOP -> stopSimulation("紧急停止")
            ACTION_TEST_SCREENSHOT -> runDiagnosticIfIdle(::testScreenshot)
            ACTION_TEST_OCR -> runDiagnosticIfIdle(::testMaturityOcr)
            ACTION_TEST_HARVEST_OCR -> runDiagnosticIfIdle(::testHarvestOcr)
            ACTION_START_AUTOMATION -> startSimulation()
            ACTION_RUN_DUE -> resumeScheduledRound(
                intent.getLongExtra(EXTRA_RUNTIME_GENERATION, Long.MIN_VALUE),
            )
            ACTION_ENABLE_FRAME_STREAM -> enableFrameStream(intent)
        }
        return START_STICKY
    }

    private fun runDiagnosticIfIdle(action: () -> Unit) {
        if (simulationJob?.isActive == true || isRunning) {
            appendLog("自动化任务执行或等待期间不运行诊断，避免画面识别互相干扰")
            showOperationToast("请先停止自动化任务再运行诊断")
            return
        }
        action()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.post {
            val view = bubble ?: return@post
            val params = bubbleParams ?: return@post
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            params.x = params.x.coerceIn(
                -view.width / 2,
                (screenWidth - view.width / 2).coerceAtLeast(0),
            )
            params.y = params.y.coerceIn(dp(8), (screenHeight - view.height - dp(8)).coerceAtLeast(dp(8)))
            windowManager.updateViewLayout(view, params)
            if (panel != null) {
                hidePanel()
                showPanel()
            }
        }
    }

    override fun onDestroy() {
        simulationJob?.cancel()
        removeView(panel)
        removeView(bubble)
        panel = null
        bubble = null
        cancelCurrentToast()
        serviceScope.cancel()
        if (ocrEngineDelegate.isInitialized()) ocrEngine.close()
        frameSource.close()
        releaseScreenWakeLock()
        super.onDestroy()
    }

    private fun enableFrameStream(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (resultCode == 0 || projectionData == null) {
            appendLog("屏幕流未获授权，继续使用 ROOT 截图")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("屏幕流识别已启用"),
                    types,
                )
            }
            frameSource.start(resultCode, projectionData)
        }.onSuccess {
            appendLog("屏幕流识别已启用，异常时自动回退 ROOT 截图")
        }.onFailure {
            appendLog("屏幕流启动失败：${it.message}；继续使用 ROOT 截图")
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(54)
        val view = TextView(this).apply {
            text = "🌱"
            textSize = 26f
            gravity = Gravity.CENTER
            elevation = dp(8).toFloat()
            background = circleDrawable(COLOR_IDLE)
        }
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = resources.displayMetrics.heightPixels / 3
        }
        attachDragAndClick(view, params)
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    private fun attachDragAndClick(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    moved = moved || abs(dx) > dp(6) || abs(dy) > dp(6)
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapBubbleToEdge(view, params)
                    } else {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubbleToEdge(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        val screenWidth = resources.displayMetrics.widthPixels
        params.x = if (params.x + view.width / 2 < screenWidth / 2) {
            -view.width / 2
        } else {
            screenWidth - view.width / 2
        }
        params.y = params.y.coerceIn(dp(8), resources.displayMetrics.heightPixels - view.height - dp(8))
        windowManager.updateViewLayout(view, params)
    }

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val displayMetrics = resources.displayMetrics
        val panelWidth = dp(460).coerceAtMost(displayMetrics.widthPixels - dp(24))
        val desiredPanelHeight = (displayMetrics.heightPixels * 0.80f).toInt()
        val panelHeight = desiredPanelHeight.coerceAtMost(
            (displayMetrics.heightPixels - dp(24)).coerceAtLeast(dp(180)),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedDrawable(Color.argb(238, 24, 28, 31), dp(16).toFloat())
            elevation = dp(12).toFloat()
        }

        content.addView(horizontalRow().apply {
            addView(label("WzryNCAuto", 18f), weighted())
            addView(smallButton("收起") { hidePanel() })
            addView(smallButton("×") { shutdown() })
        })

        content.addView(horizontalRow().apply {
            operationTabButton = actionButton("操作") { showPanelPage(showLog = false) }
            logTabButton = actionButton("日志") { showPanelPage(showLog = true) }
            addView(operationTabButton, weighted())
            addView(logTabButton, weighted())
        })

        val operationContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = label("状态：等待开始", 15f)
        progressText = label("进度：0 / $loopCount", 14f)
        countdownText = label("下次执行：--", 14f)
        operationContent.addView(statusText)
        operationContent.addView(progressText)
        operationContent.addView(countdownText)

        operationContent.addView(horizontalRow().apply {
            addView(label("循环次数", 14f), weighted())
            addView(smallButton("－") { changeLoopCount(-1) })
            loopText = label(loopCount.toString(), 16f).apply {
                gravity = Gravity.CENTER
                minWidth = dp(44)
            }
            addView(loopText)
            addView(smallButton("＋") { changeLoopCount(1) })
            addView(CheckBox(this@OverlayService).apply {
                text = "无限"
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, checked ->
                    if (!isRunning) {
                        isInfinite = checked
                        loopText.text = if (checked) "∞" else loopCount.toString()
                    } else {
                        isChecked = isInfinite
                        appendLog("运行期间不能修改循环模式")
                        showOperationToast("运行期间不能修改循环模式")
                    }
                }
            })
        })

        val keepScreenAwakeCheckBox = CheckBox(this@OverlayService).apply {
            text = "运行期间保持屏幕常亮（推荐多轮/无限）"
            setTextColor(Color.WHITE)
            isChecked = keepScreenAwake
            setOnCheckedChangeListener { _, checked ->
                if (!isRunning) {
                    keepScreenAwake = checked
                    ScreenWakePreference.save(this@OverlayService, checked)
                    appendLog(if (checked) "已开启运行期间保持屏幕常亮" else "已关闭运行期间保持屏幕常亮")
                    refreshUi()
                } else {
                    isChecked = keepScreenAwake
                    appendLog("运行期间不能修改屏幕常亮设置")
                    showOperationToast("运行期间不能修改屏幕常亮设置")
                }
            }
        }
        operationContent.addView(keepScreenAwakeCheckBox)

        operationContent.addView(horizontalRow().apply {
            addView(actionButton("开始") { startSimulation() }, weighted())
            pauseButton = actionButton("暂停") { togglePause() }
            addView(pauseButton, weighted())
            addView(actionButton("停止") { stopSimulation("用户停止") }, weighted())
        })
        content.addView(operationContent)
        operationView = operationContent

        val logContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        harvestSummaryText = label(renderHarvestSummary(), 14f).apply {
            setTextColor(Color.rgb(255, 224, 130))
            background = roundedDrawable(Color.argb(180, 46, 56, 48), dp(8).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        logContent.addView(harvestSummaryText)
        logContent.addView(label("轮次摘要", 14f))
        logText = label(renderKeyLogs(), 12f).apply {
            setTextColor(Color.rgb(190, 230, 195))
        }
        logContent.addView(ScrollView(this).apply {
            addView(logText)
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (panelHeight - dp(150)).coerceAtLeast(dp(180)),
        ))
        content.addView(logContent)
        logView = logContent
        showPanelPage(showLog = false)

        val scrollContainer = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val params = overlayParams(panelWidth, panelHeight).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = dp(12)
            y = 0
        }
        windowManager.addView(scrollContainer, params)
        panel = scrollContainer
        refreshUi()
        showOperationToast("助手面板已展开，可上下滑动")
    }

    private fun hidePanel() {
        removeView(panel)
        panel = null
    }

    private fun showPanelPage(showLog: Boolean) {
        if (!::operationView.isInitialized || !::logView.isInitialized) return
        operationView.visibility = if (showLog) View.GONE else View.VISIBLE
        logView.visibility = if (showLog) View.VISIBLE else View.GONE
        operationTabButton.isEnabled = showLog
        logTabButton.isEnabled = !showLog
        panel?.post {
            (panel as? ScrollView)?.scrollTo(0, 0)
        }
    }

    private fun changeLoopCount(delta: Int) {
        if (isRunning) {
            appendLog("运行期间不能修改循环次数")
            showOperationToast("运行期间不能修改循环次数")
            return
        }
        loopCount = (loopCount + delta).coerceIn(1, 99)
        loopText.text = loopCount.toString()
        refreshUi()
        showOperationToast("循环次数：$loopCount")
    }

    private fun restoreRuntimeCheckpoint() {
        val checkpoint = runtimeStateStore.load()
        activeTaskId = checkpoint.taskId
        completedRounds = checkpoint.completedRounds
        loopCount = checkpoint.targetRounds.takeIf { it > 0 } ?: loopCount
        isInfinite = checkpoint.infinite
        when (checkpoint.phase) {
            RuntimePhase.WAITING_ALARM -> {
                val wakeEpoch = checkpoint.nextRunAtEpochMs ?: return
                if (keepScreenAwake) acquireScreenWakeLock()
                nextWakeAt = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(wakeEpoch),
                    ZoneId.systemDefault(),
                )
                isRunning = true
                currentOperation = "等待系统定时唤醒"
                runCatching { alarmScheduler.schedule(checkpoint) }
                    .onSuccess {
                        armInProcessWake(checkpoint)
                        updateBubbleColor(COLOR_WAITING)
                    }
                    .onFailure { error ->
                        checkpoint.taskId?.let { taskId ->
                            runtimeStateStore.markFailure(
                                taskId,
                                "恢复定时任务失败：${error.message}",
                                recoverable = true,
                            )
                        }
                        isRunning = false
                        currentOperation = "恢复定时任务失败：${error.message}"
                        appendLog(currentOperation)
                        updateBubbleColor(COLOR_ERROR)
                    }
                refreshUi()
            }
            RuntimePhase.RUNNING -> {
                checkpoint.taskId?.let {
                    runtimeStateStore.markFailure(
                        it,
                        "上次执行在进程退出前未完成，已禁止自动重复点击",
                        recoverable = true,
                    )
                }
                isRunning = false
                currentOperation = "上次执行中断，需要重新确认"
                updateBubbleColor(COLOR_ERROR)
            }
            RuntimePhase.PAUSED -> {
                if (keepScreenAwake) acquireScreenWakeLock()
                isRunning = true
                isPaused = true
                checkpoint.nextRunAtEpochMs?.let { epoch ->
                    nextWakeAt = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(epoch),
                        ZoneId.systemDefault(),
                    )
                }
                currentOperation = "已暂停"
                updateBubbleColor(COLOR_WAITING)
            }
            RuntimePhase.ERROR,
            RuntimePhase.RECOVERY_REQUIRED,
            -> {
                isRunning = false
                currentOperation = checkpoint.lastError ?: "上次任务需要处理"
                updateBubbleColor(COLOR_ERROR)
            }
            RuntimePhase.IDLE,
            RuntimePhase.COMPLETED,
            -> Unit
        }
    }

    private fun startSimulation() {
        if (brightnessRecoveryJob?.isActive == true) {
            appendLog("正在恢复上次亮度，请稍后再开始")
            showOperationToast("正在恢复上次亮度，请稍后")
            return
        }
        if (brightnessLeaseStore.hasUnresolvedLease()) {
            currentOperation = "存在尚未恢复的亮度快照"
            appendLog("检测到尚未恢复的亮度快照，禁止开始新任务")
            showOperationToast("请先恢复亮度，再开始任务")
            updateBubbleColor(COLOR_ERROR)
            refreshUi()
            return
        }
        if (isRunning || simulationJob?.isActive == true) {
            appendLog("助手已有运行或等待中的任务")
            showOperationToast("已有任务，请先停止")
            return
        }
        DeviceLockPolicy.multiRoundBlockReason(
            infinite = isInfinite,
            targetRounds = loopCount,
            isKeyguardSecure = getSystemService(KeyguardManager::class.java).isKeyguardSecure,
            keepScreenAwake = keepScreenAwake,
        )?.let { reason ->
            currentOperation = reason
            appendLog(reason)
            showOperationToast(reason)
            updateBubbleColor(COLOR_ERROR)
            refreshUi()
            return
        }
        if ((isInfinite || loopCount > 1) && !alarmScheduler.canScheduleExact()) {
            currentOperation = "精确闹钟未授权，无法可靠执行多轮任务"
            appendLog("精确闹钟未授权；请先在主界面完成授权")
            showOperationToast("请先授权精确闹钟")
            updateBubbleColor(COLOR_ERROR)
            refreshUi()
            return
        }
        val existing = runtimeStateStore.load()
        if (existing.hasPendingTask || existing.phase == RuntimePhase.RECOVERY_REQUIRED) {
            currentOperation = existing.lastError ?: "存在未处理的上次任务"
            appendLog("检测到未处理任务，禁止覆盖：$currentOperation")
            showOperationToast("请先停止并清理上次任务")
            updateBubbleColor(COLOR_ERROR)
            refreshUi()
            return
        }

        if (keepScreenAwake) acquireScreenWakeLock()

        isPaused = false
        stopRequested = false
        scheduledWakeLatch.clear()
        cancelInProcessWake()
        completedRounds = 0
        currentLogRound = 0
        keyLogLines.clear()
        totalExperience = 0
        totalCrops.clear()
        remainingSeconds = 0L
        nextWakeAt = null
        currentOperation = "准备执行完整农场动作"
        val checkpoint = runtimeStateStore.beginTask(isInfinite, loopCount)
        activeTaskId = checkpoint.taskId
        appendLog("开始手机端定时务农：单轮执行 → 本地清理 → 系统唤醒")
        hidePanel()
        launchRound(checkpoint)
    }

    private fun resumeScheduledRound(generation: Long) {
        if (generation == Long.MIN_VALUE || stopRequested) return
        val checkpoint = runtimeStateStore.load()
        if (checkpoint.phase != RuntimePhase.WAITING_ALARM ||
            checkpoint.generation != generation
        ) {
            appendLog("忽略过期或重复的定时唤醒：$generation")
            return
        }
        cancelInProcessWake()
        if (simulationJob?.isActive == true) {
            scheduledWakeLatch.defer(generation)
            appendLog("定时唤醒已到达，等待本轮收尾后立即继续")
            return
        }
        val now = System.currentTimeMillis()
        val triggerAt = checkpoint.nextRunAtEpochMs ?: return
        if (triggerAt > now + ALARM_EARLY_TOLERANCE_MS) {
            runCatching { alarmScheduler.schedule(checkpoint) }
                .onSuccess {
                    armInProcessWake(checkpoint)
                    appendLog("定时广播提前到达，已重新登记")
                }
                .onFailure { error ->
                    checkpoint.taskId?.let { taskId ->
                        runtimeStateStore.markFailure(
                            taskId,
                            "重新登记定时任务失败：${error.message}",
                            recoverable = true,
                        )
                    }
                    isRunning = false
                    currentOperation = "重新登记定时任务失败：${error.message}"
                    appendLog(currentOperation)
                    updateBubbleColor(COLOR_ERROR)
                    refreshUi()
                }
            return
        }
        activeTaskId = checkpoint.taskId
        completedRounds = checkpoint.completedRounds
        loopCount = checkpoint.targetRounds.takeIf { it > 0 } ?: loopCount
        isInfinite = checkpoint.infinite
        stopRequested = false
        if (keepScreenAwake) acquireScreenWakeLock()
        appendLog("系统定时唤醒，第 ${completedRounds + 1} 轮准备开始")
        launchRound(checkpoint)
    }

    private fun launchRound(checkpoint: RuntimeCheckpoint) {
        val taskId = checkpoint.taskId ?: return
        isRunning = true
        isPaused = false
        nextWakeAt = null
        remainingSeconds = 0L
        currentOperation = "执行第 ${checkpoint.completedRounds + 1} 轮"
        updateBubbleColor(COLOR_RUNNING)
        refreshUi()
        simulationJob = serviceScope.launch {
            val control = object : AutomationControl {
                override suspend fun awaitRunnable() {
                    while (isPaused) delay(250)
                }
            }
            try {
                when (val outcome = executeSingleRound(checkpoint, control)) {
                    is RoundOutcome.Waiting -> {
                        nextWakeAt = outcome.wakeAt
                        remainingSeconds = Duration.between(
                            LocalDateTime.now(),
                            outcome.wakeAt,
                        ).seconds.coerceAtLeast(0)
                        currentOperation = if (outcome.exact) {
                            "等待系统精确定时唤醒"
                        } else {
                            "等待系统低精度定时唤醒"
                        }
                        isRunning = true
                        armInProcessWake(
                            generation = outcome.generation,
                            triggerAtEpochMs = outcome.triggerAtEpochMs,
                        )
                        updateBubbleColor(COLOR_WAITING)
                        showOperationToast("本轮完成，等待下次执行")
                    }
                    is RoundOutcome.Completed -> {
                        releaseScreenWakeLock()
                        isRunning = false
                        nextWakeAt = null
                        remainingSeconds = 0L
                        currentOperation = outcome.reason
                        updateBubbleColor(COLOR_IDLE)
                        showOperationToast(outcome.reason)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (!stopRequested) throw cancelled
            } catch (error: Throwable) {
                if (!stopRequested) {
                    val failureReason = error.message?.takeIf(String::isNotBlank) ?: "未知错误"
                    val failedOperation = currentOperation
                    val failedRound = checkpoint.completedRounds + 1
                    isPaused = false
                    currentOperation = "执行失败：$failureReason"
                    appendLog("自动化失败：$failureReason")
                    saveFailureDiagnostic(
                        error = error,
                        round = failedRound,
                        failedOperation = failedOperation,
                    )
                    scheduleFailureRetry(
                        taskId = taskId,
                        failedRound = failedRound,
                        failureReason = failureReason,
                    )
                }
            } finally {
                simulationJob = null
                refreshUi()
                scheduledWakeLatch.take()?.let { deferredGeneration ->
                    appendLog("本轮收尾完成，处理已到达的定时唤醒")
                    resumeScheduledRound(deferredGeneration)
                }
            }
        }
    }

    private fun scheduleFailureRetry(
        taskId: String,
        failedRound: Int,
        failureReason: String,
    ) {
        scheduledWakeLatch.clear()
        cancelInProcessWake()
        alarmScheduler.cancel()

        val failuresBeforeCurrent = runtimeStateStore.load().consecutiveFailures
        if (!FailureRetryPolicy.shouldRetry(failuresBeforeCurrent)) {
            val failed = runtimeStateStore.markFailure(
                expectedTaskId = taskId,
                reason = failureReason,
                recoverable = false,
            )
            releaseScreenWakeLock()
            isRunning = false
            nextWakeAt = null
            remainingSeconds = 0L
            currentOperation =
                "连续失败 ${failed.consecutiveFailures} 次，已停止自动重试：$failureReason"
            appendLog(currentOperation)
            appendLog("请处理游戏阻塞页面后手动重新开始任务")
            showOperationToast("连续失败，任务已停止，请检查游戏页面")
            updateBubbleColor(COLOR_ERROR)
            return
        }

        val nowEpochMs = System.currentTimeMillis()
        val retryAtEpochMs = nowEpochMs + FAILURE_RETRY_DELAY_MS
        val waiting = runCatching {
            runtimeStateStore.scheduleFailureRetry(
                expectedTaskId = taskId,
                triggerAtEpochMs = retryAtEpochMs,
                reason = failureReason,
                nowEpochMs = nowEpochMs,
            )
        }.getOrElse { retryError ->
            runCatching {
                runtimeStateStore.markFailure(
                    taskId,
                    "$failureReason；安排重试失败：${retryError.message}",
                    recoverable = true,
                )
            }
            releaseScreenWakeLock()
            isRunning = false
            nextWakeAt = null
            remainingSeconds = 0L
            currentOperation = "执行失败，无法安排重试：${retryError.message}"
            appendLog(currentOperation)
            showOperationToast(currentOperation)
            updateBubbleColor(COLOR_ERROR)
            return
        }

        val retryAt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(retryAtEpochMs),
            ZoneId.systemDefault(),
        )
        val alarmFailure = runCatching { alarmScheduler.schedule(waiting) }.exceptionOrNull()
        armInProcessWake(
            generation = waiting.generation,
            triggerAtEpochMs = retryAtEpochMs,
        )
        isRunning = true
        nextWakeAt = retryAt
        remainingSeconds = FAILURE_RETRY_DELAY_MS / 1_000L
        currentOperation = "第 $failedRound 轮失败，1分钟后重试：$failureReason"
        appendLog("已停止本轮并关闭王者荣耀，1分钟后重试第 $failedRound 轮")
        if (alarmFailure == null) {
            appendLog("失败重试已登记系统精确定时唤醒")
        } else {
            appendLog("失败重试的系统闹钟登记失败：${alarmFailure.message}；继续使用应用内计时")
        }
        showOperationToast("本轮失败，1分钟后自动重试")
        updateBubbleColor(COLOR_WAITING)
    }

    private suspend fun executeSingleRound(
        checkpoint: RuntimeCheckpoint,
        control: AutomationControl,
    ): RoundOutcome {
        val taskId = requireNotNull(checkpoint.taskId)
        val round = checkpoint.completedRounds + 1
        runtimeStateStore.markRoundRunning(taskId, round)
        currentOperation = "执行第 $round 轮"
        appendLog("第 $round 轮开始")
        val roundStartedAt = LocalDateTime.now()
        if (!keepScreenAwake) acquireScreenWakeLock()

        var primaryFailure: Throwable? = null
        val result = try {
            ensureDeviceReadyForAutomation()
            prepareRunBrightness()
            EnterFarmAutomation(
                runtime = automationRuntime,
                control = control,
                onState = ::onAutomationState,
                onLog = ::appendLog,
            ).run()
            FarmActionAutomation(
                runtime = automationRuntime,
                control = control,
                onState = ::onAutomationState,
                onLog = ::appendLog,
                oneClickGuard = PersistentOneClickActionGuard(
                    runtimeStateStore,
                    taskId,
                    round,
                ),
            ).run()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val cleanupErrors = cleanupAfterRound()
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = IllegalStateException(
                    "本轮清理未完成：${cleanupErrors.joinToString("；")}",
                )
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }

        appendLog("本轮类型：${if (result.harvested) "收获" else "浇水"}")
        if (result.harvested) result.harvestInfo?.let(::recordHarvest)
        val secondsToOneClick = Duration.between(
            roundStartedAt,
            result.firstWaterAt,
        ).seconds.coerceAtLeast(1)
        val wakeLeadSeconds =
            (secondsToOneClick + SCHEDULE_SAFETY_MARGIN_SECONDS).coerceAtLeast(0)
        appendLog(
            "启动到一键务农：${secondsToOneClick}秒；" +
                "排程安全余量：${SCHEDULE_SAFETY_MARGIN_SECONDS}秒",
        )

        val nextPlan = buildNextRunPlan(result.farmlandState, result, wakeLeadSeconds)
        completedRounds = round
        runtimeStateStore.markRoundCompleted(taskId, completedRounds)
        refreshUi()

        if (nextPlan == null || (!isInfinite && completedRounds >= loopCount)) {
            runtimeStateStore.markCompleted(taskId)
            alarmScheduler.cancel()
            val reason = when {
                nextPlan == null && result.farmlandState is FarmlandState.Empty ->
                    "土地为空，本次任务安全结束"
                else -> "定时务农已完成，共执行 $completedRounds 轮"
            }
            appendLog(reason)
            return RoundOutcome.Completed(reason)
        }

        val nowEpoch = System.currentTimeMillis()
        val triggerEpoch = nextPlan.wakeAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
            .coerceAtLeast(nowEpoch)
        val waiting = runtimeStateStore.scheduleNext(
            expectedTaskId = taskId,
            triggerAtEpochMs = triggerEpoch,
            wakeReason = nextPlan.reason.name,
            nowEpochMs = nowEpoch,
        )
        val alarm = alarmScheduler.schedule(waiting)
        appendLog(
            if (alarm.exact) {
                "已登记系统精确定时唤醒"
            } else {
                "精确闹钟未授权，已登记低精度系统唤醒"
            },
        )
        return RoundOutcome.Waiting(
            wakeAt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(triggerEpoch),
                ZoneId.systemDefault(),
            ),
            exact = alarm.exact,
            generation = waiting.generation,
            triggerAtEpochMs = triggerEpoch,
        )
    }

    /**
     * AlarmManager remains the process-death/deep-sleep fallback. While this
     * foreground service is alive, also keep an in-process timer so an OEM
     * alarm restriction cannot silently strand a multi-round task.
     */
    private fun armInProcessWake(checkpoint: RuntimeCheckpoint) {
        val triggerAt = checkpoint.nextRunAtEpochMs ?: return
        armInProcessWake(checkpoint.generation, triggerAt)
    }

    private fun armInProcessWake(generation: Long, triggerAtEpochMs: Long) {
        cancelInProcessWake()
        inProcessWakeJob = serviceScope.launch {
            while (isActive) {
                val remainingMs = triggerAtEpochMs - System.currentTimeMillis()
                if (remainingMs <= 0L) break
                delay(remainingMs.coerceAtMost(IN_PROCESS_WAKE_RECHECK_MS))
            }
            inProcessWakeJob = null
            appendLog("应用内计时到点，触发第 ${completedRounds + 1} 轮")
            resumeScheduledRound(generation)
        }
    }

    private fun cancelInProcessWake() {
        inProcessWakeJob?.cancel()
        inProcessWakeJob = null
    }

    private suspend fun buildNextRunPlan(
        farmland: FarmlandState,
        result: com.lispace.wzryncauto.automation.FarmActionResult,
        wakeLeadSeconds: Long,
    ): NextRunPlan? = when (farmland) {
        is FarmlandState.Planted -> {
            val observedMaturity = FarmScheduleCalculator.resolveObservedMaturity(
                farmland.maturity,
                result.firstWaterAt,
            )
            val stored = if (result.harvested) null else compatibleStoredFarmState(observedMaturity)
            val schedule = FarmScheduleCalculator.calculate(
                firstWaterAt = result.firstWaterAt,
                observedMaturityAt = observedMaturity,
                now = LocalDateTime.now(),
                storedCycleMinutes = stored?.cycleMinutes,
                batchStartedAt = stored?.batchStartedAt,
                wakeLeadSeconds = wakeLeadSeconds,
            )
            farmStateStore.save(
                cycleMinutes = schedule.cycleMinutes,
                batchStartedAt = schedule.batchStartedAt,
                observedMaturityAt = schedule.observedMaturityAt,
            )
            logSchedule(schedule, result.harvested)
            appendLog(
                "预计一键务农：" +
                    schedule.wakeAt.plusSeconds(wakeLeadSeconds)
                        .format(DATE_TIME_FORMAT),
            )
            NextRunPlan(schedule.wakeAt, schedule.reason)
        }
        is FarmlandState.Empty -> {
            appendLog("已确认到达土地，但当前土地为空，不创建错误排程")
            farmStateStore.clear()
            null
        }
        is FarmlandState.Mature -> throw IllegalStateException(
            "一键务农后土地仍显示已成熟，已停止自动重启以避免启停循环",
        )
        is FarmlandState.Unknown -> throw IllegalStateException(
            "土地状态无法确认：${farmland.reason}",
        )
    }

    private suspend fun compatibleStoredFarmState(observedMaturity: LocalDateTime) =
        farmStateStore.load()?.takeIf { stored ->
            val storedMaturity = stored.observedMaturityAt ?: return@takeIf false
            val ageHours = kotlin.math.abs(
                Duration.between(stored.updatedAt, LocalDateTime.now()).toHours(),
            )
            val maturityDifferenceMinutes = kotlin.math.abs(
                Duration.between(storedMaturity, observedMaturity).toMinutes(),
            )
            (ageHours <= FARM_STATE_MAX_AGE_HOURS &&
                maturityDifferenceMinutes <= FARM_STATE_MATURITY_TOLERANCE_MINUTES).also {
                if (!it) appendLog("旧作物批次与本轮成熟时间不一致，已忽略旧周期")
            }
        }

    private suspend fun cleanupAfterRound(): List<String> = withContext(NonCancellable) {
        val errors = mutableListOf<String>()
        runCatching { automationRuntime.stopGame() }
            .onFailure { errors += "停止游戏失败：${it.message}" }
        runCatching { restoreRunBrightness() }
            .onFailure { errors += "恢复亮度异常：${it.message}" }
        if (brightnessLeaseStore.hasUnresolvedLease()) {
            errors += "亮度恢复尚未验证"
        }
        runCatching { setOverlayVisibility(View.VISIBLE) }
            .onFailure { errors += "恢复悬浮窗失败：${it.message}" }
        if (!keepScreenAwake) releaseScreenWakeLock()
        errors
    }

    private fun logSchedule(schedule: FarmSchedule, freshBatch: Boolean) {
        val reason = if (schedule.reason == WakeReason.WATERING) "浇水" else "成熟"
        appendLog(
            if (freshBatch) {
                "检测到收获，记录新的 ${schedule.cycleMinutes} 分钟作物批次"
            } else {
                "作物周期：${schedule.cycleMinutes} 分钟"
            },
        )
        appendLog("成熟时间：${schedule.observedMaturityAt.format(DATE_TIME_FORMAT)}")
        appendLog("下个目标：${schedule.targetAt.format(DATE_TIME_FORMAT)}（$reason）")
        appendLog("下次操作时间：${schedule.wakeAt.format(DATE_TIME_FORMAT)}")
    }

    private fun togglePause() {
        if (!isRunning) {
            showOperationToast("当前没有运行中的任务")
            return
        }
        if (simulationJob == null && nextWakeAt != null) {
            val taskId = activeTaskId ?: return
            if (!isPaused) {
                alarmScheduler.cancel()
                cancelInProcessWake()
                runtimeStateStore.markPaused(taskId)
                isPaused = true
                currentOperation = "等待任务已暂停"
                appendLog("已暂停并取消系统定时唤醒")
            } else {
                if (!alarmScheduler.canScheduleExact()) {
                    currentOperation = "精确闹钟未授权，无法恢复多轮任务"
                    appendLog(currentOperation)
                    showOperationToast("请先授权精确闹钟")
                    updateBubbleColor(COLOR_ERROR)
                    refreshUi()
                    return
                }
                val paused = runtimeStateStore.load()
                val nowEpoch = System.currentTimeMillis()
                val originalEpoch = paused.nextRunAtEpochMs ?: nowEpoch
                val waiting = runtimeStateStore.scheduleNext(
                    expectedTaskId = taskId,
                    triggerAtEpochMs = originalEpoch.coerceAtLeast(nowEpoch),
                    wakeReason = paused.wakeReason ?: WakeReason.MATURITY.name,
                    nowEpochMs = nowEpoch,
                )
                val registration = alarmScheduler.schedule(waiting)
                armInProcessWake(waiting)
                nextWakeAt = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(waiting.nextRunAtEpochMs!!),
                    ZoneId.systemDefault(),
                )
                isPaused = false
                currentOperation = if (registration.exact) {
                    "等待系统精确定时唤醒"
                } else {
                    "等待系统低精度定时唤醒"
                }
                appendLog("已恢复系统定时唤醒")
            }
            showOperationToast(if (isPaused) "自动化已暂停" else "自动化继续运行")
            updateBubbleColor(COLOR_WAITING)
            refreshUi()
            return
        }
        isPaused = !isPaused
        appendLog(if (isPaused) "已暂停" else "继续运行")
        showOperationToast(if (isPaused) "自动化已暂停" else "自动化继续运行")
        updateBubbleColor(if (isPaused) COLOR_WAITING else COLOR_RUNNING)
        refreshUi()
    }

    private fun stopSimulation(reason: String, stopServiceWhenDone: Boolean = false) {
        if (stopRequested) return
        stopRequested = true
        scheduledWakeLatch.clear()
        cancelInProcessWake()
        alarmScheduler.cancel()
        val runningJob = simulationJob
        runningJob?.cancel()
        rootExecutor.cancelCurrent()
        currentOperation = "正在停止并清理"
        updateNotification(currentOperation)
        serviceScope.launch {
            runningJob?.join()
            val cleanupErrors = cleanupAfterRound()
            releaseScreenWakeLock()
            runtimeStateStore.clear()
            activeTaskId = null
            isRunning = false
            isPaused = false
            remainingSeconds = 0L
            nextWakeAt = null
            currentOperation = if (cleanupErrors.isEmpty()) {
                reason
            } else {
                "$reason，但清理未完全确认"
            }
            cleanupErrors.forEach { appendLog(it) }
            appendLog(currentOperation)
            showOperationToast(currentOperation)
            updateBubbleColor(
                if (reason == "紧急停止" || cleanupErrors.isNotEmpty()) {
                    COLOR_ERROR
                } else {
                    COLOR_IDLE
                },
            )
            refreshUi()
            updateNotification(currentOperation)
            if (stopServiceWhenDone) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun testRoot() {
        appendLog("正在请求 ROOT 权限…")
        showOperationToast("正在检查 ROOT 权限")
        serviceScope.launch {
            val message = runCatching {
                val result = rootExecutor.executeText("id", timeoutMs = 10_000)
                when {
                    result.isRoot ->
                        "ROOT 自检成功 (${result.durationMs}ms)"
                    result.timedOut ->
                        "ROOT 自检超时，请检查 Magisk 授权"
                    else ->
                        "ROOT 自检失败: " +
                            result.stderr.ifBlank { result.stdout }.trim()
                }
            }.getOrElse { "ROOT 自检异常: ${it.message}" }
            appendLog(message)
            showOperationToast(message)
            updateBubbleColor(
                if (message.startsWith("ROOT 自检成功")) {
                    COLOR_RUNNING
                } else {
                    COLOR_ERROR
                },
            )
        }
    }

    private fun readDeviceInfo() {
        appendLog("正在读取设备信息…")
        showOperationToast("正在读取设备信息")
        serviceScope.launch {
            val result = runCatching {
                rootExecutor.executeText(
                    "printf 'size='; wm size | tail -n 1; " +
                        "printf 'density='; wm density | tail -n 1; " +
                        "printf 'android='; getprop ro.build.version.release",
                    timeoutMs = 10_000,
                )
            }.getOrElse {
                appendLog("设备信息读取异常: ${it.message}")
                return@launch
            }
            if (result.isSuccess) {
                result.stdout.lineSequence()
                    .filter(String::isNotBlank)
                    .forEach(::appendLog)
                showOperationToast("设备信息读取完成")
            } else {
                appendLog("设备信息读取失败: ${result.stderr.trim()}")
                showOperationToast("设备信息读取失败")
            }
        }
    }

    private fun testScreenshot() {
        appendLog("隐藏悬浮窗并截取屏幕…")
        showOperationToast("正在截取屏幕")
        serviceScope.launch {
            val result = screenshotCapture.capture()
            result.onSuccess {
                appendLog(
                    "截图成功 ${it.width}×${it.height}，" +
                        "${it.byteCount / 1024}KB，尝试${it.attempts}次，${it.durationMs}ms",
                )
                showOperationToast("屏幕截图成功")
            }.onFailure {
                appendLog("截图失败: ${it.message}")
                showOperationToast("屏幕截图失败")
                updateBubbleColor(COLOR_ERROR)
            }
        }
    }

    private fun testMaturityOcr() {
        appendLog("截屏并识别土地成熟时间…")
        showOperationToast("正在识别作物成熟时间")
        serviceScope.launch {
            val capture = screenshotCapture.capture().getOrElse {
                appendLog("OCR 前截图失败: ${it.message}")
                return@launch
            }
            val screenshotBytes = capture.encoded
            val observation = withContext(Dispatchers.Default) {
                ocrEngine.recognize(screenshotBytes)
            }.getOrElse {
                appendLog("OCR 失败: ${it.message}")
                return@launch
            }
            val stored = runCatching {
                withContext(Dispatchers.IO) {
                    ocrSampleStore.saveMaturity(screenshotBytes, observation)
                }
            }.getOrElse {
                appendLog("OCR 样本保存失败: ${it.message}")
                return@launch
            }
            appendLog("OCR 样本已保存: ${stored.id}（累计 ${stored.sampleCount}/30）")
            appendLog("OCR 文本: ${observation.rawText.ifBlank { "<空>" }}")
            when (val reading = observation.reading) {
                is MaturityReading.Time -> {
                    appendLog("成熟时间: %02d:%02d".format(reading.hour, reading.minute))
                    showOperationToast(
                        "识别完成：%02d:%02d 成熟".format(reading.hour, reading.minute),
                    )
                }
                is MaturityReading.Mature -> {
                    appendLog("作物已成熟，可以收获")
                    showOperationToast("识别完成：作物已成熟")
                }
                is MaturityReading.Unrecognized -> {
                    appendLog("成熟时间未识别: ${reading.reason}")
                    showOperationToast("未识别到成熟时间")
                }
            }
        }
    }

    private fun testHarvestOcr() {
        appendLog("截屏并核验收获信息…")
        serviceScope.launch {
            val capture = screenshotCapture.capture().getOrElse {
                appendLog("收获 OCR 前截图失败: ${it.message}")
                return@launch
            }
            val screenshotBytes = capture.encoded
            val observation = withContext(Dispatchers.Default) {
                ocrEngine.recognizeHarvest(screenshotBytes)
            }.getOrElse {
                appendLog("收获 OCR 失败: ${it.message}")
                return@launch
            }
            val parsed = observation.parsed
            appendLog("收获 OCR 原文: ${observation.rawText.ifBlank { "<空>" }}")
            appendLog(
                "收获 OCR 结果: 经验=${parsed?.experience ?: 0}，作物=" +
                    (parsed?.crops?.entries?.joinToString("、") { (name, count) ->
                        "$name×$count"
                    }?.ifBlank { "无" } ?: "无"),
            )
        }
    }

    private fun hideAppUiForScreenshot() {
        prepareUiForScreenshot(
            cancelToast = ::cancelCurrentToast,
            hideOverlay = { setOverlayVisibility(View.INVISIBLE) },
        )
    }

    private fun cancelCurrentToast() {
        currentToast?.cancel()
        currentToast = null
    }

    private fun setOverlayVisibility(visibility: Int) {
        bubble?.visibility = visibility
        panel?.visibility = visibility
    }

    private fun refreshUi() {
        val status = when {
            isPaused -> "状态：已暂停"
            isRunning -> "状态：$currentOperation"
            else -> "状态：$currentOperation"
        }
        updateNotification(status)
        if (!::statusText.isInitialized) return
        statusText.text = status
        progressText.text = if (isInfinite) {
            "进度：$completedRounds / ∞"
        } else {
            "进度：$completedRounds / $loopCount"
        }
        countdownText.text = when {
            nextWakeAt != null -> {
                val wakeAt = checkNotNull(nextWakeAt)
                remainingSeconds = Duration.between(
                    LocalDateTime.now(),
                    wakeAt,
                ).seconds.coerceAtLeast(0)
                val hours = remainingSeconds / 3600
                val minutes = (remainingSeconds % 3600) / 60
                val seconds = remainingSeconds % 60
                "下次执行：${wakeAt.format(TIME_FORMAT)}（%02d:%02d:%02d）".format(
                    hours,
                    minutes,
                    seconds,
                )
            }
            isRunning -> "当前阶段：执行中"
            else -> "下次执行：--"
        }
        pauseButton.text = if (isPaused) "继续" else "暂停"
    }

    private fun appendLog(message: String) {
        Log.i(LOG_TAG, message)
        val occurredAt = LocalDateTime.now()
        persistLog("${occurredAt.format(PERSISTED_LOG_FORMAT)} $message")
        if (!isKeyLog(message)) return
        KeyLogDisplayFormatter.roundFrom(message)?.let {
            currentLogRound = it
        }
        keyLogLines.addLast(
            KeyLogLine(
                round = currentLogRound,
                time = occurredAt.format(TIME_FORMAT),
                message = message,
            ),
        )
        trimKeyLogs()
        if (::logText.isInitialized) logText.text = renderKeyLogs()
    }

    private fun recordHarvest(info: com.lispace.wzryncauto.ocr.HarvestInfo) {
        totalExperience += info.experience
        info.crops.forEach { (crop, count) ->
            totalCrops[crop] = (totalCrops[crop] ?: 0) + count
        }
        if (::harvestSummaryText.isInitialized) {
            harvestSummaryText.text = renderHarvestSummary()
        }
        val crops = info.crops.entries.joinToString("、") { (crop, count) -> "$crop×$count" }
            .ifBlank { "无作物" }
        appendLog("本轮收获：经验${info.experience}，$crops")
    }

    private fun renderHarvestSummary(): String {
        val crops = totalCrops.entries.joinToString("、") { (crop, count) -> "$crop：$count" }
            .ifBlank { "暂无" }
        return "累计收获\n经验：$totalExperience\n作物：$crops"
    }

    private fun saveFailureDiagnostic(
        error: Throwable,
        round: Int,
        failedOperation: String,
    ) {
        runCatching {
            val task = activeTaskId ?: "no-task"
            val directory = File(
                filesDir,
                "diagnostics/${LocalDateTime.now().format(DIAGNOSTIC_TIME_FORMAT)}_${task.take(8)}",
            ).apply { mkdirs() }
            val cachedScreen = File(cacheDir, "current.png").takeIf(File::isFile)
            cachedScreen?.copyTo(File(directory, "last_screen.png"), overwrite = true)
            val screenshotBounds = cachedScreen?.let { file ->
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                    BitmapFactory.decodeFile(file.absolutePath, options)
                }
            }
            automationRuntime.diagnosticOcrTrace().takeIf(String::isNotBlank)?.let { trace ->
                File(directory, "ocr_trace.txt").writeText(trace)
            }
            File(directory, "context.txt").writeText(
                buildString {
                    appendLine("taskId=$task")
                    appendLine("round=$round")
                    appendLine("operation=$failedOperation")
                    appendLine("status=$currentOperation")
                    appendLine(
                        "screenshotResolution=" +
                            if (
                                screenshotBounds != null &&
                                screenshotBounds.outWidth > 0 && screenshotBounds.outHeight > 0
                            ) {
                                "${screenshotBounds.outWidth}x${screenshotBounds.outHeight}"
                            } else {
                                "unavailable"
                            },
                    )
                    appendLine(
                        "appWindowResolution=${resources.displayMetrics.widthPixels}x" +
                            resources.displayMetrics.heightPixels,
                    )
                    appendLine("error=${error.message}")
                    error.suppressed.forEach { appendLine("suppressed=${it.message}") }
                    appendLine("recentLog:")
                    keyLogLines.takeLast(DIAGNOSTIC_RECENT_LOG_LINES).forEach { line ->
                        appendLine("${line.time} ${line.message}")
                    }
                    appendLine(error.stackTraceToString())
                },
            )
            appendLog("失败现场已保存：${directory.absolutePath}")
            trimDiagnosticDirectories(File(filesDir, "diagnostics"))
        }.onFailure {
            appendLog("保存失败现场异常：${it.message}")
        }
    }

    private fun trimDiagnosticDirectories(root: File) {
        root.listFiles()
            ?.filter(File::isDirectory)
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_DIAGNOSTIC_DIRECTORIES)
            ?.forEach { it.deleteRecursively() }
    }

    private fun persistLog(line: String) {
        runCatching {
            val logFile = File(filesDir, RUNTIME_LOG_FILE)
            if (logFile.length() > MAX_RUNTIME_LOG_BYTES) {
                val retained = logFile.readLines().takeLast(PERSISTED_LOG_LINES).joinToString("\n")
                logFile.writeText(if (retained.isBlank()) "" else "$retained\n")
            }
            FileWriter(logFile, true).use {
                it.appendLine(line)
            }
        }.onFailure {
            Log.e(LOG_TAG, "persist log failed", it)
        }
    }

    private fun loadPersistedLogs() {
        runCatching {
            var loadedRound = 0
            File(filesDir, RUNTIME_LOG_FILE)
                .takeIf(File::isFile)
                ?.readLines()
                ?.takeLast(PERSISTED_LOG_LINES)
                ?.forEach { line ->
                    val entry = KeyLogDisplayFormatter.parsePersisted(line)
                        ?: return@forEach
                    if (!isKeyLog(entry.message)) return@forEach
                    KeyLogDisplayFormatter.roundFrom(entry.message)?.let {
                        loadedRound = it
                    }
                    keyLogLines.addLast(
                        KeyLogLine(
                            round = loadedRound,
                            time = entry.time,
                            message = entry.message,
                        ),
                    )
                }
            currentLogRound = loadedRound
            trimKeyLogs()
        }.onFailure {
            Log.e(LOG_TAG, "load persisted log failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        screenWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "$packageName:automation-screen",
        ).apply {
            setReferenceCounted(false)
            // The lock is released explicitly when the task completes, fails,
            // or is stopped, so multi-round waits are not capped at ten minutes.
            acquire()
        }
        appendLog("运行期间屏幕常亮已启用")
    }

    private suspend fun ensureDeviceReadyForAutomation() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isInteractive) {
            val wake = deviceController.wakeScreen()
            check(wake.isSuccess) {
                "唤醒屏幕失败${wake.commandFailureDetail()}"
            }
            repeat(SCREEN_WAKE_ATTEMPTS) { attempt ->
                if (powerManager.isInteractive) return@repeat
                if (attempt < SCREEN_WAKE_ATTEMPTS - 1) {
                    delay(SCREEN_WAKE_INTERVAL_MS)
                }
            }
            check(powerManager.isInteractive) {
                "已发送唤醒命令，但屏幕仍未点亮"
            }
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        when (
            DeviceLockPolicy.forCurrentState(
                isKeyguardLocked = keyguard.isKeyguardLocked,
                isDeviceLocked = keyguard.isDeviceLocked,
                isKeyguardSecure = keyguard.isKeyguardSecure,
            )
        ) {
            LockedDeviceAction.READY -> return
            LockedDeviceAction.BLOCK_SECURE_KEYGUARD -> error(
                "设备已被密码锁定，无法安全自动解锁；请解锁后重新开始",
            )
            LockedDeviceAction.DISMISS_NON_SECURE_KEYGUARD -> {
                val dismiss = deviceController.dismissKeyguard()
                check(dismiss.isSuccess) {
                    "关闭非密码锁屏失败${dismiss.commandFailureDetail()}"
                }
                repeat(KEYGUARD_DISMISS_ATTEMPTS) { attempt ->
                    if (powerManager.isInteractive &&
                        !keyguard.isKeyguardLocked &&
                        !keyguard.isDeviceLocked
                    ) {
                        appendLog("已唤醒并关闭非密码锁屏")
                        return
                    }
                    if (attempt < KEYGUARD_DISMISS_ATTEMPTS - 1) {
                        delay(KEYGUARD_DISMISS_INTERVAL_MS)
                    }
                }
                error("非密码锁屏在唤醒后仍未关闭，已停止自动触控")
            }
        }
    }

    private fun com.lispace.wzryncauto.device.CommandResult.commandFailureDetail(): String {
        val detail = stderr.ifBlank { stdout }.trim()
        return detail.takeIf(String::isNotBlank)?.let { "：$it" } ?: ""
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        screenWakeLock = null
    }

    private fun trimKeyLogs() {
        val minimumRound = (currentLogRound - (VISIBLE_LOG_ROUNDS - 1)).coerceAtLeast(1)
        keyLogLines.removeAll { it.round in 1 until minimumRound }
        while (keyLogLines.size > MAX_VISIBLE_LOG_LINES) keyLogLines.removeFirst()
    }

    private fun renderKeyLogs(): String {
        if (keyLogLines.isEmpty()) return "等待首轮执行记录\n"
        return KeyLogDisplayFormatter.render(
            keyLogLines.map { line ->
                RoundLogEvent(line.round, line.time, line.message)
            },
        ).ifBlank { "等待首轮执行记录" } + "\n"
    }

    private suspend fun prepareRunBrightness() {
        val mode = BrightnessPreference.load(applicationContext)
        if (mode == RunBrightnessMode.KEEP) return
        val captured = brightnessController.captureSnapshot().getOrThrow()
        val saved = brightnessLeaseStore.saveBeforeApply(captured)
        brightnessSnapshot = saved.lease.snapshot
        check(saved is SaveBrightnessLeaseResult.Saved) {
            "检测到尚未恢复的亮度快照，已保留原始值并停止本轮"
        }
        val result = when (mode) {
            RunBrightnessMode.KEEP -> return
            RunBrightnessMode.SYSTEM_LOW -> brightnessController.setSystemLow()
            RunBrightnessMode.ROOT_LOW -> brightnessController.setRootLow()
        }
        check(result.isSuccess) {
            "设置运行亮度失败：${result.stderr.ifBlank { result.stdout }.trim()}"
        }
        brightnessLeaseStore.markApplied()
        appendLog("运行亮度：${mode.label}")
    }

    private suspend fun restoreRunBrightness() {
        val lease = brightnessLeaseStore.load()
        val snapshot = lease?.snapshot ?: brightnessSnapshot ?: return
        if (lease != null) brightnessLeaseStore.markRestoring()
        val errors = brightnessController.restoreVerified(snapshot)
        if (errors.isEmpty()) {
            if (lease != null) brightnessLeaseStore.clear()
            brightnessSnapshot = null
            appendLog("已恢复运行前亮度")
        } else {
            appendLog("恢复亮度失败：${errors.joinToString()}")
        }
    }

    private fun timestamp(): String = LocalTime.now().format(TIME_FORMAT)

    private fun isKeyLog(message: String): Boolean {
        return KeyLogDisplayFormatter.isVisible(message)
    }

    private fun onAutomationState(state: AutomationState, message: String) {
        currentOperation = message
        refreshUi()
        // Every state transition and every detailed operation callback uses
        // the same path, so the user always sees the exact current step rather
        // than only the broad phase name.
        showOperationToast(message)
        Log.i(LOG_TAG, "automation state=$state message=$message")
    }

    private fun showOperationToast(message: String) {
        cancelCurrentToast()
        currentToast = Toast.makeText(
            applicationContext,
            "WzryNCAuto\n$message",
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun shutdown() {
        stopSimulation("悬浮窗服务停止", stopServiceWhenDone = true)
    }

    private fun updateBubbleColor(color: Int) {
        bubble?.background = circleDrawable(color)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startBaseForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动化运行状态",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun overlayParams(width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun smallButton(value: String, onClick: () -> Unit) =
        Button(this).apply {
            text = value
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    private fun actionButton(value: String, onClick: () -> Unit) =
        Button(this).apply {
            text = value
            setOnClickListener { onClick() }
        }

    private fun weighted() = LinearLayout.LayoutParams(
        0,
        WindowManager.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
    }

    private fun roundedDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        setStroke(dp(1), Color.argb(160, 255, 255, 255))
    }

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { windowManager.removeView(view) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "wzry_automation"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.lispace.wzryncauto.STOP_OVERLAY"
        const val ACTION_EMERGENCY_STOP = "com.lispace.wzryncauto.EMERGENCY_STOP"
        const val ACTION_TEST_SCREENSHOT = "com.lispace.wzryncauto.TEST_SCREENSHOT"
        const val ACTION_TEST_OCR = "com.lispace.wzryncauto.TEST_OCR"
        const val ACTION_TEST_HARVEST_OCR =
            "com.lispace.wzryncauto.TEST_HARVEST_OCR"
        const val ACTION_START_AUTOMATION = "com.lispace.wzryncauto.START_AUTOMATION"
        const val ACTION_RUN_DUE = "com.lispace.wzryncauto.RUN_DUE_SERVICE"
        const val ACTION_ENABLE_FRAME_STREAM =
            "com.lispace.wzryncauto.ENABLE_FRAME_STREAM"
        const val EXTRA_RUNTIME_GENERATION = "runtime_generation"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val MAX_VISIBLE_LOG_LINES = 200
        private const val VISIBLE_LOG_ROUNDS = 9
        private const val LOG_TAG = "WzryOverlay"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        private val PERSISTED_LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val DIAGNOSTIC_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        private const val RUNTIME_LOG_FILE = "runtime.log"
        private const val MAX_RUNTIME_LOG_BYTES = 1_000_000L
        private const val PERSISTED_LOG_LINES = 100
        private const val SCHEDULE_SAFETY_MARGIN_SECONDS = -5L
        private const val ALARM_EARLY_TOLERANCE_MS = 30_000L
        private const val IN_PROCESS_WAKE_RECHECK_MS = 60_000L
        private const val FARM_STATE_MAX_AGE_HOURS = 72L
        private const val FARM_STATE_MATURITY_TOLERANCE_MINUTES = 10L
        private const val MAX_DIAGNOSTIC_DIRECTORIES = 20
        private const val FAILURE_RETRY_DELAY_MS = 60_000L
        private const val DIAGNOSTIC_RECENT_LOG_LINES = 30
        private const val SCREEN_WAKE_ATTEMPTS = 10
        private const val SCREEN_WAKE_INTERVAL_MS = 300L
        private const val KEYGUARD_DISMISS_ATTEMPTS = 10
        private const val KEYGUARD_DISMISS_INTERVAL_MS = 300L

        private const val COLOR_IDLE = 0xFF616161.toInt()
        private const val COLOR_RUNNING = 0xFF2E7D32.toInt()
        private const val COLOR_WAITING = 0xFFF9A825.toInt()
        private const val COLOR_ERROR = 0xFFC62828.toInt()
    }

    private data class KeyLogLine(
        val round: Int,
        val time: String,
        val message: String,
    )

    private data class NextRunPlan(
        val wakeAt: LocalDateTime,
        val reason: WakeReason,
    )

    private sealed interface RoundOutcome {
        data class Waiting(
            val wakeAt: LocalDateTime,
            val exact: Boolean,
            val generation: Long,
            val triggerAtEpochMs: Long,
        ) : RoundOutcome

        data class Completed(val reason: String) : RoundOutcome
    }
}
