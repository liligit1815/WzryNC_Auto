# WzryNCAuto Release 构建指导

本文用于指导后续构建可正式发布、可覆盖升级的 WzryNCAuto APK。

## 1. 重要原则

- 应用包名固定为 `com.lispace.wzryncauto`。
- 后续版本必须继续使用现有正式密钥签名。
- 不要重新生成同名密钥。使用新密钥签名的 APK 无法直接覆盖已安装的正式版。
- 密钥库和密码配置不能提交到 Git，也不能放进 APK。

当前正式签名证书的 SHA-256 指纹为：

```text
25a5b61d4ac2d6e7966156c79b7417d0adcd647ca611b37ebb3cb6702882e65e
```

每次正式发布前均应核对该指纹。

## 2. 必须备份的签名文件

当前构建机使用以下两个文件：

```text
/home/lili/.android-keys/wzryncauto-release.p12
/home/lili/.android-keys/wzryncauto-release.properties
```

其中：

- `wzryncauto-release.p12` 是正式签名私钥。
- `wzryncauto-release.properties` 保存密钥位置、别名和密码，属于敏感明文配置。

两者必须一起加密备份。建议保留至少两份离线备份，并分别存放。恢复文件后应限制访问权限：

```bash
chmod 600 /home/lili/.android-keys/wzryncauto-release.p12
chmod 600 /home/lili/.android-keys/wzryncauto-release.properties
```

## 3. 签名配置格式

默认签名配置文件为：

```text
/home/lili/.android-keys/wzryncauto-release.properties
```

其格式如下。尖括号内容应替换为实际值，不要将真实密码写入本文或版本库：

```properties
storeFile=/home/lili/.android-keys/wzryncauto-release.p12
storePassword=<密钥库密码>
keyAlias=wzryncauto
keyPassword=<密钥密码>
```

如果在其他电脑或其他路径构建，可以使用以下任一方式指定配置文件：

```bash
export WZRY_RELEASE_SIGNING_PROPERTIES=/安全路径/wzryncauto-release.properties
```

或者在 Gradle 命令中指定：

```bash
./gradlew assembleRelease \
  -PwzryReleaseSigningProperties=/安全路径/wzryncauto-release.properties
```

## 4. 发布前更新版本号

编辑：

```text
android-app/app/build.gradle.kts
```

更新 `defaultConfig` 中的：

```kotlin
versionCode = 2
versionName = "0.2.0"
```

版本规则：

- `versionCode` 必须是正整数，并且每次发布都必须大于以前发布过的值。
- `versionName` 是显示给用户的版本号，可按项目版本规划调整。
- 不要修改 `applicationId = "com.lispace.wzryncauto"`，否则会被系统视为另一个应用。

## 5. 构建 Release APK

在仓库根目录执行：

```bash
cd android-app
JAVA_HOME=../.android-tools/jdk \
ANDROID_HOME=../.android-tools/android-sdk \
GRADLE_USER_HOME=../.android-tools/gradle-home \
./gradlew clean testDebugUnitTest assembleRelease
```

成功时终端会显示：

```text
BUILD SUCCESSFUL
```

生成的正式 APK 位于：

```text
android-app/app/build/outputs/apk/release/app-release.apk
```

构建脚本在缺少正式签名配置时会终止 Release 任务，防止误生成未使用正式密钥的发布包。

## 6. 验证 APK

### 6.1 验证签名和证书指纹

在 `android-app` 目录执行：

```bash
JAVA_HOME=../.android-tools/jdk \
PATH=../.android-tools/jdk/bin:$PATH \
../.android-tools/android-sdk/build-tools/35.0.0/apksigner \
verify --verbose --print-certs \
app/build/outputs/apk/release/app-release.apk
```

需要确认：

- 输出包含 `Verifies`。
- `Number of signers` 为 `1`。
- SHA-256 指纹与本文第 1 节完全一致。

### 6.2 验证包名和版本

```bash
../.android-tools/android-sdk/build-tools/35.0.0/aapt2 \
dump badging app/build/outputs/apk/release/app-release.apk
```

需要确认输出中的：

- 包名是 `com.lispace.wzryncauto`。
- `versionCode` 和 `versionName` 是本次计划发布的版本。

### 6.3 可选：安装到测试设备

连接测试设备后执行：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` 表示覆盖升级并保留应用数据。安装前应确认测试设备上的旧版本具有相同包名和相同正式签名。

## 7. 发布检查清单

- [ ] 功能测试和自动化测试通过。
- [ ] `versionCode` 已递增。
- [ ] `versionName` 正确。
- [ ] 包名仍为 `com.lispace.wzryncauto`。
- [ ] Release 构建成功。
- [ ] APK 签名验证成功。
- [ ] SHA-256 证书指纹与本文一致。
- [ ] 已在目标分辨率设备上完成实际测试。
- [ ] 保存一份本次发布 APK，并记录对应版本号和源代码提交。

## 8. 常见问题

### 提示缺少正式签名配置

确认 `wzryncauto-release.properties` 存在，或使用环境变量/Gradle 属性指定其路径；同时确认配置中的 `storeFile` 指向已经恢复的 `.p12` 文件。

### 提示密码错误或密钥别名不存在

不要重新生成密钥。应从可靠备份恢复配套的 `.p12` 和 `.properties` 文件。

### APK 无法覆盖安装

优先检查：

1. 新旧 APK 的包名是否相同。
2. 新 APK 的 `versionCode` 是否更大。
3. 新旧 APK 是否由同一份正式密钥签名。

如果设备上安装的是旧包名或 debug 签名版本，需要先备份必要数据，再卸载旧应用并安装正式版；卸载通常会清除该应用的本地数据。
