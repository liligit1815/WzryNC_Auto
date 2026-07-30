package com.lispace.wzryncauto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import com.lispace.wzryncauto.automation.EnterFarmAutomation
import com.lispace.wzryncauto.automation.FarmActionAutomation
import com.lispace.wzryncauto.automation.RootAutomationRuntime
import com.lispace.wzryncauto.device.RootCommandExecutor
import com.lispace.wzryncauto.device.MediaProjectionFrameSource
import com.lispace.wzryncauto.device.RootDeviceController
import com.lispace.wzryncauto.device.RootScreenshotProvider
import com.lispace.wzryncauto.device.SafeScreenshotCapture
import com.lispace.wzryncauto.device.BrightnessController
import com.lispace.wzryncauto.device.BrightnessPreference
import com.lispace.wzryncauto.device.BrightnessSnapshot
import com.lispace.wzryncauto.device.RunBrightnessMode
import com.lispace.wzryncauto.ocr.MaturityOcrEngine
import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.OcrSampleStore
import com.lispace.wzryncauto.schedule.FarmSchedule
import com.lispace.wzryncauto.schedule.FarmScheduleCalculator
import com.lispace.wzryncauto.schedule.FarmStateStore
import com.lispace.wzryncauto.schedule.WakeReason
import com.lispace.wzryncauto.vision.OpenCvTemplateMatcher
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
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rootExecutor = RootCommandExecutor()
    private val frameSource by lazy { MediaProjectionFrameSource(applicationContext) }
    private val screenshotCapture by lazy {
        SafeScreenshotCapture(
            provider = RootScreenshotProvider(rootExecutor),
            cacheDirectory = cacheDir,
            hideOverlay = { setOverlayVisibility(View.INVISIBLE) },
            restoreOverlay = { setOverlayVisibility(View.VISIBLE) },
            streamFrame = { frameSource.latestFrame() },
        )
    }
    private val templateMatcher by lazy { OpenCvTemplateMatcher(assets) }
    private val deviceController by lazy { RootDeviceController(rootExecutor) }
    private val automationRuntime by lazy {
        RootAutomationRuntime(
            deviceController,
            screenshotCapture,
            templateMatcher,
            ocrEngine,
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
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var simulationJob: Job? = null
    private var currentToast: Toast? = null
    private var lastToastState: AutomationState? = null
    private var brightnessSnapshot: BrightnessSnapshot? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val keyLogLines = ArrayDeque<KeyLogLine>()
    private var currentLogRound = 0
    private var totalExperience = 0
    private val totalCrops = linkedMapOf<String, Int>()

    private var loopCount = 5
    private var isInfinite = false
    private var completedRounds = 0
    private var isRunning = false
    private var isPaused = false
    private var remainingSeconds = 0L
    private var nextWakeAt: LocalDateTime? = null
    private var currentOperation = "等待开始"

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
        startForeground(NOTIFICATION_ID, buildNotification("悬浮窗已就绪"))
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadPersistedLogs()
        if (Settings.canDrawOverlays(this)) {
            showBubble()
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> shutdown()
            ACTION_EMERGENCY_STOP -> stopSimulation("紧急停止")
            ACTION_TEST_SCREENSHOT -> testScreenshot()
            ACTION_TEST_OCR -> testMaturityOcr()
            ACTION_TEST_HARVEST_OCR -> testHarvestOcr()
            ACTION_START_AUTOMATION -> startSimulation()
            ACTION_ENABLE_FRAME_STREAM -> enableFrameStream(intent)
        }
        return START_STICKY
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
        currentToast?.cancel()
        currentToast = null
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
        logContent.addView(label("关键日志", 14f))
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

    private fun startSimulation() {
        if (isRunning) {
            appendLog("助手正在运行")
            return
        }
        isRunning = true
        isPaused = false
        completedRounds = 0
        currentLogRound = 0
        keyLogLines.clear()
        totalExperience = 0
        totalCrops.clear()
        remainingSeconds = 0L
        nextWakeAt = null
        currentOperation = "准备执行完整农场动作"
        appendLog("开始定时循环：启动游戏 → 进入农场 → 务农 → OCR → 排程")
        updateBubbleColor(COLOR_RUNNING)
        // A large overlay panel has to be hidden for every screenshot and
        // injected gesture, which produces distracting full-panel flashing.
        // Keep collecting detailed logs while running and show only the bubble.
        hidePanel()
        simulationJob = serviceScope.launch {
            val control = object : AutomationControl {
                override suspend fun awaitRunnable() {
                    while (isPaused) delay(250)
                }
            }
            runCatching {
                acquireScreenWakeLock()
                prepareRunBrightness()
                try {
                    runTimedLoop(control)
                } finally {
                    withContext(NonCancellable) {
                        restoreRunBrightness()
                        releaseScreenWakeLock()
                    }
                }
            }.onSuccess {
                isRunning = false
                isPaused = false
                nextWakeAt = null
                remainingSeconds = 0L
                currentOperation = "设定循环已完成"
                appendLog("定时务农已完成，共执行 $completedRounds 轮")
                showOperationToast("定时务农已完成")
                updateBubbleColor(COLOR_IDLE)
                refreshUi()
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                isRunning = false
                isPaused = false
                currentOperation = "执行失败"
                appendLog("自动化失败：${it.message}")
                showOperationToast("执行失败：${it.message ?: "未知错误"}")
                updateBubbleColor(COLOR_ERROR)
                refreshUi()
            }.also {
                if (!isRunning) {
                    simulationJob = null
                }
            }
        }
        refreshUi()
    }

    private suspend fun runTimedLoop(control: AutomationControl) {
        while (isInfinite || completedRounds < loopCount) {
            control.awaitRunnable()
            currentOperation = "执行第 ${completedRounds + 1} 轮"
            nextWakeAt = null
            remainingSeconds = 0L
            refreshUi()
            appendLog("第 ${completedRounds + 1} 轮开始")
            val roundStartedAt = LocalDateTime.now()

            EnterFarmAutomation(
                runtime = automationRuntime,
                control = control,
                onState = ::onAutomationState,
                onLog = ::appendLog,
            ).run()
            val result = FarmActionAutomation(
                runtime = automationRuntime,
                control = control,
                onState = ::onAutomationState,
                onLog = ::appendLog,
            ).run()
            if (result.harvested) {
                result.harvestInfo?.let(::recordHarvest)
            }
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
            completedRounds += 1
            refreshUi()

            currentOperation = "本轮完成，退出游戏"
            refreshUi()
            automationRuntime.stopGame()
            if (!isInfinite && completedRounds >= loopCount) {
                appendLog("已达到设定循环次数")
                return
            }

            val wakeAt = when (val maturity = result.maturity) {
                is MaturityReading.Time -> {
                    val observedMaturity = FarmScheduleCalculator.resolveObservedMaturity(
                        maturity,
                        result.firstWaterAt,
                    )
                    val stored = if (result.harvested) null else farmStateStore.load()
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
                            schedule.wakeAt.plusSeconds(secondsToOneClick)
                                .format(DATE_TIME_FORMAT),
                    )
                    showOperationToast(
                        "排程完成：${schedule.wakeAt.format(TIME_FORMAT)} 执行",
                    )
                    schedule.wakeAt
                }
                is MaturityReading.Mature -> {
                    appendLog("成熟时间：已成熟")
                    LocalDateTime.now().plusSeconds(3).also {
                        appendLog("下次操作时间：${it.format(DATE_TIME_FORMAT)}")
                    }
                }
                is MaturityReading.Unrecognized ->
                    error("自动化返回了未识别的成熟时间")
            }

            currentOperation = "等待下次执行"
            refreshUi()
            showOperationToast("本轮完成，等待下次执行")
            updateBubbleColor(COLOR_WAITING)
            waitUntil(wakeAt, control)
        }
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

    private suspend fun waitUntil(target: LocalDateTime, control: AutomationControl) {
        nextWakeAt = target
        while (true) {
            control.awaitRunnable()
            val seconds = Duration.between(LocalDateTime.now(), target).seconds.coerceAtLeast(0)
            remainingSeconds = seconds
            currentOperation = if (seconds > 0) "等待下次执行" else "准备唤醒"
            refreshUi()
            if (seconds <= 0) break
            delay(1_000)
        }
        nextWakeAt = null
        remainingSeconds = 0L
        appendLog("到达唤醒时间，开始下一轮")
        showOperationToast("到达执行时间，开始下一轮")
        updateBubbleColor(COLOR_RUNNING)
    }

    private fun togglePause() {
        if (!isRunning) {
            showOperationToast("当前没有运行中的任务")
            return
        }
        isPaused = !isPaused
        appendLog(if (isPaused) "已暂停" else "继续运行")
        showOperationToast(if (isPaused) "自动化已暂停" else "自动化继续运行")
        updateBubbleColor(if (isPaused) COLOR_WAITING else COLOR_RUNNING)
        refreshUi()
    }

    private fun stopSimulation(reason: String) {
        simulationJob?.cancel()
        simulationJob = null
        rootExecutor.cancelCurrent()
        isRunning = false
        isPaused = false
        remainingSeconds = 0L
        nextWakeAt = null
        currentOperation = reason
        releaseScreenWakeLock()
        appendLog(reason)
        showOperationToast(reason)
        updateBubbleColor(if (reason == "紧急停止") COLOR_ERROR else COLOR_IDLE)
        refreshUi()
        updateNotification(reason)
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

    private fun testTemplateMatch() {
        appendLog("截屏并测试 start_game.png 识别…")
        showOperationToast("正在测试图像识别")
        serviceScope.launch {
            val capture = screenshotCapture.capture().getOrElse {
                appendLog("识别前截图失败: ${it.message}")
                return@launch
            }
            val screenshotBytes = withContext(Dispatchers.IO) {
                capture.file.readBytes()
            }
            val match = withContext(Dispatchers.Default) {
                templateMatcher.match(
                    screenshotPng = screenshotBytes,
                    templateName = "start_game.png",
                    screenshotId = "${capture.file.lastModified()}-${capture.byteCount}",
                )
            }.getOrElse {
                appendLog("OpenCV 识别失败: ${it.message}")
                return@launch
            }
            appendLog(
                "start_game score=%.3f/%s，中心(%d,%d)，scale=%.3f".format(
                    match.score,
                    if (match.matched) "通过" else "未通过",
                    match.centerX,
                    match.centerY,
                    match.scale,
                ),
            )
            showOperationToast(if (match.matched) "图像识别成功" else "未识别到开始游戏按钮")
            val diagnostic = withContext(Dispatchers.Default) {
                templateMatcher.annotate(screenshotBytes, match)
            }.getOrElse {
                appendLog("诊断图生成失败: ${it.message}")
                return@launch
            }
            withContext(Dispatchers.IO) {
                val directory = File(cacheDir, "diagnostics").apply { mkdirs() }
                File(directory, "start_game_latest.png").writeBytes(diagnostic)
            }
            appendLog("诊断图已保存: cache/diagnostics/start_game_latest.png")
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
            val screenshotBytes = withContext(Dispatchers.IO) {
                capture.file.readBytes()
            }
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
            val screenshotBytes = withContext(Dispatchers.IO) {
                capture.file.readBytes()
            }
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

    private fun setOverlayVisibility(visibility: Int) {
        bubble?.visibility = visibility
        panel?.visibility = visibility
    }

    private fun refreshUi() {
        if (!::statusText.isInitialized) return
        statusText.text = when {
            isPaused -> "状态：已暂停"
            isRunning -> "状态：$currentOperation"
            else -> "状态：$currentOperation"
        }
        progressText.text = if (isInfinite) {
            "进度：$completedRounds / ∞"
        } else {
            "进度：$completedRounds / $loopCount"
        }
        countdownText.text = when {
            nextWakeAt != null -> {
                val hours = remainingSeconds / 3600
                val minutes = (remainingSeconds % 3600) / 60
                val seconds = remainingSeconds % 60
                "下次执行：${nextWakeAt!!.format(TIME_FORMAT)}（%02d:%02d:%02d）".format(
                    hours,
                    minutes,
                    seconds,
                )
            }
            isRunning -> "当前阶段：执行中"
            else -> "下次执行：--"
        }
        pauseButton.text = if (isPaused) "继续" else "暂停"
        updateNotification(statusText.text.toString())
    }

    private fun appendLog(message: String) {
        Log.i(LOG_TAG, message)
        persistLog("${LocalDateTime.now().format(PERSISTED_LOG_FORMAT)} $message")
        if (!isKeyLog(message)) return
        ROUND_START.find(message)?.groupValues?.get(1)?.toIntOrNull()?.let {
            currentLogRound = it
        }
        keyLogLines.addLast(KeyLogLine(currentLogRound, "${timestamp()} $message"))
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
            File(filesDir, RUNTIME_LOG_FILE)
                .takeIf(File::isFile)
                ?.readLines()
                ?.takeLast(PERSISTED_LOG_LINES)
                ?.forEach { line -> keyLogLines.addLast(KeyLogLine(0, line)) }
        }.onFailure {
            Log.e(LOG_TAG, "load persisted log failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        screenWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "$packageName:automation-screen",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        appendLog("运行期间屏幕常亮已启用")
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

    private fun renderKeyLogs(): String =
        if (keyLogLines.isEmpty()) {
            "${timestamp()} 悬浮窗服务已启动\n"
        } else {
            keyLogLines.joinToString(separator = "\n", postfix = "\n") { it.text }
        }

    private suspend fun prepareRunBrightness() {
        val mode = BrightnessPreference.load(applicationContext)
        if (mode == RunBrightnessMode.KEEP) return
        brightnessSnapshot = brightnessController.captureSnapshot().getOrThrow()
        val result = when (mode) {
            RunBrightnessMode.KEEP -> return
            RunBrightnessMode.SYSTEM_LOW -> brightnessController.setSystemLow()
            RunBrightnessMode.ROOT_LOW -> brightnessController.setRootLow()
        }
        check(result.isSuccess) {
            "设置运行亮度失败：${result.stderr.ifBlank { result.stdout }.trim()}"
        }
        appendLog("运行亮度：${mode.label}")
    }

    private suspend fun restoreRunBrightness() {
        val snapshot = brightnessSnapshot ?: return
        brightnessSnapshot = null
        val errors = brightnessController.restore(snapshot)
        if (errors.isEmpty()) {
            appendLog("已恢复运行前亮度")
        } else {
            appendLog("恢复亮度失败：${errors.joinToString()}")
        }
    }

    private fun timestamp(): String = LocalTime.now().format(TIME_FORMAT)

    private fun isKeyLog(message: String): Boolean {
        return ROUND_START.containsMatchIn(message) ||
            message.startsWith("本轮收获：") ||
            message.startsWith("启动到一键务农：") ||
            message.startsWith("检测到收获，记录新的") ||
            message.startsWith("作物周期：") ||
            message.startsWith("成熟时间：") ||
            message.startsWith("下个目标：") ||
            message.startsWith("下次操作时间：") ||
            message.startsWith("预计一键务农：") ||
            message.startsWith("自动化失败：") ||
            message.startsWith("异常：")
    }

    private fun onAutomationState(state: AutomationState, message: String) {
        currentOperation = message
        refreshUi()
        showStageToast(state)
        Log.i(LOG_TAG, "automation state=$state message=$message")
    }

    private fun showOperationToast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(
            applicationContext,
            "WzryNCAuto\n$message",
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun showStageToast(state: AutomationState) {
        if (lastToastState == state) return
        lastToastState = state
        val message = when (state) {
            AutomationState.WAITING_LOGIN -> "正在检测开始游戏"
            AutomationState.CLOSING_STARTUP_POPUPS -> "正在检测广告弹窗"
            AutomationState.CLICKING_START_GAME -> "正在点击开始游戏"
            AutomationState.WAITING_LOBBY -> "正在等待游戏大厅"
            AutomationState.CLOSING_LOBBY_POPUPS -> "正在检测广告弹窗"
            AutomationState.ENTERING_FARM -> "正在进入农场"
            AutomationState.RESETTING_POSITION -> "正在检测农场初始位置"
            AutomationState.MOVING_TO_STATUE -> "正在移动到一键务农位置"
            AutomationState.VERIFYING_ONE_CLICK_FARM -> "正在检测一键务农"
            AutomationState.ONE_CLICK_FARMING -> "正在执行一键务农"
            AutomationState.HANDLING_HARVEST -> "正在检测收获弹窗"
            AutomationState.MOVING_TO_FARMLAND -> "正在移动到土地"
            AutomationState.VERIFYING_FARMLAND,
            AutomationState.READING_MATURITY,
            -> "正在检测作物成熟信息"
            else -> return
        }
        currentToast?.cancel()
        currentToast = Toast.makeText(
            applicationContext,
            "WzryNCAuto\n$message",
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun shutdown() {
        stopSimulation("悬浮窗服务停止")
        rootExecutor.cancelCurrent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateBubbleColor(color: Int) {
        bubble?.background = circleDrawable(color)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
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
        const val ACTION_ENABLE_FRAME_STREAM =
            "com.lispace.wzryncauto.ENABLE_FRAME_STREAM"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val MAX_VISIBLE_LOG_LINES = 200
        private const val VISIBLE_LOG_ROUNDS = 9
        private const val LOG_TAG = "WzryOverlay"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        private val PERSISTED_LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val ROUND_START = Regex("""第\s*(\d+)\s*轮开始""")
        private const val RUNTIME_LOG_FILE = "runtime.log"
        private const val MAX_RUNTIME_LOG_BYTES = 1_000_000L
        private const val PERSISTED_LOG_LINES = 100
        private const val SCHEDULE_SAFETY_MARGIN_SECONDS = -5L

        private const val COLOR_IDLE = 0xFF616161.toInt()
        private const val COLOR_RUNNING = 0xFF2E7D32.toInt()
        private const val COLOR_WAITING = 0xFFF9A825.toInt()
        private const val COLOR_ERROR = 0xFFC62828.toInt()
    }

    private data class KeyLogLine(val round: Int, val text: String)
}
