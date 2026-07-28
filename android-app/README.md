# WzryNCAuto Android 工程

## 本地构建

仓库根目录已准备忽略版本控制的本地 JDK 与 Android SDK。执行：

```bash
cd android-app
JAVA_HOME=../.android-tools/jdk \
GRADLE_USER_HOME=../.android-tools/gradle-home \
./gradlew assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 正式版构建

正式签名默认从以下仓库外配置读取：

```text
/home/lili/.android-keys/wzryncauto-release.properties
```

也可以通过 Gradle 属性 `wzryReleaseSigningProperties` 或环境变量
`WZRY_RELEASE_SIGNING_PROPERTIES` 指定另一份配置文件。执行：

```bash
cd android-app
JAVA_HOME=../.android-tools/jdk \
ANDROID_HOME=../.android-tools/android-sdk \
GRADLE_USER_HOME=../.android-tools/gradle-home \
./gradlew assembleRelease
```

Release APK：

```text
app/build/outputs/apk/release/app-release.apk
```

密钥库与含密码的配置文件不得提交到版本库，必须一并加密备份。密钥丢失后，
将无法为已发布应用签署可覆盖安装的后续版本。

完整的版本升级、构建、验签及发布检查步骤见
[`../docs/RELEASE_BUILD_GUIDE.md`](../docs/RELEASE_BUILD_GUIDE.md)。

产品与技术范围见 `../docs/APK_DEVELOPMENT_PLAN.md`。
