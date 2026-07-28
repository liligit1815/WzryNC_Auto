# 王者荣耀农场助手 APK 完整开发方案

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 项目名称 | 王者荣耀农场助手 |
| 第一版形态 | ROOT Android APK |
| 运行方式 | 游戏前台运行，悬浮窗控制 |
| 循环方式 | OCR 驱动的定时循环 |
| 目标设备 | Xiaomi M2002J9E |
| Android 版本 | Android 12 / API 31 |
| CPU 架构 | arm64-v8a |
| 目标横屏分辨率 | 2400×1080 |
| 安装方式 | 本地签名 APK 侧载 |
| 当前依据 | `wzry_auto.py` Python 自动化流程 |

## 2. 建设目标

将现有依赖电脑、Python 和 ADB 的自动化程序迁移为可直接安装在目标
Android 手机上运行的 APK。

用户打开 APK、授予 ROOT 与悬浮窗权限后，可以进入王者荣耀，通过悬浮窗
设置循环次数并开始或停止自动化。运行过程中，悬浮窗显示当前步骤、循环
进度、下一次执行时间、倒计时和实时日志。

第一版应完整实现：

- ROOT 权限检测与授权；
- 游戏启动、停止和前台状态检测；
- ROOT 截图、点击、滑动和亮度控制；
- OpenCV 模板匹配；
- 中文 OCR；
- 单轮务农状态机；
- OCR 驱动的定时循环；
- 指定次数和无限循环；
- 悬浮球、控制面板、日志面板和设置面板；
- 开始、暂停、继续、停止和紧急停止；
- 运行状态持久化；
- 失败截图、上下文和日志；
- 异常时安全恢复悬浮窗和亮度。

## 3. 第一版范围

### 3.1 包含

- 仅支持已获得 Magisk ROOT 的 Android 手机；
- 优先支持当前 Xiaomi M2002J9E；
- 优先支持游戏横屏 2400×1080；
- APK 与游戏同时运行，手机保持亮屏；
- 等待期间退出王者荣耀，但悬浮窗与服务继续运行；
- 到达计算出的目标时间后重新启动游戏；
- 支持 1～99 次以及无限循环；
- 模板和 OCR 完全在本机处理；
- 本地日志与诊断，不依赖服务器。

### 3.2 不包含

- 无 ROOT 模式；
- AccessibilityService 自动化；
- MediaProjection 截图；
- 息屏、锁屏和自动输入锁屏密码；
- AlarmManager、精确闹钟和开机自启动；
- 固定时间间隔循环；
- Google Play 发布；
- 云端控制和账号系统；
- 多账号、多开和多设备控制；
- 自动下载或在线更新模板；
- 自动规避游戏检测。

## 4. 用户使用流程

```text
安装并打开 APK
        ↓
完成 ROOT、悬浮窗和通知权限检查
        ↓
选择游戏版本和亮度模式
        ↓
点击“打开悬浮窗并启动游戏”
        ↓
进入王者荣耀，右侧显示悬浮球
        ↓
展开悬浮窗，设置循环次数
        ↓
点击“开始”
        ↓
执行一轮务农并识别成熟时间
        ↓
计算下一次浇水或收获时间
        ↓
退出游戏，悬浮窗显示倒计时
        ↓
到达目标时间后重新启动游戏
        ↓
完成下一轮，直到达到循环次数或用户停止
```

## 5. 循环语义

一次循环定义为一次完整的务农尝试：

```text
确认或启动游戏
→ 进入登录页
→ 进入大厅
→ 进入农场
→ 移动到雕像
→ 点击一键务农
→ 处理收获结果
→ 移动到土地
→ OCR 读取成熟时间
→ 计算下次执行时间
```

设置循环 5 次时：

```text
第 1 轮 → 定时等待
第 2 轮 → 定时等待
第 3 轮 → 定时等待
第 4 轮 → 定时等待
第 5 轮 → 完成并停止
```

最后一轮完成后不再创建等待任务。

### 5.1 定时规则

- 下一次执行时间由当前批次、作物周期和 OCR 成熟时间共同确定；
- 优先选择尚未执行且早于 OCR 成熟时间的最近浇水节点；
- 没有有效浇水节点时选择 OCR 成熟时间；
- 默认提前 2 分钟启动下一轮；
- 倒计时必须使用绝对时间计算，不能依赖逐秒减一；
- 用户调整系统时间后立即重新计算剩余时间；
- OCR 失败时进入有限重试，不能创建未经确认的长期等待；
- 目标时间已经到达时立即进入下一轮。

## 6. 悬浮窗产品设计

### 6.1 收起悬浮球

- 默认吸附屏幕右侧；
- 支持拖动和左右边缘吸附；
- 保存最后位置；
- 单击展开控制面板；
- 不获取全屏输入焦点；
- 不影响悬浮球以外的游戏触控。

状态颜色：

| 颜色 | 状态 |
|---|---|
| 灰色 | 未运行 |
| 蓝色 | 准备或启动游戏 |
| 绿色 | 自动化执行中 |
| 黄色 | 等待、暂停或重试 |
| 红色 | 错误或需要用户处理 |

### 6.2 控制面板

```text
┌──────────────────────────┐
│ 王者农场助手       收起  × │
├──────────────────────────┤
│ 状态：等待下一次浇水       │
│ 当前：步骤 8 / 处理收获     │
│ 进度：2 / 5               │
│ 下一动作：浇水             │
│ 目标时间：14:20:00         │
│ 剩余时间：00:17:36         │
├──────────────────────────┤
│ 循环：[－]  5  [＋] [无限] │
│ [开始] [暂停/继续] [停止]  │
│ [日志] [设置] [保存现场]   │
└──────────────────────────┘
```

规则：

- 运行开始后锁定循环次数，避免中途修改导致歧义；
- “×”用于关闭悬浮窗服务，执行前必须确认；收起按钮不停止任务；
- 暂停只阻止新的自动化动作；
- 停止取消当前任务并进行安全清理；
- 长按停止执行紧急停止；
- 错误状态必须显示可读原因，而不只是错误码。

### 6.3 日志面板

- 显示最近 200 行；
- 按时间顺序自动滚动；
- 支持 INFO、WARN、ERROR 颜色区分；
- 支持清空显示、复制和导出；
- 完整日志持续写入应用内部文件；
- 日志不得包含 ROOT 敏感输入或锁屏密码。

日志格式：

```text
12:40:02 INFO  ROOT 授权成功
12:40:05 INFO  开始游戏 score=0.985 x=1190 y=842
12:40:06 INFO  点击开始游戏
12:40:18 INFO  已进入大厅
12:40:44 INFO  一键务农成功
12:40:51 WARN  未找到收获弹窗，重试 1/3
```

### 6.4 设置面板

第一版设置项：

- 循环次数：1～99 或无限；
- 提前执行时间：默认 2 分钟；
- 完成后是否退出游戏；
- 等待期间是否退出游戏；
- 亮度模式：保持、系统最低、ROOT 亮度 1；
- 截图超时：默认 30 秒；
- 截图重试：默认 3 次；
- 是否保存诊断现场；
- 最大诊断数量；
- 游戏版本和包名；
- 分辨率配置。

不提供固定间隔循环设置。

## 7. Android 技术架构

```text
MainActivity
├── PermissionGuide
├── RootSelfTest
├── BasicSettings
└── OverlayLauncher

AutomationForegroundService
├── OverlayController
│   ├── FloatingBubble
│   ├── ControlPanel
│   ├── LogPanel
│   └── SettingsPanel
├── AutomationEngine
│   ├── AutomationStateMachine
│   ├── LoopController
│   ├── PauseController
│   └── CancellationController
├── Device
│   ├── RootCommandExecutor
│   ├── ScreenshotProvider
│   ├── InputController
│   ├── GameController
│   └── BrightnessController
├── Vision
│   ├── TemplateRepository
│   ├── TemplateMatcher
│   └── OcrEngine
├── Scheduling
│   ├── FarmScheduleCalculator
│   └── CountdownController
└── Data
    ├── SettingsRepository
    ├── FarmStateRepository
    ├── RuntimeStateRepository
    ├── LogRepository
    └── DiagnosticManager
```

### 7.1 技术选型

| 领域 | 方案 |
|---|---|
| 开发语言 | Kotlin |
| 主界面 | Jetpack Compose |
| 悬浮窗 | WindowManager + Android View/ComposeView |
| 任务并发 | Kotlin Coroutines |
| 状态输出 | StateFlow / SharedFlow |
| 服务 | ForegroundService |
| ROOT | `su -c` 子进程 |
| 截图 | ROOT `screencap` |
| 输入 | ROOT `input tap/swipe/keyevent` |
| 模板匹配 | OpenCV Android |
| OCR | ML Kit Text Recognition v2 中文打包模型 |
| 设置和状态 | DataStore |
| 日志 | 内部文件 + 内存环形缓冲 |
| 构建 | Gradle Kotlin DSL |
| 第一版 ABI | arm64-v8a |

## 8. 服务与权限

### 8.1 必要权限

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

说明：

- `SYSTEM_ALERT_WINDOW` 由设置页引导用户授权；
- Android 13 以上才运行时请求通知权限；
- 第一版目标设备为 Android 12，但代码应兼容通知权限分支；
- ROOT 权限由 Magisk 弹窗授权，不是 Manifest 权限；
- `WAKE_LOCK` 只防止运行中 CPU 休眠，不实现息屏唤醒。

### 8.2 前台服务

用户从 MainActivity 点击“打开悬浮窗”时启动服务。服务负责：

- 悬浮窗生命周期；
- 自动化任务；
- 倒计时；
- 日志；
- 安全清理。

服务必须显示不可静默隐藏的通知，并提供：

- 当前状态；
- 循环进度；
- 下一目标时间；
- 停止按钮。

### 8.3 悬浮窗参数

- Android 8 以上使用 `TYPE_APPLICATION_OVERLAY`；
- 收起状态只占悬浮球区域；
- 使用 `FLAG_NOT_FOCUSABLE`，避免抢占游戏输入；
- 展开面板只拦截面板自身区域；
- 监听横竖屏和显示尺寸变化；
- 第一版检测到非 2400×1080 横屏时暂停并提示。

## 9. ROOT 命令层

### 9.1 接口

```kotlin
interface RootCommandExecutor {
    suspend fun executeText(
        command: String,
        timeoutMs: Long
    ): CommandResult

    suspend fun executeBinary(
        command: String,
        timeoutMs: Long
    ): BinaryCommandResult

    suspend fun cancelCurrent()
}
```

`CommandResult` 至少包含：

```text
exitCode
stdout
stderr
durationMs
timedOut
attemptCount
```

### 9.2 必须支持的命令

```text
su -c id
am start -n <game activity>
am force-stop <game package>
dumpsys activity
dumpsys window
input tap x y
input swipe x1 y1 x2 y2 duration
input keyevent
screencap -p
settings get/put system screen_brightness
settings get/put system screen_brightness_mode
读取和写入 /sys/class/backlight/*/brightness
wm size
wm density
```

### 9.3 安全要求

- 不通过宿主 Shell 二次解释用户输入；
- 命令参数集中构建和转义；
- 每条命令都有超时；
- 超时后销毁进程；
- 自动化停止后禁止提交新命令；
- ROOT 失败不能导致 UI 线程 ANR；
- 二进制截图不能经过文本编码；
- 清理动作逐项执行，一个失败不能跳过其余动作。

## 10. 截图设计

### 10.1 基本流程

```text
获得截图互斥锁
→ 暂时隐藏全部悬浮窗
→ 等待 100～200ms 完成画面刷新
→ ROOT 执行 screencap
→ 验证 PNG 和分辨率
→ 恢复悬浮窗
→ 释放互斥锁
→ 进行 OpenCV/OCR
```

悬浮窗恢复必须放在 `finally` 语义中。

### 10.2 截图要求

- 默认单次超时 30 秒；
- 默认最多尝试 3 次；
- 每次失败之间短暂等待；
- 验证 PNG 可解码；
- 验证分辨率不小于最低要求；
- 竖屏截图自动转换或拒绝；
- 新截图成功前不替换上一张有效截图；
- 业务识别必须获得截图 ID，禁止误用旧截图；
- 记录截图时间和耗时。

### 10.3 截图与点击一致性

截图和自动点击共享操作互斥锁：

```text
截图 → 模板匹配 → 校验截图仍是当前帧 → 点击
```

等待数秒后点击时必须重新截图，不能使用旧坐标。

## 11. 模板匹配

迁移 Python 版本中的：

- `TM_CCOEFF_NORMED`；
- ROI；
- 每模板独立阈值；
- 有限多尺度；
- 分辨率专属模板；
- 匹配中心点；
- 失败诊断。

第一版打包 `assets/templates` 中的 17 个模板，不打包
`assets/screenshots` 中约 6.9 MB 的模板源截图。

匹配结果：

```kotlin
data class TemplateMatch(
    val templateName: String,
    val score: Double,
    val centerX: Int,
    val centerY: Int,
    val bounds: Rect,
    val screenshotId: String
)
```

点击前必须检查：

- 分数达到阈值；
- 坐标位于安全区域；
- 截图 ID 未过期；
- 当前状态允许点击该模板；
- 自动化没有被暂停或停止。

## 12. OCR 方案

第一版采用 ML Kit 中文打包模型，避免首次运行下载。

OCR 仅裁剪必要区域：

- 土地成熟时间；
- 收获弹窗信息。

处理流程：

```text
裁剪 ROI
→ 放大
→ 对比度或灰度预处理
→ ML Kit OCR
→ 文本规范化
→ 正则解析
→ 合理性校验
```

成熟时间兼容：

```text
12:00成熟
12：00成熟
12点00分
存在空格或多余字符
```

合理性校验：

- 小时为 0～23；
- 分钟为 0～59；
- 早于当前时间时按跨天规则处理；
- 与当前批次理论周期严重冲突时以 OCR 成熟时间作为安全上限；
- OCR 不确定时保留原始文本并进入重试。

如果真机样本准确率不足，再评估 ONNX Runtime Mobile 和 RapidOCR 模型；
第一阶段不同时集成两套 OCR。

## 13. 自动化状态机

### 13.1 土地移动路径与前置状态

步骤 9 的摇杆动作不是从农场初始位置直接前往土地。正确且不可跳过的
完整路径为：

```text
农场初始位置
→ 步骤 6：移动到雕像
→ 验证 oneclick_farm.png 出现
→ 步骤 7：点击一键务农
→ 处理可能出现的收获弹窗
→ 步骤 9：(430,755) → (430,555), 1200ms
→ 到达土地并显示左侧作物卡片
```

2026-07-27 的一次真机验证从初始位置直接执行了步骤 9，因此未到达土地；
这不代表步骤 9 参数失效，而是前置状态错误。

APK 状态机必须：

- 只有在雕像处完成一键务农后才能进入 `MOVING_TO_FARMLAND`；
- 以 `oneclick_farm.png` 作为到达雕像的视觉条件；
- 点击一键务农后重新截图，处理收获弹窗或确认按钮已经消失；
- 步骤 9 移动后，以左侧作物卡片或成熟时间区域出现作为到达成功条件；
- 未出现作物卡片时有限重试，不执行 OCR 和等待计算；
- 为不同分辨率分别保存雕像路径和步骤 9 摇杆参数。

```kotlin
enum class AutomationState {
    IDLE,
    PREPARING,
    CHECKING_GAME,
    LAUNCHING_GAME,
    WAITING_LOGIN,
    CLOSING_STARTUP_POPUPS,
    CLICKING_START_GAME,
    WAITING_LOBBY,
    CLOSING_LOBBY_POPUPS,
    ENTERING_FARM,
    RESETTING_POSITION,
    MOVING_TO_STATUE,
    ONE_CLICK_FARMING,
    HANDLING_HARVEST,
    MOVING_TO_FARMLAND,
    READING_MATURITY,
    CALCULATING_SCHEDULE,
    STOPPING_GAME,
    WAITING_NEXT_RUN,
    PAUSED,
    STOPPING,
    COMPLETED,
    ERROR
}
```

步骤返回值：

```kotlin
sealed interface StepResult {
    data class Success(val next: AutomationState) : StepResult
    data class Retry(
        val delayMs: Long,
        val reason: String
    ) : StepResult
    data class Failure(
        val recoverable: Boolean,
        val reason: String
    ) : StepResult
}
```

每个步骤必须定义：

- 超时；
- 最大重试次数；
- 重试间隔；
- 成功条件；
- 可恢复失败动作；
- 不可恢复失败动作；
- 诊断名称；
- 是否允许用户暂停。

## 14. 暂停、停止与异常恢复

### 14.1 暂停

- 不发起新的截图、点击或滑动；
- 已经执行的单条命令允许结束或超时；
- 保存当前状态和循环进度；
- 不修改目标绝对时间；
- 恢复时若目标时间已到，立即执行下一轮。

### 14.2 普通停止

- 设置全局停止标志；
- 取消自动化协程；
- 阻止新命令；
- 等待当前命令短时间结束；
- 恢复悬浮窗；
- 按设置决定是否退出游戏；
- 恢复亮度；
- 保存本轮日志；
- 状态回到 `IDLE`。

### 14.3 紧急停止

长按悬浮窗停止按钮或点击通知停止按钮：

- 立即取消任务；
- 超时终止当前 ROOT 子进程；
- `am force-stop` 游戏；
- 恢复亮度；
- 保存最后现场；
- 服务保持或退出由用户选择。

### 14.4 连续失败熔断

- 同一步骤连续失败达到上限时停止当前轮；
- 多轮连续失败达到上限时停止整个自动化；
- 禁止无限启动游戏或无限点击；
- 红色悬浮球显示需要用户处理；
- 日志显示明确错误和诊断路径。

## 15. 亮度控制

启动时保存：

- `screen_brightness`；
- `screen_brightness_mode`；
- `/sys/class/backlight/*/brightness` 实际节点和值。

支持：

| 模式 | 行为 |
|---|---|
| 保持 | 不修改亮度 |
| 系统最低 | 系统亮度设为 1 |
| ROOT 极低 | 实际背光节点设为 1 |

悬浮窗必须始终可见，因此第一版不提供 ROOT 亮度 0。

恢复时逐项执行：

1. 恢复实际存在的背光节点；
2. 恢复系统亮度值；
3. 恢复自动亮度模式；
4. 显示未恢复项目。

首页和悬浮设置中都提供“立即恢复亮度”按钮。

## 16. 数据模型

### 16.1 AppSettings

```text
loopMode = FINITE | INFINITE
loopCount
wakeAdvanceMinutes
stopGameWhileWaiting
stopGameAfterCompletion
brightnessMode
screenshotTimeoutMs
screenshotRetries
resolutionProfile
gamePackage
gameActivity
saveDiagnostics
maxDiagnosticCount
```

### 16.2 FarmState

```text
batchStartedAt
cycleMinutes
observedMaturityAt
nextRunAt
nextAction
lastFarmedAt
updatedAt
```

### 16.3 RuntimeState

```text
isRunning
isPaused
currentState
currentRound
targetRounds
lastSuccessfulState
nextRunAt
lastError
lastScreenshotAt
startedAt
```

### 16.4 LogEntry

```text
timestamp
level
state
round
message
metadata
```

DataStore 保存设置、FarmState 和必要的 RuntimeState。完整日志与诊断图片使用
应用内部文件目录。

## 17. 诊断文件

建议格式：

```text
files/diagnostics/
└── 20260727_124250_step8_harvest/
    ├── screenshot.png
    ├── annotated.png
    ├── context.json
    └── recent.log
```

`context.json` 包含：

- APK 版本；
- 设备和 Android 信息；
- 当前状态和循环；
- 分辨率；
- 模板名称、阈值和得分；
- OCR 原始文本；
- 最近一次 ROOT 命令结果；
- 时间计划；
- 错误堆栈。

诊断数量超过设置上限时删除最旧记录。

## 18. 项目目录

```text
WzryNC_Auto/
├── wzry_auto.py
├── assets/
├── tests/
├── docs/
│   └── APK_DEVELOPMENT_PLAN.md
└── android-app/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradlew
    ├── gradle/
    └── app/
        ├── build.gradle.kts
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── assets/templates/
            │   └── java/com/lili/wzryfarm/
            │       ├── MainActivity.kt
            │       ├── automation/
            │       ├── overlay/
            │       ├── service/
            │       ├── device/
            │       ├── vision/
            │       ├── ocr/
            │       ├── scheduling/
            │       ├── data/
            │       ├── diagnostics/
            │       └── ui/
            ├── test/
            └── androidTest/
```

Python 版本继续保留，作为：

- 行为基准；
- 时间计算参考；
- 模板裁剪工具；
- APK 识别结果对照；
- 故障复现工具。

## 19. 开发阶段

### 阶段 0：工具链和工程

任务：

- 安装 JDK；
- 安装 Android SDK；
- 建立 Gradle Wrapper；
- 创建 `android-app`；
- 配置 Kotlin、Compose、DataStore；
- 建立 debug 签名构建；
- 安装空 APK 到目标手机。

验收：

```text
./gradlew assembleDebug
APK 成功安装
MainActivity 正常打开
```

预计：0.5～1 天。

### 阶段 1：悬浮窗原型

任务：

- 悬浮窗权限引导；
- ForegroundService；
- 悬浮球；
- 拖动和吸边；
- 控制面板；
- 循环次数；
- 开始、暂停、停止按钮；
- 模拟日志和模拟倒计时。

验收：

- 在游戏上方连续显示 30 分钟；
- 不影响面板之外的游戏触控；
- 横屏位置正确；
- 展开、收起和拖动稳定；
- 通知停止按钮有效。

预计：2～3 天。

### 阶段 2：ROOT 与设备控制

任务：

- Magisk ROOT 自检；
- 文本和二进制命令执行器；
- 点击和滑动；
- 启动和停止游戏；
- 前台状态检测；
- 截图；
- 亮度保存与恢复；
- ROOT 测试页面。

验收：

- 连续执行 50 次截图；
- 测试点击和滑动准确；
- ROOT 拒绝时不崩溃；
- 命令超时可以终止；
- 亮度能够安全恢复。

预计：2～4 天。

### 阶段 3：无悬浮窗污染截图

任务：

- 截图前隐藏悬浮窗；
- 截图后恢复；
- 截图和点击互斥；
- 展开日志面板时仍可截图；
- 截图失败时恢复悬浮窗。

验收：

- 连续 100 张截图不出现悬浮球或面板；
- 截图失败后悬浮窗仍可操作；
- 点击永远基于最新有效截图。

预计：1～2 天。

### 阶段 4：OpenCV 模板匹配

任务：

- 集成 OpenCV Android；
- 迁移 17 个模板；
- ROI；
- 阈值；
- 有限尺度匹配；
- 分辨率配置；
- 标注诊断图；
- 使用现有截图进行回归。

验收：

- 关键模板匹配与 Python 结果基本一致；
- ROI 外错误目标不会触发点击；
- 失败时保存得分和标注图；
- 现有离线样本全部形成自动测试。

预计：3～5 天。

### 阶段 5：中文 OCR 和时间计算

任务：

- 集成 ML Kit 中文模型；
- OCR ROI 与预处理；
- 成熟时间解析；
- 收获信息解析；
- 迁移批次与浇水时间算法；
- OCR 安全上限。

验收：

- 至少收集 30 张真机 OCR 样本；
- 成熟时间识别率目标不低于 95%；
- 非法时间不会进入等待；
- 下一次浇水不得晚于 OCR 成熟时间。

预计：2～4 天。

### 阶段 6：单轮自动化

任务：

- 迁移 Python 步骤 1～10；
- 每步超时和重试；
- 点击后状态验证；
- 用户暂停和停止；
- 日志和诊断。

验收：

- 连续完成 10 个单轮测试；
- 目标成功率不低于 90%；
- 任一步骤失败都能定位；
- 用户停止后不再产生点击。

预计：4～7 天。

### 阶段 7：定时循环

任务：

- 指定次数和无限循环；
- 绝对时间倒计时；
- 等待期间退出游戏；
- 到点重新启动；
- 暂停和恢复；
- 状态持久化；
- 最后一轮完成逻辑。

验收：

- 设置 5 次后恰好完成 5 次；
- 等待倒计时不发生累计漂移；
- 暂停跨过目标时间后恢复会立即执行；
- 服务重建后能恢复循环进度；
- 不存在固定间隔循环入口。

预计：2～4 天。

### 阶段 8：稳定性和发布

任务：

- 连续运行测试；
- 日志轮转；
- 诊断清理；
- 连续失败熔断；
- Release 签名；
- APK 校验值；
- 安装和权限说明；
- 已知问题。

验收：

- 连续运行至少 8 小时；
- 不出现无限重试；
- 不出现旧截图点击；
- 不出现永久低亮度；
- 停止按钮始终有效；
- 形成可重复安装的签名 APK。

预计：3～5 天。

总体预计：集中开发约 3～5 周。第一版只针对当前目标设备时，可以通过
减少通用适配缩短周期。

## 20. 测试方案

### 20.1 JVM 单元测试

- 时间计算和跨天；
- 批次状态；
- 循环计数；
- OCR 文本解析；
- 坐标缩放；
- ROI；
- 状态转换；
- 重试与熔断；
- 绝对时间倒计时；
- 暂停恢复；
- 亮度恢复顺序。

### 20.2 Android 仪器测试

- DataStore；
- ForegroundService；
- 悬浮窗生命周期；
- Bitmap/OpenCV 转换；
- 文件日志；
- 诊断导出；
- 服务重建。

### 20.3 真机测试

- ROOT 允许、拒绝和撤销；
- 游戏冷启动和热启动；
- 横竖屏变化；
- 不同游戏加载速度；
- 启动弹窗和大厅弹窗；
- 农场未成熟；
- 农场成熟；
- 收获并重新种植；
- 截图卡顿；
- ROOT 命令超时；
- 用户手动干预；
- 用户暂停、继续和停止；
- 有限循环；
- 无限循环；
- 悬浮窗被关闭；
- APK 进程被系统终止；
- 亮度节点不存在或写入失败。

## 21. 性能要求

- 悬浮窗空闲时不持续高频刷新；
- 倒计时 UI 每秒更新，但绝对时间只在需要时重新计算；
- OpenCV 和 OCR 不在主线程运行；
- 每次只保留必要 Bitmap；
- OCR 只处理 ROI；
- 日志面板使用有上限的内存缓冲；
- 截图完成后及时释放 Mat、Bitmap 和原始字节；
- 等待期间不进行截图和模板匹配；
- 前台服务不得触发 ANR。

## 22. 主要风险

| 风险 | 应对 |
|---|---|
| 游戏更新导致模板失效 | 模板版本化、失败现场、独立阈值配置 |
| 悬浮窗污染截图 | 截图前隐藏并在 finally 中恢复 |
| ML Kit 对游戏字体识别不足 | ROI 放大预处理，必要时切换 ONNX OCR |
| ROOT 命令卡住 | 单命令超时、进程终止、连续失败熔断 |
| MIUI 回收服务 | 前台服务、常驻通知、用户主动启动 |
| 横竖屏或分辨率变化 | 运行前检查，不匹配时暂停 |
| 用户在自动点击时操作 | 操作锁、悬浮提示、点击后验证 |
| 亮度恢复失败 | 多通道恢复、紧急恢复按钮 |
| 无限循环失控 | 紧急停止、通知停止、最大连续失败次数 |
| 游戏账号风险 | 明确提示用户承担自动化使用风险 |

## 23. 发布产物

第一版完成时应交付：

- `wzry-farm-v1.0.0-arm64.apk`；
- Release 签名与安全备份说明；
- APK SHA-256；
- 安装说明；
- Magisk 授权说明；
- 悬浮窗权限说明；
- 使用说明；
- 已知问题；
- 模板更新说明；
- 开发和构建说明；
- 测试报告；
- 版本变更日志。

## 24. 开工顺序

严格按以下顺序实施：

1. 搭建 Android 工具链；
2. 创建可安装的空 APK；
3. 完成悬浮球与控制面板；
4. 完成 ROOT 命令测试页面；
5. 完成无悬浮窗污染的截图；
6. 迁移 OpenCV 模板匹配；
7. 集成中文 OCR；
8. 迁移单轮状态机；
9. 加入定时循环；
10. 完成异常恢复和连续运行测试；
11. 构建签名 APK。

第一个可验收里程碑为：

> APK 安装到目标手机后，能够通过主界面打开悬浮球；在王者荣耀上展开
> 控制面板；设置循环次数；显示模拟日志；测试 ROOT；隐藏悬浮窗完成截图；
> 执行测试点击和滑动；通过停止按钮立即终止并恢复亮度。

该里程碑通过后，才开始迁移完整游戏业务流程。
