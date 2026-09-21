# Agent Mobile Use - Android 虚拟副屏与无感后台控制底座

[English](#english) | [中文说明](#中文说明)

---

<a name="中文说明"></a>
## 中文说明

本项目提供一套针对 Android（以 ColorOS / Android 16 为第一实验环境）深度定制的 **完全静默、后台独立运行、与物理主屏完全解耦** 的系统级控制底座。

通过底层的特权虚拟显示器（Virtual Display）、LSPosed 跨屏调度拦截、以及免软键盘弹窗的无障碍文字注入，为大模型 Agent、自动化测试系统及远程控制脚本提供第一层设备操纵能力。

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
| **设备型号** | 真实 Android 物理机 (ColorOS 16 深度定制系统) |
| **系统版本** | Android 16 (基于 6.12 内核分支) |
| **安全补丁级别** | **2025 年 12 月 / 2026 年最新补丁环境** |
| **Root 方案** | **KernelSU (KSU)** (非解锁 Bootloader 状态，无缝特权注入) |
| **Hook 框架** | **LSPosed** (通过 Zygisk / KSU 驱动模块注入 `system_server` 进程) |
| **物理主屏规格** | 1272 x 2800 @ 560 DPI (副屏由守护脚本自适应匹配该规格) |

> **提示**：如果您的设备处于不同厂商系统（如 MIUI/HyperOS、OneUI、原生 AOSP）或不同 Android 版本，请务必参阅后文的 [兼容性与二次适配说明](#兼容性与二次适配说明)。

---

### 核心设计与作用

传统自动化方案（如普通 `adb shell input`、uiautomator、投屏方案）的最大痛点在于：**抢占主屏前台、弹窗打扰用户使用、输入法强制弹窗、主屏息屏或切换应用时任务中断**。

本项目通过多层底层机制实现：

1. **后台独立副屏 (Display > 0)**：在系统内存中创建一个独立的 Headless 虚拟屏幕，应用直接在副屏渲染运行，物理主屏可以正常日常使用甚至息屏，两者互不干扰。
2. **全静默调度 (No Focus Stealing)**：通过 LSPosed Hook 补丁拦截 `ActivityTaskSupervisor` 和 `ActivityRecord` 的跨屏约束，禁止副屏应用抢夺主屏焦点。
3. **免输入法文字灌入 (No IME Popup)**：通过 Java 字节码注入无障碍 `ACTION_SET_TEXT`，中英文长难句瞬时填入，完全不拉起软键盘。
4. **轻量与自愈 (Zero Overhead)**：提供命令行控制总线与纯静态 HTTP 监控网关，副屏按需启动、随时安全注销，显存与计算资源零泄露。

---

### 暴露的工具与接口

模块刷入后，提供三层接入形态：

#### 1. 命令行控制总线 (`vd` 工具)

模块安装后会自动在系统 PATH 中注册 `vd` 命令（位于 `/system/bin/vd`）：

- **`vd start`**：唤醒底层虚拟副屏，自适应计算物理主屏分辨率，启动 3070 端口监控网关。
- **`vd stop`**：完全销毁副屏，向系统注销 Display，回收所有 GPU 显存与 CPU 资源。
- **`vd status`**：查看当前副屏状态（运行中/休眠）、当前 Display ID 以及分辨率参数。
- **`vd launch <包名>`**：定向调度指定应用直接在副屏启动（例如 `vd launch com.sankuai.meituan`）。
- **`vd tree`**：结构化 Dump 当前副屏的无障碍控件树（以极简 JSON 输出节点文本、ID、中心绝对点击坐标）。
- **`vd tap <x> <y>`**：向副屏指定坐标发送物理触控点击事件（利用 `input -d <did> tap`）。
- **`vd type "<文本>"`**：静默将文本填入副屏当前获得焦点的输入框（支持中文、特殊符号，0 键盘弹窗）。
- **`vd swipe <x1> <y1> <x2> <y2> [duration_ms]`**：向副屏发送滑动、曲线笔触或长按手势。
- **`vd key <keycode>`**：向副屏发送系统物理按键（如 4 为返回，3 为主页，66 为回车）。
- **`vd screenshot [path]`**：定向截取副屏当前帧并保存为 PNG 图片（默认路径 `/data/local/tmp/vd_screenshot.png`）。

#### 2. HTTP / REST 监控网关 (Port 3070)

由纯静态 Go 服务 `vd_server` 提供：
- `GET http://127.0.0.1:3070/`：可视化 Web 监控界面，提供手动刷新快照、当前状态指示与**副屏开关按钮**（副屏运行中显示「关闭副屏」，已休眠显示「开启副屏」，网关不可达时按钮置灰）。快照区高度按浏览器视口自适应，无需滚动即可整屏查看。
- `GET http://127.0.0.1:3070/api/status`：获取副屏 JSON 状态（`{"status":"running","display_id":5,"width":1272,"height":2800,"dpi":560}`）。
- `GET http://127.0.0.1:3070/api/screenshot`：获取副屏当前画面。**默认直接返回守护进程缓存的 JPEG**（副屏运行时，全分辨率 1272×2800，约 270 KB），守护进程未运行或尚未出帧时回退到 `screencap -p` 的 PNG 路径。
  - 为什么要这么绕：`screencap -p` 编一张全分辨率 PNG 要烧掉约 **1.8 秒 CPU**、产出 2.9 MB；而守护进程本来就持有副屏的输出 Surface，每帧在手，编成 JPEG 只要 **约 18 ms**、270 KB。实测同一条取图链路端到端从 **2520 ms 降到 63 ms（约 40 倍）**。
  - ⚠️ **JPEG 必须保持副屏全分辨率**：截图工具（`mobile_screenshot`）是用返回图片的像素尺寸去推算投递给视觉模型时的缩放比例的，一旦这里预降采样，工具就会告诉模型一个错误的比例，导致点击坐标整体偏移。
- `GET|POST http://127.0.0.1:3070/api/start`：远程拉起副屏（副屏未启动时 Web 界面按钮自动指向此接口）。
- `POST http://127.0.0.1:3070/api/stop`：远程关闭副屏。

---

### 安装与使用方式

#### 方式一：直接刷入发行版（推荐）

1. 从 `release/` 目录或 GitHub Releases 下载预编译好的刷机包：
   **`agent-mobile-use-ksu-v4.5.zip`**
2. 将 zip 文件传输至手机中。
3. 打开 **KernelSU** (或 APatch / Magisk) 管理器 -> 点击「模块」-> 选择该 zip 进行安装。
4. 安装过程中脚本会自动完成以下动作：
   - 安装静默 Hook APK (`agent_hook.apk`)；
   - 自动检测本地 LSPosed 数据库并激活 `system` 和 `android` 作用域；
   - 将 `vd` 部署至 `/system/bin/vd`。
5. 重启手机使 LSPosed Hook 与系统服务挂载生效。

#### 方式二：手动编译源码

- 编译 Java 组件：进入 `vd-tool-java/` 目录，执行 `./build.sh`。
- 编译 Go 服务：进入 `vd-server-go/` 目录，执行 `./build.sh`（静态交叉编译）。
- 组装并打包：在 `ksu-module/` 执行 `./pack.sh` 生成模块 zip。

#### 热更新线上 `vd_server`（不刷模块）

`index.html` 由 `//go:embed` 编进 `vd_server`，**只改 HTML 不重新编译等于没改**。更新一个已部署设备时：

1. 重新编译后，把新二进制放进模块目录：`/data/adb/modules/agent_mobile_use/bin/vd_server`，
   并把 `ksu-module/bin/vd_server`、`release/`、`release-packages/` 一并刷新，保证仓库、发行包、
   生产三者 md5 一致（`md5sum` 逐个比对即可）。
2. **先 kill 再替换**：运行中的可执行文件被 `cp` 覆盖会 `ETXTBSY`，必须先 `kill -9 $(pidof vd_server)`，
   再 `mv` 新文件就位，最后用 `setsid`/`nohup` 重新拉起，并让它跑在 Android 挂载命名空间内
   （否则看不到 `/system/bin` 与 `/data/local/tmp`）。
3. ⚠️ **切勿在 `mobile_shell` 通道里 kill `vd_server`**：DSH 预设的 `mobile_shell` 走的正是
   `POST http://127.0.0.1:3070/api/shell`（见 `dsh-preset-mobile-use/preset/mobile-use/mobile_plugin.js`），
   kill 掉网关会同时切断自己的执行通道，导致重启脚本执行到一半就失联。请从宿主/容器侧用
   `nsenter -t 1 -m -- /system/bin/sh -c 'nohup setsid /data/adb/modules/agent_mobile_use/bin/vd_server ...'`
   完成「停旧 + 起新」，或让重启用一条独立于该会话的后台命令执行。
4. 更新完成自检：`curl -s http://127.0.0.1:3070/ | diff - vd-server-go/index.html` 应无差异。

---

### 进阶：DSH 原生预设与 MCP (Model Context Protocol) 接入

本项目定位为 **设备端的纯原生底座与标准能力提供方**：

1. **配套的 DSH 原生预设现已同步开源**：
   - DeepSeek Harness (DSH) 原生适配的 Mobile Use 预设：**[dsh-preset-mobile-use](https://github.com/AcidGr/dsh-preset-mobile-use)**。
   - 该预设直接调度底座的 `vd` 工具与 3070 端口，完成自动化视觉推理闭环与智能滑动窗口图片内存压缩（Sliding-Window Image Offload），解压至 `~/.dsh/.agent-presets/` 即可直接在 Web 界面中使用。
2. **支持接入 MCP 协议 (Model Context Protocol)**：
   - 本项目通过 `vd` 命令行与 `vd_server` HTTP 接口暴露了完整原子能力（截屏、控件感知、点击、滑动、键入、启动应用）。
   - **如果您希望将本底座接入 Claude Desktop、Cursor 等支持 MCP 的宿主系统，需要开发者自行编写轻量级 MCP Server 包装层**（例如使用 Node.js / Python 监听 stdio，将 MCP 请求映射为对 `vd` 指令或 3070 端口的调用）。底座已准备好所有原子工具，无需对手机端做多余改造。

---

### 兼容性与二次适配说明

1. **`BOOTCLASSPATH` 环境变量解耦**：
   - 在 `system/bin/vd` 与 `ksu-module/bin/run_daemon.sh` 中配置的 `BOOTCLASSPATH` 当前包含了 ColorOS 特定的 framework 包（例如 `oplus-framework.jar`）。
   - **非 OPPO/OnePlus 设备适配**：若在原生 Android、小米或三星设备上运行报错，请自行修改脚本中的 `BOOTCLASSPATH`，动态获取系统默认类路径以适应目标机型。
2. **LSPosed 模块配置路径差异**：
   - `customize.sh` 默认操作的 LSPosed 数据库路径为 `/data/adb/lspd/config/modules_config.db`。若使用其他变种，请手动打开 LSPosed App，勾选「Agent Mobile Use Hook」，并勾选「系统框架 (Android)」后重启即可。
3. **特定 App 副屏控件树降级策略**：
   - 部分第三方加固应用（如微信）在未连接真实物理触摸板的虚拟副屏上，系统默认会压制无障碍节点生成（`UiAutomation` 获取为空树）。针对此类应用，请以截屏视觉感知（`vd screenshot` + 坐标推理）作为主链路。

---

<a name="english"></a>
## English Description

`agent-mobile-use` provides an industrial-grade, fully silent, background headless virtual display and low-level control foundation for Android (tested on ColorOS 16 / Android 16).

By decoupling execution onto an independent virtual display (Display > 0), intercepting task/activity focus switches with LSPosed hooks, and injecting text via accessibility without popping up soft keyboards, this project provides a clean substrate for LLM Agents and automated systems.

---

### Real-world Showcase: Autonomous Hand-drawn Artwork

📺 **Bilibili Showcase Video**: [https://www.bilibili.com/video/BV1WYeS6YEwt](https://www.bilibili.com/video/BV1WYeS6YEwt)

The underlying headless virtual display supports not just discrete button clicks, but high-frequency, precision continuous gestures.

In automated tests with DeepSeek Harness (DSH), the Agent received a single prompt: **"Go to my system Notes app and draw a picture using the system pen! Must be hand-drawn!"**. It autonomously planned and executed **105 consecutive precision drawing gestures stroke by stroke**, producing a complete rural landscape artwork:

| DSH Execution Workflow (1 turn, 105 steps) | Final Hand-drawn Artwork in Notes App |
| :---: | :---: |
| <img src="docs/images/dsh_drawing_task.jpg" width="340" alt="DSH Task Execution" /> | <img src="docs/images/drawn_landscape.jpg" width="340" alt="Drawn Landscape Result" /> |

The whole drawing process took place entirely in the background virtual display without taking focus away from the user on the primary physical screen.

---

### Experimental Verification Environment

| Aspect | Tested Configuration |
| :--- | :--- |
| **Device** | Physical Android device (ColorOS 16 custom ROM) |
| **Android Version** | Android 16 (Linux Kernel 6.12) |
| **Security Patch** | **Dec 2025 / 2026 Latest Security Patch Level** |
| **Root Solution** | **KernelSU (KSU)** (No BL unlock required) |
| **Hook Engine** | **LSPosed** (Injected into `system_server`) |
| **Physical Display** | 1272 x 2800 @ 560 DPI (Auto-mirrored by daemon) |

---

### Exposed Tools & Interfaces

1. **CLI Bus (`/system/bin/vd`)**:
   - `vd start` / `vd stop` / `vd status`: Virtual display lifecycle management.
   - `vd launch <pkg>`: Launch application directly onto the background display.
   - `vd tree`: Output structured accessibility UI hierarchy and clickable node coordinates in JSON.
   - `vd tap <x> <y>`: Inject touch events directly to the target display.
   - `vd type "<text>"`: Inject text into the focused field without soft keyboard popups.
   - `vd swipe <x1> <y1> <x2> <y2> [duration]`: Simulate drag/swipe gestures or brush strokes.
   - `vd key <keycode>`: Send key events (e.g. 4 for BACK, 3 for HOME, 66 for ENTER).
   - `vd screenshot [path]`: Take a direct frame capture of the virtual display.

2. **HTTP / REST Gateway (Port 3070)**:
   - `GET /`: Visual web snapshot monitor with a manual refresh and a state-aware display toggle (shows "关闭副屏" while the display is running and "开启副屏" once it is stopped; greyed out when the gateway is unreachable). The snapshot area sizes itself to the browser viewport, so the whole frame is visible without scrolling.
   - `GET /api/status`: JSON display status.
   - `GET /api/screenshot`: Current frame of the virtual display. Answers with the daemon's cached **JPEG** (full 1272x2800, ~270 KB) while the display runs, and falls back to the `screencap -p` **PNG** path when the daemon is down or has not produced a frame yet. The daemon already owns the display's output surface, so caching a frame costs ~18 ms of encode against the ~1.8 s of CPU `screencap -p` spends in the PNG encoder — 2520 ms to 63 ms end to end, measured.
     The cached frame MUST stay at the display's full resolution: the screenshot tool derives the scale factor it reports to the vision model from these pixel dimensions, so serving a downscaled frame would silently shift every tap coordinate.
   - `GET|POST /api/start`: Bring the virtual display up on demand.
   - `POST /api/stop`: Safely release virtual display resources.

---

### Ecosystem & Companion DSH Preset

- **Native DSH Agent Preset**:
  Check out **[dsh-preset-mobile-use](https://github.com/AcidGr/dsh-preset-mobile-use)**, our official DeepSeek Harness agent preset that interacts with this module to provide sliding-window image context offloading and visual autonomous control.
- **MCP (Model Context Protocol) Support**:
  All foundational tools are exposed via `vd` and REST endpoints. Developers can easily build an MCP server wrapper on top of this foundation for Claude Desktop or Cursor.

---

## License

MIT License.
