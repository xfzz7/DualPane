<div align="center">

<img src="app/src/main/res/drawable-xhdpi/dual_icon.png" width="96" alt="双屏">

# 双屏

**一块屏幕，两路画面。声音也分开。**

一边放跟练，一边放纪录片。手机投过来，或者直接播电视里的片子。

<img alt="Android 8.0 及以上" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">
<img alt="版本 1.0" src="https://img.shields.io/badge/%E7%89%88%E6%9C%AC-1.0-2997ff?style=flat-square">

[![在电视上同时播放两路，再收成画中画](docs/demo.webp)](docs/demo.mp4)

实机录屏。点开可以看完整片段。

</div>

这是装在 Android 电视上的播放器。左右各一路，各有各的进度、音量和硬解/软解。手机在投屏列表里会看到两个设备：「双屏左」和「双屏右」。这是普通的 DLNA，不是 Miracast，也不是乐播或 Chromecast。

## 打开之后

左右各有一块空画面。中间那条短白条用来分宽窄，上次拉过的比例会留下来。有画面进来之后，提示字会让开。

![两块空画面，中间是白色分隔条](docs/empty.webp)

## 把片子送进来

手机和电视连在同一个局域网里，让「双屏」保持打开。在视频 App 的投屏列表里选择「双屏左」或「双屏右」。投到哪一边，片子就出现在哪一边，焦点也会跟过去。两路可以同时放，声音也同时出。

也可以在电视上长按确认键，从菜单里打开本机视频。第一次会向系统要存储权限。能列出来的有 mp4、m4v、mov、mkv、webm、ts、3gp。

每一边都能单独选硬解或软解。还没出画面时，下面有一行灰色的「正在打开」。

![左边和右边各放一路](docs/split.webp)

## 遥控器

焦点在哪一边，左右键和上下键就只动那一边。遥控器上的音量键始终调电视自己的音量，不拦下来。

![遥控器在画面上和在白条上的用法](docs/remote.webp)

还没有片子时，按确认也会打开菜单。返回不会立刻离开：底部先出现「再按一次返回退出」，不再按就会自己淡掉。

忘了怎么操作时，菜单里有「使用说明」，可以再看一遍引导。

## 画中画

菜单里选「画中画」之后，左边铺满屏幕，右边收成角落里的小窗。小窗可以直接聚焦：左右键快进、快退，上下键调这一路音量，确认键播放或暂停。

这时菜单键只在大屏和小窗之间移动焦点，不改变布局。焦点在小窗时，返回先回到大屏。

「画中画设置」里可以改小窗大小（小、中、大）和位置（右下、右上、左下、左上）。小窗宽高比跟着右边的片子走。左右对换会交换两路的内容和进度，左边仍然是大屏，右边仍然是小窗。想把右边的内容放大，用这个对换，不会自动换位。

退出时选「分屏」，左右比例恢复成进入画中画之前。

![左边铺满，右边是小窗](docs/pip.webp)

## 第一次打开

第一次打开会在实际画面上走一遍，依次说明左右两路、当前这一边、中间白条、确认键，最后切到画中画给你看。确认或向右继续，向左或返回回到上一步。

## 安装

### 直接装发布包

从 [Releases](https://github.com/xfzz7/DualPane/releases) 下载 `DualPane-1.0.apk`。

```
adb install -r DualPane-1.0.apk
adb shell am start -n io.github.xfzz7.dualpane/.MainActivity
```

### 让 Agent 装（推荐）

把下面这段发给能在电脑上运行命令的 Agent（WorkBuddy、豆包电脑版、千问电脑版、Trae、Claude Code、Codex 等）：

```
请帮我把开源电视应用「双屏」装到我的 Android 电视上。
电视地址：<电视IP>:5555

安装说明在仓库的 docs/agent.md。
请先完整读完安装说明再动手，然后在我的电脑上亲自运行命令，不要只把步骤告诉我。
需要我在电视上点允许时提醒我。不要向电视发送遥控器按键。

最后按说明里的「验收」，把这几条命令的原始输出原样贴给我：
- adb shell pm path io.github.xfzz7.dualpane
- adb shell dumpsys package io.github.xfzz7.dualpane | grep versionName
- 启动之后的 adb shell "dumpsys window | grep mCurrentFocus"
```

手机上的豆包、网页版聊天只会把步骤告诉你，不会执行。要用能跑命令的电脑版。Agent 说装好了，看它贴出的 `mCurrentFocus` 里有没有 `io.github.xfzz7.dualpane`，`versionName` 是不是 `1.0`。

完整步骤、失败时怎么处理，写在 [docs/agent.md](docs/agent.md)。

### 自己编译再装

需要 JDK 17、Android SDK，以及 `adb`。电视先打开开发者选项里的网络调试（有的机器叫 ADB 调试），地址形如 `192.168.1.20:5555`。

在仓库根目录放 `local.properties`，这个文件只属于这台电脑，不要提交：

```
sdk.dir=/绝对路径/Android/sdk
```

```
./gradlew :app:assembleDebug
adb connect 192.168.1.20:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.xfzz7.dualpane/.MainActivity
```

第一次编译会下载 Gradle 和依赖，需要网络。Windows 把 `./gradlew` 换成 `gradlew.bat`，后面的 `adb` 命令一样。安装包在 `app/build/outputs/apk/debug/app-debug.apk`。电视弹出调试授权时，要在电视上点允许，然后再连一次。

应用 id 是 `io.github.xfzz7.dualpane`，列表里的名字是「双屏」。盖不上去时（签名不一致，或版本号比电视上已有的包更低），先卸掉再装：

```
adb uninstall io.github.xfzz7.dualpane
adb install app/build/outputs/apk/debug/app-debug.apk
```

`minSdk` 26，`targetSdk` 28，`compileSdk` 34，播放使用 Media3 1.4.1，界面是 Java。启动图标的源文件在 `design/launcher`，打进包里的是 `app/src/main/res/drawable-xhdpi`。

同一局域网里的设备都能搜到「双屏左」和「双屏右」，中间没有账号。适合家里用。

## 测过的环境

下面这些是在这台电视上做过的，当时能装上、能打开，没有闪退：

- 电视：小米 MiTV_ASTP0
- 系统：Android 9（API 28），分辨率 1920×1080
- 应用：双屏 1.0，debug 包
- 安装：同一局域网里的电脑，用 ADB 网络调试装上并启动

在这台机器上确认过：左右两路可以同时播放；暂停、快进、两路分开调音量、停止后再播都正常；画中画时左边全屏、右边是小窗，两路画面都在。遥控器的音量键调的是电视总音量。手机视频 App 投到「双屏左」「双屏右」在这台电视上用过，可以投上来。别的品牌、别的 Android 版本还没有按同样的项目逐项看过。

## 画面出处

上面的实机画面来自 Blender 基金会以 [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) 发布的开放电影预告：[Sintel](https://durian.blender.org/) 与 [Big Buck Bunny](https://peach.blender.org/)。片子本身不在这个仓库里。

## 许可

Copyright 2026 xfzz。代码以 [Apache-2.0](LICENSE) 发布。
