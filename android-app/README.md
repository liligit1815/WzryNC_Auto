# 王者农场助手 Android 工程

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

产品与技术范围见 `../docs/APK_DEVELOPMENT_PLAN.md`。
