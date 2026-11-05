# DSH Web 安卓壳

用系统 WebView 直接加载 **DeepSeek Harness 官方 Web 版本**（`dsh web` 提供的浏览器 UI）的极简安卓 App。

壳里没有聊天逻辑，会话、工具、审批、文件全部来自官方 Web UI —— 官方怎么迭代，手机上就是最新版，不需要跟着重新发版。

> 非官方项目，与 DeepSeek 官方无关；DeepSeek / DSH 的名称与标识归其所有者。本仓库不包含任何官方代码或凭据。

## 功能

- **直接加载官方 Web 版**：无自研聊天界面，功能随官方更新
- **一次连接，之后自动重连**：首次粘贴 `dsh web` 启动日志里的完整链接（含一次性 `?token=`），换取 30 天会话 Cookie 后记住干净地址
- **全屏体验**：无浏览器地址栏，深色/浅色跟随系统
- **手机该有的行为**：硬件返回、聊天附件上传、带会话 Cookie 的下载、JS 弹窗
- **出错兜底**：断网 / 401 / 证书错误有说明页，可重试或修改地址
- **极简依赖**：Java + Android 系统框架，无 AndroidX 等第三方运行时依赖；debug APK 约 48 KB，minSdk 24 / targetSdk 34

## 界面

| 首次启动（浅色） | 首次启动（深色） | 加载失败 |
| --- | --- | --- |
| ![首次启动](android/docs/screenshots/after-01-setup-light.png) | ![深色](android/docs/screenshots/after-02-setup-dark.png) | ![加载失败](android/docs/screenshots/after-03-error.png) |

连接成功后整个屏幕就是官方 DSH Web 界面（与手机浏览器打开同一地址看到的页面一致，但没有地址栏）。

## 快速开始

```sh
# 1. 电脑上启动 DSH Web，监听手机能访问的地址
dsh web --no-open --host 192.168.1.10 --port 3082

# 2. 获取并安装 debug APK（仓库 android/dist/ 里附带最近一次构建的包；也可以按下文在独立构建机上重新构建）
adb install -r android/dist/dsh-web-shell-debug.apk

# 3. 打开 App，粘贴电脑打印的 `dsh web: http://192.168.1.10:3082/?token=...` 整行链接，点“连接”
```

## 构建（在独立构建机上跑）

Android 构建（安装 Android SDK、跑 Gradle）会占用较多 CPU/内存/磁盘 IO，**不要在承载 DSH 服务的共享 PVE 主机/容器上执行**，避免挤占宿主资源、影响 PVE 稳定性。构建统一交给独立构建机：

```sh
bash scripts/remote-build.sh debug     # 或 release
```

脚本会：把当前工作区同步到构建机 → 在构建机上执行单测 / Lint / assemble → 把 APK 取回 `android/dist/dsh-web-shell-<variant>.apk`。

构建机连接信息由部署环境提供，写在 `~/.config/dsh/build.env`（**不要提交到仓库**），例如：

```sh
BUILD_HOST=user@build-machine              # 必填，SSH 目标
BUILD_SSH_KEY=$HOME/.ssh/build-key         # 可选，SSH 私钥
BUILD_DIR=dsh-build/lab-dsh-superagent     # 可选，构建机上的工作目录
BUILD_JAVA_HOME=$HOME/.local/jdk17         # 可选，构建机上的 JDK
BUILD_ANDROID_HOME=$HOME/Library/Android/sdk  # 可选，构建机上的 Android SDK
```

只有构建机本身才直接执行 `cd android && ./gradlew ...`（需要 JDK 17 + Android SDK 34）。完整说明（反向代理 `--trusted-host` 用法、安全边界、已知限制）见 [android/README.md](android/README.md)。

## 已知限制

- WebView 壳不是官方客户端：没有原生推送通知，不能离线使用，手机必须能访问 `dsh web` 服务
- 默认局域网明文 HTTP；公网访问请走 TLS 反向代理或内网穿透，不要把端口直接暴露到公网
- 页面由系统 WebView 渲染；过旧的 WebView（约 Chrome < 90）可能跑不动官方前端，建议 Android 10+ 或更新 WebView
- 仓库里的 APK 为 debug 签名，仅供侧载

## License

[MIT](LICENSE)。仓库内的 Gradle wrapper 归 Gradle 项目所有，遵循其各自许可证。
