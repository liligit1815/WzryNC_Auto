# 新电脑 Release 构建指南

本文用于在更换电脑、重装系统或重新克隆私有仓库后，构建可覆盖升级现有安装版本的正式 APK。

## 1. 最重要的原则

- 必须继续使用仓库内同一份 `wzryncauto-release.p12` 私钥。
- 不要重新生成密钥；新密钥签署的 APK 无法直接覆盖升级现有正式版。
- 仓库必须始终保持私有，不得建立公开 Fork 或向无关人员开放访问。
- 不要在终端、截图、Issue、日志或聊天记录中输出 `wzryncauto-release.properties` 的内容。
- 仓库不是唯一备份。必须另外保存至少两份加密离线备份。

正式签名材料位于：

```text
release-signing/
├── wzryncauto-release.p12
└── wzryncauto-release.properties
```

Gradle 已默认读取该目录，不需要把密钥复制到用户主目录。

## 2. 新电脑所需软件

### Windows

安装以下软件：

1. Git。
2. JDK 17。
3. Android Studio，或独立 Android SDK Command-line Tools。
4. Android SDK Platform 35。
5. Android SDK Build-Tools 35.0.0。
6. Android SDK Platform-Tools（ADB）。

可在 Android Studio 的 SDK Manager 中安装 Platform 35、Build-Tools 35.0.0 和 Platform-Tools。

### Linux/macOS

安装 JDK 17、Android SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools，并确保 `git`、`java` 和 Android SDK 可用。

## 3. 克隆并核对私有仓库

```bash
git clone <私有仓库地址> WZRY_Auto
cd WZRY_Auto
```

确认以下文件都存在且大小非零：

```text
release-signing/wzryncauto-release.p12
release-signing/wzryncauto-release.properties
android-app/gradlew
android-app/gradlew.bat
android-app/app/build.gradle.kts
```

只检查文件是否存在，不要在共享终端中打印签名配置内容。

Windows PowerShell：

```powershell
Get-Item .\release-signing\wzryncauto-release.p12
Get-Item .\release-signing\wzryncauto-release.properties
```

Linux/macOS：

```bash
test -s release-signing/wzryncauto-release.p12
test -s release-signing/wzryncauto-release.properties
```

## 4. Windows 构建步骤

以下路径按常见安装位置示例填写；若实际安装位置不同，请改为真实路径。

```powershell
cd WZRY_Auto\android-app

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

.\gradlew.bat --version
.\gradlew.bat clean testDebugUnitTest assembleRelease
```

成功标志：

```text
BUILD SUCCESSFUL
```

正式 APK 输出位置：

```text
android-app\app\build\outputs\apk\release\app-release.apk
```

## 5. Linux/macOS 构建步骤

```bash
cd WZRY_Auto/android-app

export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

chmod +x gradlew
./gradlew --version
./gradlew clean testDebugUnitTest assembleRelease
```

正式 APK 输出位置：

```text
android-app/app/build/outputs/apk/release/app-release.apk
```

## 6. 必须执行的验签

构建成功不等于可以发布。每次都必须核对签名、包名和版本。

当前正式证书 SHA-256 指纹：

```text
25a5b61d4ac2d6e7966156c79b7417d0adcd647ca611b37ebb3cb6702882e65e
```

### Windows PowerShell

```powershell
$apk = ".\app\build\outputs\apk\release\app-release.apk"
$buildTools = "$env:ANDROID_HOME\build-tools\35.0.0"

& "$buildTools\apksigner.bat" verify --verbose --print-certs $apk
& "$buildTools\aapt2.exe" dump badging $apk
```

### Linux/macOS

```bash
apk=app/build/outputs/apk/release/app-release.apk
build_tools="$ANDROID_HOME/build-tools/35.0.0"

"$build_tools/apksigner" verify --verbose --print-certs "$apk"
"$build_tools/aapt2" dump badging "$apk"
```

必须确认：

- 输出包含 `Verifies`。
- `Number of signers` 为 `1`。
- 证书 SHA-256 指纹与本文完全一致。
- 包名为 `com.lispace.wzryncauto`。
- `versionCode` 和 `versionName` 与本次计划发布版本一致。
- 最低 SDK 仍符合项目要求。

## 7. 发布前递增版本

编辑：

```text
android-app/app/build.gradle.kts
```

更新：

```kotlin
versionCode = 23
versionName = "0.3.20"
```

示例值仅表示下一版本的可能写法。实际发布时：

- `versionCode` 必须大于所有已发布版本。
- `versionName` 应符合项目版本规划。
- 不得修改 `applicationId = "com.lispace.wzryncauto"`。

修改版本后重新运行测试、Release 构建和验签。

## 8. 安装验证

连接测试设备后执行：

```bash
adb install -r android-app/app/build/outputs/apk/release/app-release.apk
```

需要验证：

- 能覆盖安装旧正式版。
- 应用数据按预期保留。
- ROOT、悬浮窗、录屏、精确闹钟和电池优化权限流程正常。
- 至少完成一次目标设备单轮务农测试。

如果覆盖安装提示签名不一致，不要卸载生产设备上的旧版本来绕过问题；应先检查是否使用了错误私钥。

## 9. 常见故障

### 缺少正式签名配置

确认克隆结果包含：

```text
release-signing/wzryncauto-release.properties
```

如果文件缺失，先确认当前账号有权读取私有仓库的签名目录，再从加密离线备份恢复。不要创建新配置和新密钥冒充旧签名。

### 缺少正式签名密钥

确认以下文件存在：

```text
release-signing/wzryncauto-release.p12
```

必须从私有仓库或可信离线备份恢复原文件。

### 密码错误或别名不存在

说明 `.p12` 与 `.properties` 不是配套文件，或配置被修改。恢复同一备份批次中的两个文件，不要反复猜测或重新生成密钥。

### 找不到 Java

确认使用 JDK 17：

```bash
java -version
```

并检查 `JAVA_HOME` 是否指向 JDK 根目录，而不是 `bin` 目录。

### 找不到 Android SDK 或 Build-Tools

确认 `ANDROID_HOME` 正确，并检查 `build-tools/35.0.0`、`platforms/android-35` 和 `platform-tools` 是否已安装。

### Gradle 下载失败

首次构建需要下载 Gradle 和 Maven 依赖。检查网络、代理和证书设置，不要把本机 Gradle 缓存提交到仓库。

## 10. 私钥备份与仓库安全检查

每次换机或调整仓库权限后检查：

- [ ] 私有仓库没有被改为公开。
- [ ] 没有公开 Fork 或镜像。
- [ ] 只有必要人员拥有读取权限。
- [ ] 已启用账号双重验证。
- [ ] `.p12` 与 `.properties` 有至少两份加密离线备份。
- [ ] 离线备份已实际解密并比对过文件哈希。
- [ ] 发布 APK 已验签并记录 SHA-256。
- [ ] 发布版本已关联对应 Git 提交。

如果私钥仓库曾意外公开，应立即收回公开访问、审计克隆和下载记录，并将事件视为签名私钥泄露处理。对于已经依赖该证书覆盖升级的侧载应用，更换密钥可能导致无法直接升级，因此预防泄露比事后补救更重要。
