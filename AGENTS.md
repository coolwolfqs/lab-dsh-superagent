# 给编码 Agent 的约定（AGENTS.md）

本仓库是一个 Android WebView 壳：用系统 WebView 直接加载 DSH 官方 Web UI。

## 构建纪律（重要）

- **重活（安装 Android SDK、跑 Gradle 构建、开模拟器）不要放在运行 DSH 服务的共享 PVE 主机/容器上。**
  这类任务会挤占宿主 CPU/内存/磁盘 IO，影响 PVE 稳定性。
- 构建、单元测试、Lint 统一在独立构建机（装好 JDK 17 + Android SDK 34 的机器）上执行：

  ```sh
  bash scripts/remote-build.sh debug     # 或 release
  ```

  脚本会把当前工作区同步到构建机、在构建机上跑 Gradle，再把 APK 取回 `android/dist/`。
- 构建机的 SSH 连接信息由部署环境提供（`~/.config/dsh/build.env`，**不要提交**）；
  仓库里不要写死内网地址、主机名、用户名或密钥。
- 本机（共享主机）只做轻量操作：改代码、审查、取回产物。
- 没有构建机配置时脚本会报错退出（退出码 2），**不要**退回本机安装 SDK / 跑 Gradle。

## 其他约定

- 不要提交签名文件（`*.jks` / `*.keystore` / `keystore.properties`）、令牌或任何凭据；debug 签名即可。
- 本仓库是公开仓库：不要写入内网地址、主机名、口令等内部信息。
- 保持零第三方运行时依赖（Java + 系统框架）；单元测试可用 JUnit。
- 改 UI 需要附上同场景、同视口、同主题的前后截图对比。
