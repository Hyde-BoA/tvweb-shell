# 电视网页（tvweb-shell）

Android TV / 小米电视上的「网页壳」应用：启动即全屏加载指定网址，用遥控器当鼠标操作，专为网页直播/视频站点适配。

## 下载

已编译好的 APK 在 [Releases](https://github.com/Hyde-BoA/tvweb-shell/releases/latest) 里，`tvweb-shell.apk` 直接用 U 盘拷到电视安装。
想自己编译就克隆仓库后跑 `bash build.sh`（不依赖 Android Studio）。

- 包名：`com.tvweb.shell`
- 体积：约 100KB（纯原生，不含 AndroidX）
- 最低系统：Android 5.0（API 21）
- 无后台服务、无多余权限，只有 INTERNET / ACCESS_NETWORK_STATE

## 遥控器操作

| 按键 | 作用 |
|---|---|
| 方向键 | 移动屏幕上的白色箭头光标（按住会加速） |
| OK / 确认 | 在光标位置点击（网页和右上角按钮都能点） |
| 返回 | 网页能后退就后退；不能再后退时连按两次退出应用 |
| 菜单键 | 打开设置面板（小米遥控器没有此键，用右上角「设置」按钮） |

光标移到屏幕边缘时，页面会自动翻页滚动（优先滚动光标下方的可滚动容器）。

## 设置面板

点右上角「设置」（或用遥控器的菜单键）打开，里面依次是：

- **网址输入**：改完点「保存并加载」，会同时记住这条网址
- **历史网址**：保存过的地址按最近使用排序，最多 12 条。选中任意一条按 OK 直接打开；每行右侧的 `×` 删掉单条
- **清空历史**：第一次点变成「再点一次确认清空」，4 秒内再点才真的清空，防止误触
- **检查更新**：见下
- **WebView 能力**：实时探测 MSE / H264 / HEVC 支持情况

## 应用内更新

点「检查更新」会去 GitHub 的 Releases 拉最新版本号（`v1.1` 这种格式会自动拆成数字比较）：

1. 有新版本 → 按钮变「下载并安装」，APK 下到应用缓存目录
2. 下载完自动调起系统安装器（`content://` URI + `REQUEST_INSTALL_PACKAGES`）
3. 如果系统拦着，会跳到「安装未知应用」开关页，打开后再点一次「立即安装」

电视访问 `api.github.com` 可能不通，失败时状态栏会写明原因，可多点几次或换网络。

> **签名必须一致**：自更新靠同一个签名覆盖安装。本项目的 APK 由 `tools/debug.keystore` 签名，
> 这个文件**不在仓库里**（也不该进仓库）。请自己备份它，换机器重新生成的话，新 APK 会因签名不同装不上去，
> 只能先卸载旧版。

## 直播相关的适配

在 `MainActivity.configureWebView()` 里已经做好的几件事：

- `setMediaPlaybackRequiresUserGesture(false)`：直播免用户手势自动起播
- `onShowCustomView` / `onHideCustomView`：接管播放器全屏，避免视频被裁切
- `FLAG_KEEP_SCREEN_ON`：播放时屏幕不熄灭
- `MIXED_CONTENT_ALWAYS_ALLOW`：允许 https 页面里加载 http 流
- 视频全屏时隐藏系统 UI（沉浸模式）

## 设置面板里能看到什么

点右上角「设置」，除了改网址，底部会显示一行 `WebView 能力检测`，内容是执行 JS 探测出来的：

```
mse:true   是否支持 MSE（hls.js / flv.js 的前提）
hls:maybe  原生 HLS 支持情况（Android WebView 基本是空）
h264:probably  H.264 解码
hevc:...   H.265 解码
```

配合 `adb shell dumpsys webviewupdate` 的版本号，就能判断直播为什么播不出来。

## 编译

项目不用 Gradle，`build.sh` 直接调 aapt2 / javac / d8 / zipalign / apksigner：

```bash
bash build.sh
# 产物：dist/tvweb-shell.apk
```

依赖已经放在 `tools/` 下（便携版 JDK 17 + Android build-tools 34 + platform android-34），
换机器时删掉 `tools/jdk-*`、`tools/sdk`、`tools/*.zip` 重新下载即可。

## 安装到电视

```bash
# 电视打开「开发者选项 → USB 调试 / 网络调试」，与电脑同一局域网
adb connect 192.168.1.x:5555
adb install -r dist/tvweb-shell.apk
```

也可以把 apk 拷到 U 盘插电视安装（设置里要打开「允许安装未知来源应用」）。

启动并直接指定网址（不用进设置）：

```bash
adb shell am start -n com.tvweb.shell/.MainActivity -e url "https://example.com/live"
```

卸载：

```bash
adb uninstall com.tvweb.shell
```

## 换网址

1. 应用内右上角「设置」→ 输入网址 → 保存并加载（会记住，下次启动直接进）
2. 或 `adb shell am start -n com.tvweb.shell/.MainActivity -e url "..."`
3. 或改 `MainActivity.DEFAULT_URL` 重新编译

## 播放卡顿排查

同一个网页、同一台电视，Chrome 流畅而本应用卡，先别猜，**打开诊断浮层看数字**：
设置里把「诊断：关」切成「诊断：开」，左上角会出现一个绿色浮层：

```
画面 1920×1080  播放
缓冲 +11.8s
丢帧 3/900
卡顿 0 次
分片 236ms
```

对照判断：

| 现象 | 结论 |
|---|---|
| 缓冲稳定在 5s 以上、卡顿次数不涨 | 不是网络，问题在渲染 |
| 卡顿次数持续上涨、缓冲掉到 0.5s 以下 | 在等分片，是网络或播放器策略 |
| 丢帧比例 > 2% 而缓冲充足 | 解码/合成瓶颈 |
| 分片耗时长期 > 1000ms | CDN 或线路问题 |

另外设置里还有「UA：标准/手机/桌面」三档，切一档会自动重载页面，
用来验证站点是否按 UA 下发了不同的播放策略。

已知的内核情况：小米电视的系统 WebView 由 `com.android.webview` 提供，
可以用下面的命令确认版本，116 以上就属于比较新的内核，不是卡顿的主因：

```bash
adb shell dumpsys webviewupdate | grep -i "Current WebView package"
```

## 已知限制

- WebView 不是 Chrome。电视上装了新版 Chrome，不代表 WebView 内核也新。用
  `adb shell dumpsys webviewupdate` 查 `Current WebView package` 的版本号。
- 如果站点用 DRM（Widevine）加密，电视 WebView 可能拿不到证书，表现为黑屏有声或直接报错。
- 本应用只是浏览器壳，不解析、不代理、不下载任何视频流。
