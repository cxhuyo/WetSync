# 构建与部署

## 1. 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 或 21 | AGP 8.7.3 要求 17+ |
| Android SDK Platform | **37** | `libxposed:api:102.0.0` 的 AAR 清单声明 `minCompiledSdkVersion=37` |
| Android SDK Build-Tools | 34+ | 任意近期版本即可 |
| Gradle | 8.13 | 仓库自带 wrapper，无需单独安装 |

安装 Platform 37：

```bash
sdkmanager "platforms;android-37" "build-tools;36.0.0"
```

## 2. 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/AndroidSdk

./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

国内网络如果卡在依赖下载，[`settings.gradle`](../settings.gradle) 里已经预置了阿里云镜像作为兜底，
必要时把镜像三行提到 `google()` / `mavenCentral()` 之前。

## 3. `compileSdk 37` 的两个坑

**坑一：SDK 里没有 Platform 37。** 报错形如
`Failed to find target with hash string 'android-37'`。装 `platforms;android-37` 即可。

**坑二：SDK 把 37 注册成了「扩展平台」。** 如果 `$ANDROID_HOME/platforms/package.xml` 里写的是

```xml
<path>platforms;android-37.0</path>
```

那么整数 `compileSdk 37` 会报 `Failed to find Platform SDK`。两种解法：

- 把该 `<path>` 改回 `platforms;android-37`（推荐，一次到位）；
- 或在 `app/build.gradle` 里写 `compileSdkPreview "android-37.0"`。

## 4. 签名

仓库默认用 **debug 签名**（`signingConfig signingConfigs.debug`），`assembleRelease` 出来的包可以直接
`adb install`，方便自用与内测。要换成自己的签名，在仓库根目录建 `keystore.properties`：

```properties
storeFile=release.jks
storePassword=******
keyAlias=wtsync
keyPassword=******
```

`storeFile` 相对仓库根目录解析。该文件已在 `.gitignore` 中，不会误提交。
存在这个文件时，`release` 自动切到自定义签名；不存在则回退 debug，fork 下来不用改任何东西。

## 5. 版本号

两处必须同步改，否则 LSPosed 管理器显示的版本会与 APK 实际版本不一致：

- `app/build.gradle` → `versionCode` / `versionName`
- `app/src/main/resources/META-INF/xposed/module.prop` → `version` / `versionCode`

## 6. 真机部署

```bash
adb install -r app/build/outputs/apk/release/app-release.apk

# 重启宿主，让 LSPosed 重新加载模块（等价于 LSPosed 里的「重启应用」）
adb shell su -c 'am force-stop com.oplus.keyboard'
```

然后在 LSPosed 管理器里：

1. 模块 → 启用 **WetSync**
2. 作用域 → 只勾选 `com.oplus.keyboard`
3. 打开任意输入框，弹出小布输入法即生效

首次生效时 `module.log` 会出现：

```
event=module_loaded process=com.oplus.keyboard
event=package_ready
event=identity_ok uin=<uin>
event=loop_start
```

## 7. 常见构建错误

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to find Platform SDK with path: platforms;android-37` | 扩展平台注册问题 | 见第 3 节 |
| `Dependency 'io.github.libxposed:api:102.0.0' not found` | 仓库里没有该坐标 | 确认 `settings.gradle` 含 `google()` / `mavenCentral()`，或临时加 `mavenLocal()` |
| `Android Gradle plugin requires Java 17` | JDK 版本过低 | 切到 JDK 17/21 |
| `minCompiledSdkVersion 37 ... compileSdk 35` | `compileSdk` 被改小 | 改回 37 |
| `Duplicate class androidx.*` | 引入了 androidx 运行期依赖 | 删掉，宿主的 androidx 被 R8 重名化，只能反射 |
