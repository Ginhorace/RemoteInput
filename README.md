# RemoteInput

把一部 Android 手机变成键鼠输入设备，用来控制电脑等主机。两条链路可选：

| 链路 | 手机侧 | 主机侧 | 指针 |
| --- | --- | --- | --- |
| **经典蓝牙** | 注册成蓝牙 HID 设备（键鼠组合） | 配对即用，**不需要装任何软件** | 鼠标（相对位移） |
| **局域网（USB/IP）** | 在本机端口上监听，扮演一个 USB HID 设备 | 装一个 USB/IP 客户端后 `attach`（Windows 用 [usbip-win2](https://github.com/vadimgrn/usbip-win2)） | 触摸板（绝对坐标，支持多指） |

连上后主机把它当成普通的键盘 + 指针设备。两条链路**互斥**：一条占住时，另一条自动让位。

## 它解决什么问题

Android 从 9 开始提供 Bluetooth HID Device Profile，手机可以「扮演」蓝牙键盘。同类应用大多停在「能敲英文」这一步，真正麻烦的是**非 ASCII 字符**：

HID 键盘只能上报**物理按键**，没有「直接发送一个字符」的通道。要在主机上打出中文、日文或任意 Unicode 字符，只能借宿主系统自带的输入方案间接实现 —— 例如 Windows 的 Alt Code、macOS 的 Unicode Hex Input、Linux 的 `Ctrl+Shift+U`、RichEdit 的 `Alt+X`。这些方案各自要求特定的按键节奏和位数，手工记不现实。

RemoteInput 把「字符 → 按键序列」这套做法固化成**可编辑的宏模板**：输入一段文本，应用按模板把它翻译成一串按键上报给主机，宿主的输入法再把它还原成字符。

## 两条链路

### 经典蓝牙

手机注册成蓝牙 HID 设备（报告 ID 1 = 鼠标、2 = 键盘），电脑扫描配对后直接可用。适合临时接管一台机器 —— 主机侧什么都不用装。

这条链路只做**鼠标**、不做触摸板：触摸板（PTP）需要主机读两条 Feature 报告，而这条链路上第一条 `GET_REPORT` 会被 Android 协议栈吞掉，主机只能等约 5 秒超时重发 —— 已经配对的设备会让电脑开机卡十几秒，应用层绕不过去。

### 局域网（USB/IP）

手机当**设备端**，在 TCP 端口（默认 `3240`，可在设置里改成 `1024–65535`）上监听；电脑当**主机端**，用一个现成的 USB/IP 客户端把它挂载成键鼠设备。不需要蓝牙，也不需要同一账号或同一系统。

这条链路用**触摸板**（绝对坐标 PTP，支持多指手势）+ 键盘，比相对位移的鼠标更好用。

Windows 侧的客户端用 [vadimgrn/usbip-win2](https://github.com/vadimgrn/usbip-win2)，按它自己的说明装好驱动后（以下命令需管理员权限，`<手机IP>` 换成应用里显示的本机地址）：

```powershell
usbip list -r <手机IP>                        # 看到 RemoteInput 的设备
usbip attach -r <手机IP> -b 1-1               # 挂载，成功后应用自动进入控制页
usbip port                                    # 查看已挂载的端口
usbip detach -p <端口号>                      # 用完摘掉
```

应用里的局域网卡片会把 `usbip attach -r <IP> -b 1-1` 这一行直接显示出来，照着敲即可。

Linux 侧用内核自带的 `usbip` / `usbip-utils`，命令一致。

## 功能

### 三种控制模式

| 模式 | 说明 |
| --- | --- |
| 键盘 | 完整键盘界面：字母、数字、功能键、小键盘、方向键，支持组合键与修饰键 |
| 指针 | 同一个槽位按链路渲染：**经典蓝牙是鼠标页**（左右键、滚轮、拖动），**局域网是触摸板页**（绝对定位、点击、拖动、多指） |
| 模板 | 把一段文本按宏模板展开成按键序列发送 |

### 键盘

- 键位与键面文字跟随所选的**宿主键盘布局**（内置 9 张布局图例表），也可跟随应用语言自动选择
- 组合键（`Ctrl+Shift+u` 这类）与长按；切换模式时会补一帧空报告，避免主机以为按键还按着
- 键盘灯：Num / Caps / Scroll 三个按钮，灯态只认主机下发的 Output Report，未确认时画空心圈
- 无障碍：键盘界面提供语义层，支持触摸探索与读屏

### 模板（宏模板系统）

- 用一套小 DSL 描述「输入一个字符要按哪些键」：`tap` / `down` / `up` / `sleep`、组合键、`<code>` 占位符（可设补零位数）
- 内置预设 7 个：Blank、OEM Alt Code、ANSI Alt Code、Windows Hex Numpad、RichEdit Alt+X、macOS unicode Hex (unverified)、Linux Ctrl+Shift+U
- 编辑器带诊断：错误 / 警告分级标注，加载失败时弹窗；单条步骤可停用（注释掉）
- 支持试发与展开预览，预览与实际发送共用同一套展开逻辑
- 两条 Alt Code 的默认字符集按界面语言自动选宿主码页，其余预设按各自方案写死

### 字符集与编码检查

- 内置 14 项：11 个 Windows 代码页（GBK、Big5、windows-31j、windows-949、windows-1252、IBM437、IBM850、windows-1251、IBM866、windows-1256、IBM720）+ Unicode（完整码点）/ UTF-16 / US-ASCII
- 支持新增自定义字符集，也可禁用不需要的项
- 发送前做编码检查：当前字符集编不出来的字符会先弹确认框（指出第几个字符、字符本身、`U+xxxx`），可选择【仍然发送】

### 其他

- 界面语言 12 种（另可跟随系统）
- 主题：深色 / 浅色 / 跟随系统，6 套固定色相方案，另可跟随系统取色（Android 12+）
- 连接在后台保持：切到其他应用、退出控制页都不会断开（连接由进程级单例持有，前台服务保活）
- 局域网链路电脑挂上来时会自动进入控制页一次

## 运行要求

| 项 | 要求 |
| --- | --- |
| Android | 12（API 31）及以上 |
| 蓝牙链路 | 设备须支持蓝牙 HID Device Profile（`BluetoothHidDevice`）。部分机型 / ROM 不支持，应用会明确提示 |
| 局域网链路 | 手机与电脑在同一网络；电脑需装 USB/IP 客户端（Windows 用 [usbip-win2](https://github.com/vadimgrn/usbip-win2)，Linux 用 `usbip-utils`） |

蓝牙链路的权限及用途：

- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`（后者带 `neverForLocation`）：扫描并连接主机
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：连接或监听期间把进程钉在前台。否则退到后台的普通进程随时可能被系统回收，HID 链路随之断开
- `POST_NOTIFICATIONS`：显示连接期间的常驻通知（Android 13+ 需用户授权）。拒绝只影响通知可见性，连接仍由前台服务维持
- `INTERNET`：仅局域网链路使用（本机监听一个 TCP 端口）

## 构建

需要 JDK 21 与 Android SDK。

```bash
git clone <仓库地址>
cd RemoteInput
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

也可以直接用 Android Studio 打开项目。产物在 `app/build/outputs/apk/debug/`。

release 构建默认开启 R8 代码裁剪与资源收缩（APK 约 2.95 MB）；依赖版本集中在 `gradle/libs.versions.toml`。源码包名是 `com.github.remotecontrol`，实际安装的包名（`applicationId`）是 `com.github.ginforace.remoteinput`，两者有意解耦 —— 换 `applicationId` 等同装成另一个应用，旧数据与已配对设备都不会跟过来。

## 使用

**蓝牙**

1. 打开应用，授予蓝牙权限
2. 扫描并选择主机
3. 在主机侧确认配对
4. 连接建立后进入控制页，在三种模式之间切换

**局域网（USB/IP）**

1. 手机与电脑连到同一网络（建议同一个可信局域网 —— 这条链路是明文 TCP，没有鉴权）
2. 应用首屏下方点「开始监听」，记下显示的 `本机IP:端口`
3. 电脑上执行 `usbip attach -r <手机IP> -b 1-1`
4. 挂载成功后应用自动进入控制页

**模板**

首次用模板输入非 ASCII 字符前，先在设置里选好与宿主匹配的字符集与预设；不确定时可先「试发」看展开结果。

## 项目结构

```
app/src/main/java/com/github/remotecontrol/
├─ MainActivity.kt            首屏：蓝牙扫描 + 局域网卡片
├─ HidControlActivity.kt      两条链路共用的控制页外壳
├─ SettingsActivity.kt        设置页
├─ HidForegroundService.kt    连接 / 监听期间的前台服务（保活 + 常驻通知）
├─ hid/                       共享会话层：HidSession、状态、链路互斥、键盘灯同步
├─ bluetooth/                 蓝牙 transport + 扫描页与蓝牙控制页外壳
├─ usbip/                     局域网 transport：线协议、虚拟 USB 设备、URB 队列、监听服务端
├─ ui/                        Compose 界面（两条链路共用一份）
│   ├─ keyboard/              键盘界面与键盘语义层
│   ├─ template/              模板编辑器
│   └─ theme/                 主题与配色
├─ util/                      常量、偏好、字符集清单、宿主键盘布局、报告描述符
│   └─ template/              宏模板解析与展开引擎
└─ viewmodel/                 设置页 ViewModel
```

## 已知限制

- 蓝牙链路依赖 HID Device Profile，设备不支持时无法使用；部分主机 / 系统对「手机当键盘」有额外确认步骤。
- 蓝牙链路的指针只有**鼠标**（相对位移），没有触摸板：PTP 在这条链路上会让主机等 5 秒超时，取舍见上文。
- 局域网链路同一时刻只允许**一台电脑**挂载（第二台导入会得到「设备忙」）；断网 / 电脑休眠后要重新 `attach`。
- 局域网链路是**明文 TCP、无鉴权**，只适合可信局域网；不要在公网暴露监听端口。
- 输入非 ASCII 字符要靠宿主系统自带的输入方案（Alt Code / Unicode Hex Input / `Ctrl+Shift+U` / `Alt+X` 等）配合模板实现，并非直接发送字符。宿主需要相应设置（例如 macOS 需先添加 Unicode Hex Input 输入法），且并非所有程序都接受这套输入方式。
- 「选错代码页」检测不出来：发送前的编码检查只能判断当前字符集编不编得出来，无法判断宿主实际用的是哪一页。
- 锁屏 / 熄屏时连接会断开（未持有 wake lock，属有意取舍）；进程被系统回收后不会自动重连。
- 报告的 `VID:PID` 目前是 `0x1209:0x0001`（pid.codes 的测试 PID），正式分发前应换成申请到的唯一 PID。

## 相关项目

- [vadimgrn/usbip-win2](https://github.com/vadimgrn/usbip-win2) —— Windows 上的 USB/IP 客户端与驱动。局域网链路在 Windows 侧就用它 `attach`；本项目只实现服务端（DEVLIST / IMPORT 握手与 URB 收发），**不含也不分发它的任何代码或驱动**，使用前请按该项目的说明安装。

## 许可

[Apache License 2.0](LICENSE)
