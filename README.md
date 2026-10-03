# Agent Mobile Use - Android 虚拟副屏与无感后台控制底座

[English](#english) | [中文说明](#中文说明)

---

<a name="中文说明"></a>
## 中文说明

本项目提供一套针对 Android 深度定制的 **完全静默、后台独立运行、与物理主屏完全解耦** 的工业级系统控制底座。

通过底层的特权虚拟显示器（Virtual Display）、LSPosed 跨屏调度与输入法隔离、以及免软键盘弹窗的确定性无障碍文字注入，为大模型 Agent、自动化测试系统及远程控制脚本提供第一层设备操纵能力。

> ⚠️ **版本说明（SemVer 标准化）**：  
> 本项目遵循语义化版本规范（Semantic Versioning）。当前最新发行版本为 **`v0.8.5-alpha`**（KSU 模块 versionCode: `805`）。全面实装了纯原生极客暗黑风的 **Agent Mobile 控制中心与配置中心 (`SettingsActivity`)**、三栏纯几何矢量底栏、流体云注销撕裂热切换、毛玻璃透明透视/纯黑实色双模主题切换，以及基于 3080 端口 Remote RPC 的 DSH 动态版本握手机制。

---

### 实测实录：纯手绘作画实机效果展示（物理触控含金量）

📺 **B站高清实机演示视频**：[https://www.bilibili.com/video/BV1WYeS6YEwt](https://www.bilibili.com/video/BV1WYeS6YEwt)

底层虚拟副屏不仅能响应离散的按钮点击，更能承受高密度、高频次的连续物理手势调度。

在与 DeepSeek Harness (DSH) 配合测试中，Agent 接到指令 **“去我的便签里面，用绘制的方式（用系统的笔）随便画一幅画吧！要手绘噢！”**。在后台完全静默的副屏上拉起便签画板，自主进行了 **105 步精细运笔手势**，一手一手纯手绘创作完成了整幅风景画：

| DSH 交互执行链路 (1 轮 105 步连续触控) | 副屏纯手绘作画最终成品 (系统便签画板) |
| :---: | :---: |
| <img src="docs/images/dsh_drawing_task.jpg" width="340" alt="DSH Task Execution" /> | <img src="docs/images/drawn_landscape.jpg" width="340" alt="Drawn Landscape Result" /> |

整个手绘过程完全在后台虚拟副屏中发生，手机物理主屏完全不受影响，真正做到了“你在主屏聊天刷剧，Agent 在后台副屏手绘作画”。

---

### 试验环境声明 (Test Environment)

本系统在以下真机实验环境下完成全流程开发、调试与自动化闭环验证：

| 维度 | 实测实验配置 |
| :--- | :--- |
| **设备型号** | 真实 Android 物理机 (ColorOS 16 / Android 15-16 深度定制系统) |
| **系统内核** | Android Linux 6.12+ 内核分支 |
| **Root 方案** | **KernelSU (KSU)** / APatch / Magisk 特权环境 |
| **Hook 框架** | **LSPosed** (注入 `system_server`、`com.android.systemui` 进程) |
| **物理主屏规格** | 1272 x 2800 @ 560 DPI (副屏由守护脚本自适应匹配该规格与打孔 Cutout) |

---

### 核心特性：Agent Mobile 控制中心与配置中心 (v0.8.0 全新实装)

在 `v0.8.0-alpha` 中，项目全面引入了内置于特权 APK (`agent_hook.apk`) 的原生控制中心（`SettingsActivity`），采用深空暗黑极客风格（`#0F1117`）、**绝对零 Emoji**、单行极简条目与紧凑按钮设计：

#### 1. 固化吸顶统一 Header 与纯几何矢量底栏
- **吸顶固定 Header**：跨页面绝对对齐，零跳动。集成当前看板子标题与 `[刷新]` 快捷按钮。
- **纯几何矢量 Canvas 底栏**：`56dp` 沉浸式底栏，零图片、零文字、零表情符号，高精度 Canvas 动态绘制：
  - **Tab 0 (仪表图标)**：基本信息与服务监控看板
  - **Tab 1 (终端图标 `>_`)**：DSH 设置、凭据与 Web 控制台偏好
  - **Tab 2 (双屏图标)**：虚拟副屏硬件参数与自动化环境

#### 2. 三大板块功能矩阵

##### 【Tab 0】基本信息 / 监控 (Status & System Preferences)
- **服务与网络监控看板**：
  - `DSH 控制台`：实时检测 `127.0.0.1:3080` 连通性，回显 `[ONLINE] (3080)`；
  - `DSH 版本`：**不读任何本地文件路径**，通过本地签名 Cookie 向 3080 发起原生 Remote RPC（`POST /api/pluginManager/listBundles`）动态嗅探，回显核心版本（如 `v0.2.0-rc.2`），无论 DSH 部署在 Chroot、PRoot、Termux 还是 Docker 宿主网络均 100% 通用；
  - `网关服务`：实时探测 `127.0.0.1:3070`，回显 PID；
  - `运行模式`：`IDLE (待机)` / `BACKGROUND` / `FOREGROUND`；
  - `LSPosed 模块`：`[ACTIVE]` / `[INACTIVE]`（双保险探针：Self-Hook + Daemon 深度校验）。
- **系统特性偏好**：
  - `通知流体云化`：开启时将运行状态提升为 ColorOS 状态栏打孔胶囊；关闭时**显式销毁打孔区旧胶囊**，Hook 层执行硬拒绝，纯净退回下拉通知栏；
  - `任务完成提醒`：一键切换自动化跑完后的声音与振动强提醒；
  - `桌面图标快捷方式`：默认开启**纯隐形模式**（桌面上零图标）；打开后动态注册 `LauncherAlias` 快捷入口。
- **快捷唤起**：`[打开控制台]` 按钮即时拉起 Web 悬浮窗。

##### 【Tab 1】DSH 设置 / 凭据 (DSH Core & Console Customization)
- **网关节点**：回显 3070 与 3080 本地端点；
- **通信凭据密钥**：提供掩码输入框、`[显示/隐藏]` 切换与 `[保存并同步]` 按钮，自动持久化并下发至 3070 网关；
- **控制台偏好**：
  - `毛玻璃透明主题`：开启时 WebView 全透视且注入半透明磨砂毛玻璃 (`backdrop-filter: blur(28px)`) 与呼吸光；关闭时 WebView 填充 DSH 官方纯黑实色（`#151517`），彻底遮蔽底层画面；
  - `悬浮控制球与快捷条`：控制是否注入底部小鲸鱼浮动开关、主页按键与返回对话条；
  - `输入法自适应滚动`：输入法弹起时自动上推避让输入框。

##### 【Tab 2】副屏设置 / 环境 (Virtual Display & Automation)
- **副屏硬件参数**：实时显示 Display ID、副屏分辨率（如 `1272 x 2800`）、像素密度（`560 DPI`）；
- **自动化环境**：
  - `副屏自动化静音`：通过 AppOps 底层精准抑制副屏音频流，杜绝后台刷视频突发爆音；
  - `前台接管呼吸光`：前台物理屏幕被 AI 接管操作时的边缘视觉呼吸警示；
  - `完成后自动待机`：任务跑完且无提问时副屏自动退回 Idle 节能待机；
- **模式手动切换**：`[待机]` / `[后台副屏]` / `[前台接管]` 一键切换。

#### 3. 系统级多维入口
- **LSPosed 管理器直达**：遵循 `de.robv.android.xposed.category.MODULE_SETTINGS` 契约，在 LSPosed 模块卡片点击齿轮一键直达控制中心；
- **下拉通知栏快捷磁贴**：注册 Android 原生 Quick Settings Tile（`ConsoleTileService`），非实体侧键机型亦可下拉状态栏一键呼出控制台；
- **桌面快捷小鲸鱼**：支持动态开启/隐藏。

---

### 核心架构与职责分工

1. **命令行控制总线 (`/system/bin/vd`)**：
   - 守护进程生命周期控制与即时状态诊断；
   - 物理输入事件与底层 Java 观测工具的 CLI 快捷直通封装。
2. **底层运行时与守护进程 (`vd-tool-java`)**：
   - `DaemonMain` (`agent_vd.dex`)：通过特权 API 动态创建 `VirtualDisplay`，镜像主屏物理打孔（Cutout），管理全分辨率的高性能 JPEG 帧缓存。
   - `ToolMain` (`agent_tools.dex`)：利用 `UiAutomation` 抓取结构化平铺 UI 控件树；提供基于无障碍的纯确定性双轨文字注入。
3. **HTTP / REST 控制网关 (`vd-server-go`)**：
   - 纯静态编译的 ARM64 Go 服务，运行在 `0.0.0.0:3070`；
   - 对外暴露标准的 RESTful API，统一调度物理/虚拟屏幕切换、手势与无障碍操作、通知与交互确认。
4. **设备端交互与特权宿主应用 (`agent-hook-apk`)**：
   - `SettingsActivity`：**原生控制中心与配置中心**（三栏纯图标底栏、服务看板、偏好开关）；
   - `HookEntry`：基于 LSPosed 的 `system_server` 特权补丁，解锁虚拟副屏多任务承载能力、主屏输入法隔离（IMMS），以及 SystemUI 流体云过滤与硬拦截；
   - `ConsoleTileService`：Android Quick Settings 下拉控制中心快捷磁贴；
   - `GlowService`：前台全屏赛博呼吸光效、实时触控涟漪与激光轨迹，以及状态栏打孔胶囊生命周期管理；
   - `DemoDialogActivity`：全屏嵌入式 Web 控制台浮窗，支持透明毛玻璃与官方纯黑双模自适应；
   - `QuestionActivity` & `NotifyReceiver`：交互提问直达浮窗与任务完成通知。

---

### 暴露的工具与接口

#### 1. 命令行控制总线 (`vd` 工具)

模块安装后会自动在系统 PATH 中注册 `vd` 命令（位于 `/system/bin/vd`）：

- **`vd start`**：唤醒底层虚拟副屏，自适应计算物理主屏分辨率与 DPI，启动 3070 端口监控网关。
- **`vd stop`**：完全销毁副屏，向系统注销 Display，回收所有显存与计算资源。
- **`vd status`**：查看当前副屏状态（运行中/休眠）、当前 Display ID 以及动态屏幕规格。
- **`vd launch <包名> [--user <id>]`**：定向调度指定应用直接在副屏启动（支持应用双开分身 `--user 999`）。
- **`vd tree`**：结构化 Dump 当前副屏的无障碍控件树（平铺格式：状态行 + 列头 + 一行一元素，含节点文本、flags、边界与可点击中心坐标）。
- **`vd tap <x> <y>`**：向当前目标屏幕发送物理触控点击事件。
- **`vd type [target] "<文本>"`**：静默文字注入（双轨确定性：可指定 UI 树数字节点 ID 如 `146`，或省略 target 直接灌入当前聚焦输入框；0 键盘弹窗）。
- **`vd swipe <x1> <y1> <x2> <y2> [duration_ms]`**：向目标屏幕发送滑动、曲线笔触或长按手势。
- **`vd key <keycode>`**：向目标屏幕发送系统物理按键（如 4 为返回，3 为主页，66 为回车）。
- **`vd screenshot [path]`**：定向截取当前屏幕画面并保存为 JPEG 图片。
- **`vd apps [query]`**：获取本机已安装的应用名称与启动 Activity 组件名。

#### 2. HTTP / REST 监控网关 (Port 3070)

由纯静态 Go 服务 `vd_server` 提供：
- `GET http://127.0.0.1:3070/`：可视化 Web 监控界面，提供低延迟 H.264 硬件编码实时视频流、副屏状态指示与前后台切换开关。
- `GET /api/stream/ws`：WebSocket H.264 低延迟裸流通道，直连 `DaemonMain` 硬件编码器（1080P / 60FPS / 6Mbps）。
- `GET /api/status`：获取当前显示器 JSON 状态（含运行状态、物理/副屏宽高、DPI、当前操作模式、目标 Display ID、LSPosed 挂载状态）。
- `GET /api/screenshot`：获取当前目标屏幕画面。副屏运行时默认直接提取硬件帧缓存并压缩为 JPEG；主屏模式自动调用硬件抓屏。
- `POST /api/action`：**统一复合动作执行引擎**。聚合了 `observe`、`click`、`swipe`、`type`、`key`、`launch_app`、`wait` 等全部物理动作，并在动作后自动进行自适应物理过渡与回弹 UI dump 观测。
- `GET|POST /api/start` · `POST /api/stop`：远程拉起/注销底层虚拟副屏。
- `GET|POST /api/mode`：前后台操作模式查询与切换（`foreground`、`background`、`idle`）。
- `GET /api/dump_ui`：平铺式无障碍树观测接口，支持 `?no_system_ui=1`。
- `GET|POST /api/apps`：查询本机桌面应用列表（自动归一化解析 `--user` 分身应用）。
- `POST /api/notify`：投递系统横幅通知与任务完成状态。
- `POST /api/question` · `POST /api/question/cancel`：交互提问通知投递与取消。
- `POST /api/auth/secret`：DSH 通信密钥动态同步与查询。
- `GET /api/audio/status` · `POST /api/audio/toggle` · `POST /api/audio/unmute-all`：副屏应用智能静音状态查询、手动开关与全量解静音。
- `GET /api/session/watch` · `POST /api/task_event`：DSH 活跃会话内核级连接心跳与状态同步。

---

### 安装与使用方式

#### 方式一：直接刷入发行版（推荐）

1. 从 `release/` 目录或 GitHub Releases 下载最新的刷机包：
   **`agent-mobile-use-ksu-v0.8.5-alpha.zip`**
2. 将 zip 文件传输至手机中。
3. 打开 **KernelSU** (或 APatch / Magisk) 管理器 -> 点击「模块」-> 选择该 zip 进行安装。
4. 安装过程中脚本会自动完成以下动作：
   - 安装静默控制底座 APK (`agent_hook.apk`)；
   - 自动检测本地 LSPosed 数据库并激活 `system`、`android`、`com.android.systemui` 作用域；
   - 将 `vd` 部署至 `/system/bin/vd`。
5. 重启手机使 LSPosed Hook 与系统服务挂载生效。

#### 方式二：手动编译源码

- 编译 Java 组件：进入 `vd-tool-java/` 目录，执行 `./build.sh`。
- 编译 Go 服务：进入 `vd-server-go/` 目录，执行 `./build.sh`（静态交叉编译）。
- 编译 Hook APK：进入 `agent-hook-apk/` 目录，执行 `./build.sh`。
- 组装并打包：在 `ksu-module/` 执行 `./pack.sh` 生成模块 zip。

---

<a name="english"></a>
## English Description

`agent-mobile-use` provides an industrial-grade, fully silent, background headless virtual display and low-level control substrate for Android (tested on ColorOS 16 / Android 15-16).

By decoupling execution onto an independent virtual display (Display > 0), intercepting task/activity focus switches with LSPosed hooks, and injecting text via accessibility without popping up soft keyboards, this project provides a clean foundation for LLM Agents and automated systems.

> ⚠️ **Release Notice (v0.8.5-alpha)**:  
> Current active release is **`v0.8.5-alpha`** (KSU module versionCode: `805`). Features the brand new **Agent Mobile Control & Settings Center (`SettingsActivity`)**, 3-tab vector bottom navigation, fluid cloud lifecycle teardown toggle, translucent blur vs. pure black theme switcher, and network-based DSH version RPC discovery.

---

### Key Features: Mobile Control & Settings Center (v0.8.0)

Integrated directly into `agent_hook.apk`, the new native control center (`SettingsActivity`) delivers a minimalist cyberpunk industrial UI (`#0F1117`) with **strict zero-emoji typography** and compact controls:

1. **Sticky Header & Vector Bottom Navigation Bar**:
   - **Sticky Fixed Header**: Seamlessly aligned across all three tabs, displaying the live view title and a compact `[Refresh]` button.
   - **Pure Vector Bottom Bar (56dp)**: Crisp Canvas-drawn vector icons (Gauge, Terminal `>_`, Dual Monitors) with micro glowing indicator dots — zero images, zero emojis.
2. **Feature Matrix Across 3 Tabs**:
   - **Tab 0 (Dashboard & Status)**:
     - Real-time 3080 Web Console connection check;
     - Dynamic DSH runtime version probe (via 3080 Remote RPC `POST /api/pluginManager/listBundles`, fully decoupled from local paths or Chroot/PRoot/Termux environments);
     - Port 3070 daemon status and PID;
     - Current execution mode (`IDLE`, `BACKGROUND`, `FOREGROUND`);
     - LSPosed hook active indicator (`[ACTIVE]` / `[INACTIVE]`);
     - System preference switches: Fluid Cloud conversion (with punch-hole capsule teardown), task completion alerts (sound & vibration), and launcher icon toggle (pure invisible mode by default).
   - **Tab 1 (DSH Core & Console)**:
     - Gateway and console endpoints;
     - Dynamic authentication secret management with show/hide mask and gateway synchronization;
     - Web console preferences: Translucent blur theme toggle (glassmorphism overlay vs. native DSH solid black `#151517`), floating whale toolbar toggle, and soft-keyboard scroll assist.
   - **Tab 2 (Virtual Display & Automation)**:
     - Virtual display hardware parameters (Display ID, resolution, DPI);
     - Automation environment switches: Virtual display audio mute guard, foreground takeover glow frame, and auto standby on finish;
     - Manual mode switcher (`[Idle]`, `[Background]`, `[Foreground]`).
3. **Multi-Entry Integration**:
   - **LSPosed Module Settings**: Click the module card gear icon in LSPosed Manager to open the control center directly (`de.robv.android.xposed.category.MODULE_SETTINGS`);
   - **Quick Settings Tile**: `ConsoleTileService` allows pulling down the Android status bar to trigger the Web console on any device;
   - **Launcher Shortcut**: Optional toggleable launcher icon.

---

### Exposed Tools & Interfaces

1. **CLI Bus (`/system/bin/vd`)**:
   - `vd start` / `vd stop` / `vd status`: Virtual display lifecycle and metrics.
   - `vd launch <pkg> [--user <id>]`: Launch application directly onto target display.
   - `vd tree`: Flat structured dump of accessibility hierarchy.
   - `vd tap <x> <y>`: Inject touch events to target display.
   - `vd type [target] "<text>"`: Deterministic dual-track silent text injection without keyboard popups.
   - `vd swipe <x1> <y1> <x2> <y2> [duration]`: Simulate gestures or brush strokes.
   - `vd key <keycode>`: Send key events.
   - `vd screenshot [path]`: Capture JPEG frame of current target display.
   - `vd apps [query]`: List launchable applications.

2. **HTTP / REST Gateway (Port 3070)**:
   - `GET /`: Visual web console with live H.264 video streaming.
   - `GET /api/stream/ws`: WebSocket low-latency raw H.264 bitstream.
   - `GET /api/status`: JSON display status (includes LSPosed active state).
   - `GET /api/screenshot`: Current frame of target display.
   - `POST /api/action`: Unified composite action engine.
   - `GET|POST /api/mode`: Query or switch between `foreground`, `background`, and `idle`.
   - `POST /api/auth/secret`: Dynamic DSH auth secret synchronization.
   - `GET /api/audio/status` · `POST /api/audio/toggle`: Event-driven background audio mute guard.

---

## License

MIT License.
