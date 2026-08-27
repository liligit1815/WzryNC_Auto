# 王者荣耀自动务农APP

## 项目介绍

WzryNCAuto 是一款在已取得 ROOT 权限的 Android 设备上运行的王者荣耀农场自动化工具，通过本机截图、中文文字识别、安全触控与定时唤醒完成重复务农流程。

当前版本为 `0.3.19`（`versionCode 22`），应用包名为 `com.lispace.wzryncauto`。

## 功能特性

- 引导并检查通知、悬浮窗、精确闹钟、电池优化、厂商自启动与 ROOT 权限。
- 通过悬浮球设置 1–99 轮或无限循环，支持开始、暂停、继续、停止和紧急停止。
- 使用 ROOT 启动/停止王者荣耀，执行点击、滑动、返回、屏幕唤醒和非密码锁屏解除。
- 自动处理登录页、更新或广告弹窗、大厅、王者农场入口、站位刷新和角色移动。
- 连续识别“一键务农”文字与坐标，执行游戏内的一键收获、播种和浇水。
- 解析收获经验、作物名称与数量，并识别土地状态和成熟时间。
- 根据 5、60、480、960、1920 分钟作物周期计算后续浇水或成熟节点。
- 使用系统精确闹钟和应用内计时双保险，并在重启、升级、时间或时区变化后恢复任务。
- 在不可逆点击前持久化检查点，降低进程异常后重复点击的风险。
- 支持保持亮度、系统最低亮度和 ROOT 极低亮度，并在停止或异常后恢复原亮度。
- 保存有限数量的运行日志、OCR 样本和失败现场，便于定位识别问题。

兼容性会受游戏版本、活动弹窗、分辨率、系统字体、ROM 和 ROOT 实现影响，不承诺所有设备或后续游戏版本均可直接运行。

## 技术架构

- Kotlin `2.0.21`、Android Gradle Plugin `8.7.3`、Gradle `8.9`、JDK 17。
- Jetpack Compose + Material 3 构建竖屏主界面，前台 `OverlayService` 提供悬浮控制与任务编排。
- 最低 Android 9（API 28），目标 API 35，仅打包 `arm64-v8a`。
- ML Kit 中文文字识别负责页面文字、收获信息和成熟时间识别。
- MediaProjection JPEG 屏幕流为快速截图通道，ROOT `screencap -p` PNG 为无损复核与回退通道。
- `su -c`、Android shell input 和 Activity Manager 负责游戏启停、触控、前台检查、唤醒与亮度控制。
- SharedPreferences 保存运行检查点和不可逆动作边界，DataStore 保存作物批次与排程状态。
- AlarmManager、BroadcastReceiver、wake lock 和前台服务共同完成跨轮次唤醒与异常恢复。

```text
MainActivity（权限、自检、MediaProjection 授权）
        │
        ├── WzryHomeScreen / Theme
        └── OverlayService（悬浮 UI 与总编排）
                ├── EnterFarmAutomation（启动游戏并进入农场）
                ├── FarmActionAutomation（移动、务农、收获、土地识别）
                ├── RootAutomationRuntime
                │       ├── ROOT 设备控制与安全触控
                │       ├── MediaProjection / ROOT 截图
                │       ├── ML Kit OCR
                │       └── 弹窗与开始按钮视觉匹配
                ├── RuntimeStateStore / FarmStateStore
                ├── AutomationAlarmScheduler / Receiver
                └── BrightnessController / BrightnessLeaseStore
```

## 目录结构

```text
WZRY_Auto/
├── android-app/                 # Android APK 工程
│   ├── app/src/main/            # Kotlin 主源码、Manifest、资源与运行模板
│   ├── app/src/test/            # JVM 单元测试
│   ├── app/src/androidTest/     # Android 仪器测试与测试输入
│   └── gradle/wrapper/          # 固定 Gradle 版本的 Wrapper
├── docs/
│   ├── NEW_COMPUTER_RELEASE_BUILD.md # 新电脑首次构建指南
│   └── RELEASE_BUILD_GUIDE.md        # Release 构建、签名和验签说明
├── release-signing/             # 私有仓库内的正式签名材料
├── scripts/                     # Android OCR 样本导出与准确率统计工具
├── .gitignore
└── README.md
```

## 使用说明

### 环境要求

- Android 9 或更高版本。
- `arm64-v8a` 设备。
- Magisk 或其他可向应用提供 `su` 的 ROOT 环境。
- 构建需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。
- 安装、仪器测试和设备排障需要 ADB。

### 构建 Debug APK

Windows PowerShell：

```powershell
cd android-app
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
./gradlew.bat testDebugUnitTest assembleDebug
```

Linux/macOS：

```bash
cd android-app
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleDebug
```

Debug APK 输出到 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

私有仓库已包含正式签名材料，克隆后可直接构建 Release。新电脑首次配置见 [新电脑 Release 构建指南](docs/NEW_COMPUTER_RELEASE_BUILD.md)，完整验签与发布步骤见 [Release 构建指南](docs/RELEASE_BUILD_GUIDE.md)。

### 安装与运行

1. 使用 ADB 安装自行构建并验签的 APK：`adb install -r app-debug.apk`。
2. 打开应用，按顺序授予通知、悬浮窗、精确闹钟、忽略电池优化等权限，并在 ROOT 管理器中授权。
3. 打开悬浮控制。允许录屏可启用 MediaProjection 快速截图；拒绝后应用会尝试使用 ROOT 截图。
4. 启动王者荣耀，在悬浮面板中选择轮次或无限模式后开始任务。
5. 首次运行建议选择单轮、保持设备解锁并观察完整流程；确认当前游戏版本和分辨率适配后再使用多轮模式。

### 测试

```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # 需要连接 Android 设备
```

仓库清理后已验证 157 项 Android JVM 测试全部通过，Debug 与正式签名 Release APK 均打包成功。仪器测试和真机端到端测试仍需连接目标设备执行。

## 注意事项

- 本项目会自动操作网络游戏。使用前应自行确认并遵守游戏用户协议、平台规则及当地法律；账号处罚等后果由使用者自行承担。
- ROOT 和系统级命令可能影响设备安全与稳定性。不要在不了解风险的设备上授权，也不要向第三方提供 ROOT、锁屏或签名凭据。
- 多轮无人值守任务无法绕过密码锁屏；建议保持屏幕常亮，或在单轮启动前手动解锁。
- 游戏 UI、活动弹窗或分辨率变化可能导致 OCR/视觉识别失效。出现异常点击、连续失败或版本更新后应立即停止并重新测试。
- 私有仓库内含正式签名私钥和密码配置；不得把仓库改为公开、创建公开 Fork、上传到公共制品或向无关人员授权。
- 即使仓库内已有签名材料，仍必须保留至少两份加密离线备份，避免仓库损坏或账号失效后无法继续发布。
- 当前没有证据证明 v0.3.19 已完成长时间真机验收；JVM 测试通过不能替代目标设备验证。

## 开源协议

仓库当前未提供 `LICENSE` 文件，因此尚未明确授予复制、修改、分发或商业使用权。在维护者选择并添加正式开源许可证前，本项目不能按常见开源许可直接复用。
