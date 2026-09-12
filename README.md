# HarleyApp Android

Harley生活助手是一款使用Kotlin和Jetpack Compose开发的本地Android应用，包含首页设备状态、
自定义网站收藏轮播、电子书、英语单词、伙伴成长、记账、运动监督、快捷应用、微信相关辅助和本机清理等功能。

## 仓库中包含什么

- `app/src/main`：应用源码、资源、Manifest与服务配置。
- `app/src/test`：不依赖手机的本地单元测试。
- `app/src/androidTest`：需要连接Android设备的实机测试。
- `gradle/wrapper`、`gradlew`、`gradlew.bat`：固定版本的Gradle Wrapper，必须保留。
- `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`：工程构建配置。

`local.properties`、`.gradle`、`.idea`、`.kotlin`、各模块的`build`目录和APK不会提交。
这些内容包含本机路径或可重新生成的缓存，不是跨电脑运行所需源码。

## 另一台电脑首次运行

1. 安装Android Studio，并确保其内置或配置的Gradle JDK为JDK 25。

2. 使用Git克隆本仓库：

   ```bash
   git clone https://github.com/halibaduoxiansheng/HarleyApp_Android.git
   ```

3. 在Android Studio中选择 **Open**，打开克隆后的仓库根目录。
4. 等待Gradle Sync完成。首次同步会按工程配置安装或提示安装所需Android SDK组件，
   并在本机生成不提交的`local.properties`。
5. 在手机上开启开发者选项和USB调试，连接并授权电脑，然后在Android Studio设备列表中选择手机。
6. 点击 **Run 'app'** 安装并启动应用。

工程当前最低支持Android 8.0（API 26），使用`compileSdk 37`和`targetSdk 37`。

## 命令行验证

Windows PowerShell中把`JAVA_HOME`改为本机Android Studio的JBR目录后执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --no-configuration-cache
.\gradlew.bat lintDebug --no-configuration-cache
.\gradlew.bat assembleDebug --no-configuration-cache
```

Debug APK生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 本地数据说明

账目、网站、伙伴经验、任务完成状态和应用设置保存在当前手机的应用私有存储中，
不会随Git仓库同步。换电脑不会丢失手机上已经安装应用的数据；换手机时则需要重新配置。
