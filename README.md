# Agent Mobile Use - Android 虚拟副屏与无感后台控制底座

本项目提供一套针对 Android（以 ColorOS / Android 16 为第一实验环境）深度定制的 **完全静默、后台独立运行、与物理主屏完全解耦** 的系统级控制底座。

通过底层的特权虚拟显示器（Virtual Display）、LSPosed 跨屏调度拦截、以及免软键盘弹窗的无障碍文字注入，为大模型 Agent、自动化测试系统及远程控制脚本提供第一层设备操纵能力。

---

## 试验环境声明 (Test Environment)

本系统在以下真机实验环境下完成全流程开发、调试与自动化闭环验证：

| 维度 | 实测实验配置 |
| :--- | :--- |
| **设备型号** | 真实 Android 物理机 (ColorOS 16 深度定制系统) |
| **系统版本** | Android 16 (基于 6.12 内核分支) |
| **安全补丁级别** | **2025 年 12 月 / 2026 年最新补丁环境** |
| **Root 方案** | **KernelSU (KSU)** (非解锁 Bootloader 状态，无缝特权注入) |
| **Hook 框架** | **LSPosed** (通过 Zygisk / KSU 驱动模块注入 `system_server` 进程) |
| **物理主屏规格** | 1272 x 2800 @ 560 DPI (副屏由守护脚本自适应匹配该规格) |

> **提示**：如果您的设备处于不同厂商系统（如 MIUI/HyperOS、OneUI、原生 AOSP）或不同 Android 版本，请务必参阅后文的 [兼容性与二次适配说明](#兼容性与二次适配说明)。

---

## 核心设计与作用

传统自动化方案（如普通 `adb shell input`、uiautomator、投屏方案）的最大痛点在于：**抢占主屏前台、弹窗打扰用户使用、输入法强制弹窗、主屏息屏或切换应用时任务中断**。

本项目通过多层底层机制实现：

1. **后台独立副屏 (Display > 0)**：在系统内存中创建一个独立的 Headless 虚拟屏幕，应用直接在副屏渲染运行，物理主屏可以正常刷微信、刷视频甚至息屏，两者互不干扰。
2. **全静默调度 (No Focus Stealing)**：通过 LSPosed Hook 补丁拦截 `ActivityTaskSupervisor` 和 `ActivityRecord` 的跨屏约束，禁止副屏应用抢夺主屏焦点。
3. **免输入法文字灌入 (No IME Popup)**：通过 Java 字节码注入无障碍 `ACTION_SET_TEXT`，中英文长难句瞬时填入，完全不拉起软键盘。
4. **轻量与自愈 (Zero Overhead)**：提供命令行控制总线与纯静态 HTTP 监控网关，副屏按需启动、随时安全注销，显存与计算资源零泄露。

---

## 暴露的工具与接口

模块刷入后，提供三层接入形态：

### 1. 命令行控制总线 (`vd` 工具)

模块安装后会自动在系统 PATH 中注册 `vd` 命令（位于 `/system/bin/vd`）：

- **`vd start`**：唤醒底层虚拟副屏，自适应计算物理主屏分辨率，启动 3070 端口监控网关。
- **`vd stop`**：完全销毁副屏，向系统注销 Display，回收所有 GPU 显存与 CPU 资源。
- **`vd status`**：查看当前副屏状态（运行中/休眠）、当前 Display ID 以及分辨率参数。
- **`vd launch <包名>`**：定向调度指定应用直接在副屏启动（例如 `vd launch com.sankuai.meituan`）。
- **`vd tree`**：结构化 Dump 当前副屏的无障碍控件树（以极简 JSON 输出节点文本、ID、中心绝对点击坐标）。
- **`vd tap <x> <y>`**：向副屏指定坐标发送物理触控点击事件（利用 `input -d <did> tap`）。
- **`vd type "<文本>"`**：静默将文本填入副屏当前获得焦点的输入框（支持中文、emoji、特殊符号，0 键盘弹窗）。
- **`vd swipe <x1> <y1> <x2> <y2> [duration_ms]`**：向副屏发送滑动或长按手势。
- **`vd key <keycode>`**：向副屏发送系统物理按键（如 4 为返回，3 为主页，66 为回车）。
- **`vd screenshot [path]`**：定向截取副屏当前帧并保存为 PNG 图片（默认路径 `/data/local/tmp/vd_screenshot.png`）。

### 2. HTTP / REST 监控网关 (Port 3070)

由纯静态 Go 服务 `vd_server` 提供：
- `GET http://127.0.0.1:3070/`：可视化 Web 监控界面，提供手动刷新快照、当前状态指示与副屏注销按钮。
- `GET http://127.0.0.1:3070/api/status`：获取副屏 JSON 状态（`{"status":"running","display_id":5,"width":1272,"height":2800,"dpi":560}`）。
- `GET http://127.0.0.1:3070/api/screenshot`：获取副屏当前实时快照图像流。
- `POST http://127.0.0.1:3070/api/stop`：远程关闭副屏。

---

## 仓库工程结构与源码说明

```
.
├── ksu-module/                   # KSU 模块打包源文件与安装脚本
│   ├── customize.sh              # 模块刷入安装入口（自动安装 APK 并静默配置 LSPosed 作用域）
│   ├── service.sh                # 开机后台拉起守护
│   ├── module.prop               # 模块配置信息
│   ├── system/bin/vd             # 命令行总线脚本
│   └── bin/                      # 静态二进制与 DEX
│       ├── vd_server             # Go 编译生成的纯静态无依赖 Web 服务
│       ├── agent_vd.dex          # 虚拟屏常驻进程 DEX
│       ├── agent_tools.dex       # 控件树 Dump 与文字注入 DEX
│       └── sqlite3               # 静态 SQLite 二进制（用于操作 LSPosed 配置库）
├── vd-server-go/                 # vd_server 完整 Go 源码与网页 HTML
│   ├── main.go
│   ├── index.html
│   └── build.sh
├── vd-tool-java/                 # 两个 DEX 的原生 Java 源码与编译脚本
│   ├── src/com/agent/
│   │   ├── DaemonMain.java       # 副屏生命周期管理器
│   │   └── ToolMain.java         # 无障碍树解析与注入执行器
│   └── build.sh
├── agent-hook-apk/               # LSPosed Hook 补丁源码
│   ├── src/com/agent/mobileuse/
│   │   └── HookEntry.java        # 拦截 Task 跨屏限制与 IMMS 软键盘调度
│   └── AndroidManifest.xml
└── release/                      # 开箱即用的预打包发行版
    └── agent-mobile-use-ksu-v3.5.zip
```

---

## 安装与使用方式

### 方式一：直接刷入发行版（推荐）

1. 从 `release/` 目录下载预编译好的刷机包：
   **`agent-mobile-use-ksu-v3.5.zip`**
2. 将 zip 文件传输至手机中。
3. 打开 **KernelSU** (或 APatch / Magisk) 管理器 -> 点击「模块」-> 选择该 zip 进行安装。
4. 安装过程中脚本会自动完成以下动作：
   - 安装静默 Hook APK (`agent_hook.apk`)；
   - 自动检测本地 LSPosed 数据库并激活 `system` 和 `android` 作用域；
   - 将 `vd` 部署至 `/system/bin/vd`。
5. 重启手机使 LSPosed Hook 与系统服务挂载生效。

### 方式二：手动编译源码

若需修改 Java 或 Go 逻辑：
- 编译 Java 组件：进入 `vd-tool-java/` 目录，执行 `./build.sh`（需本地安装有 `javac` 与 Android SDK 的 `dx` 或 `d8` 工具）。
- 编译 Go 服务：进入 `vd-server-go/` 目录，执行 `./build.sh`（要求 `CGO_ENABLED=0 GOOS=linux GOARCH=arm64` 静态交叉编译）。
- 组装并打包：在 `ksu-module/` 执行 `./pack.sh` 生成模块 zip。

---

## 进阶：DSH 原生插件与 MCP (Model Context Protocol) 说明

本项目定位为 **设备端的纯原生底座与标准能力提供方**：

1. **后续将配套推出 DSH 原生预设插件**：
   - DeepSeek Harness (DSH) 原生适配的 Mobile Use 预设插件（Cordis 架构）将在后续单独发布。该插件将直接调度底座的 `vd` 工具，完成自动化视觉推理闭环与历史图片内存压缩（Sliding Window Image Offload）。
2. **支持接入 MCP 协议 (Model Context Protocol)**：
   - 本项目通过 `vd` 命令行与 `vd_server` HTTP 接口暴露了完整原子能力（截屏、控件感知、点击、滑动、键入、启动应用）。
   - **如果您希望将本底座接入 Claude Desktop、Cursor 等支持 MCP 的宿主系统，需要开发者自行编写轻量级 MCP Server 包装层**（例如使用 Node.js / Python 监听 stdio，将 MCP 请求映射为对 `vd` 指令或 3070 端口的调用）。底座已准备好所有原子工具，无需对手机端做多余改造。

---

## 兼容性与二次适配说明

为了在 ColorOS 16 上获得极致的稳定度与系统融合，本模块在部分配置上针对实验环境做了适配。如果要在其他机型上获得完美体验，请注意以下改动点：

1. **`BOOTCLASSPATH` 环境变量解耦**：
   - 源码 `vd-tool-java/` 编译出的 DEX 是通过系统自带的 `app_process` 执行的。在 `system/bin/vd` 与 `ksu-module/bin/run_daemon.sh` 中配置的 `BOOTCLASSPATH` 当前包含了 ColorOS 特定的 framework 包（例如 `oplus-framework.jar`）。
   - **非 OPPO/OnePlus 设备适配**：若在原生 Android、小米或三星设备上运行报错，请自行修改脚本中的 `BOOTCLASSPATH`，动态获取系统默认类路径（例如 `export BOOTCLASSPATH=$(grep "export BOOTCLASSPATH" /init.environ.rc | cut -d' ' -f3)`）以适应目标机型。
2. **LSPosed 模块配置路径差异**：
   - `customize.sh` 默认操作的 LSPosed 数据库路径为 `/data/adb/lspd/config/modules_config.db`。若使用其他变种（如某些非官方 LSPosed 分支），自动添加作用域可能会跳过，此时请手动打开 LSPosed App，勾选「Agent Mobile Use Hook」，并勾选「系统框架 (Android)」后重启即可。
3. **特定 App 副屏控件树降级策略**：
   - 部分第三方加固应用（如微信）在未连接真实物理触摸板的虚拟副屏上，系统默认会压制无障碍节点生成（`UiAutomation` 获取为空树）。针对此类应用，请以截屏视觉感知（`vd screenshot` + 坐标推理）作为主链路。

---

## 开源协议

本项目代码遵循 MIT 开源协议发布。
