# WzryNCAuto Android 工程

完整项目说明见仓库根目录的 [`README.md`](../README.md)。

## Debug 构建

准备 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0 后执行：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用 `gradlew.bat`。生成文件位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release 构建

正式签名默认从私有仓库内的以下配置读取：

```text
../release-signing/wzryncauto-release.properties
```

也可以通过 Gradle 属性 `wzryReleaseSigningProperties` 或环境变量
`WZRY_RELEASE_SIGNING_PROPERTIES` 指定其他位置。仓库必须保持私有，且仍需维护仓库外加密备份。

完整的版本升级、构建、验签及发布检查步骤见
[`../docs/RELEASE_BUILD_GUIDE.md`](../docs/RELEASE_BUILD_GUIDE.md)。

新电脑首次配置见
[`../docs/NEW_COMPUTER_RELEASE_BUILD.md`](../docs/NEW_COMPUTER_RELEASE_BUILD.md)。
