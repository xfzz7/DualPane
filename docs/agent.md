# 把「双屏」装到电视上

给会在电脑上执行命令的 Agent。先读完这一页，再动手。需要用户在电视上点允许时，停下来告诉他，不要自己猜过去。

不要向电视发送遥控器按键。启动应用用下面的 `am start`，那不是按键。

应用 id 是 `tv.naughty.dual`，列表里的名字是「双屏」，版本是 1.0。

## 电脑上要有

- JDK 17
- Android SDK（含 `platform-tools`，也就是 `adb`）
- 能访问网络，第一次编译要下载 Gradle 和依赖

SDK 路径常见位置：

- macOS：`~/Library/Android/sdk`
- Linux：`~/Android/Sdk`
- Windows：`%LOCALAPPDATA%\Android\Sdk`

已经设置过 `ANDROID_HOME` 的，就用那个目录。

## 电视上要先打开

开发者选项里的网络调试，有的机器叫 ADB 调试或网络 ADB。打开之后电视上会有一个地址，形如 `192.168.1.20:5555`。没有这个地址就问用户，不要编一个。

电脑和电视要在同一个局域网。

## 编译

在仓库根目录写 `local.properties`。这个文件不要提交：

```
sdk.dir=/绝对路径/Android/sdk
```

macOS、Linux：

```
./gradlew :app:assembleDebug
```

Windows：

```
gradlew.bat :app:assembleDebug
```

安装包在 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装

把 `<电视地址>` 换成用户给的地址。

```
adb connect <电视地址>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.naughty.dual/.MainActivity
```

`adb devices` 里这台电视是 `unauthorized` 时，请用户在电视上点允许，然后再执行一次 `adb connect`。

盖不上去时先卸掉再装。签名不一致，或者电视上已有的开发包 versionCode 比 1 更高，都会这样：

```
adb uninstall tv.naughty.dual
adb install app/build/outputs/apk/debug/app-debug.apk
```

`INSTALL_FAILED_VERSION_DOWNGRADE` 也可以不卸载，直接：

```
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

`-d` 允许版本号往下降。用户电视上如果装着更早的开发包，用这一条即可。

## 验收

把下面三条的原始输出原样贴给用户，不要改写成「已安装」。

```
adb shell pm path tv.naughty.dual
adb shell dumpsys package tv.naughty.dual | grep versionName
adb shell "dumpsys window | grep mCurrentFocus"
```

通过的样子：

- `pm path` 有一行 `package:` 路径
- `versionName=1.0`
- `mCurrentFocus` 里有 `tv.naughty.dual`

第三条要在 `am start` 之后立刻看。焦点不在「双屏」上，就再执行一次 `am start`，然后重新贴 `mCurrentFocus`。

## 失败时

| 看到的 | 怎么处理 |
|---|---|
| `unable to connect`、`offline` | 核对地址和端口，确认电视开着网络调试，电脑和电视在同一局域网 |
| `unauthorized` | 请用户在电视上点允许，再 `adb connect` 一次 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall tv.naughty.dual` 后再装 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `adb install -r -d`，或先卸载再装 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 请用户在电视上腾出空间 |
| 找不到 `adb` 或 `java` | 先装 JDK 17 和 Android SDK platform-tools，不要跳过编译 |
