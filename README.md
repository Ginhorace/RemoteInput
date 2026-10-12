# RemoteInput

把一部 Android 手机变成蓝牙 HID 输入设备 —— 无线键盘 + 触摸板，用来控制电脑等主机。

连上后，主机会把它当成一个普通的蓝牙键盘和触摸板，**主机侧不需要安装任何软件**。

## 它解决什么问题

Android 从 9 开始提供 Bluetooth HID Device Profile，手机可以「扮演」蓝牙键盘。同类应用大多停在「能敲英文」这一步，真正麻烦的是**非 ASCII 字符**：

HID 键盘只能上报**物理按键**，没有「直接发送一个字符」的通道。要在主机上打出中文、日文或任意 Unicode 字符，只能借宿主系统自带的输入方案间接实现 —— 例如 Windows 的 Alt Code、macOS 的 Unicode Hex Input、Linux 的 `Ctrl+Shift+U`、RichEdit 的 `Alt+X`。这些方案各自要求特定的按键节奏和位数，手工记不现实。

RemoteInput 把「字符 → 按键序列」这套做法固化成**可编辑的宏模板**：输入一段文本，应用按模板把它翻译成一串按键上报给主机，宿主的输入法再把它还原成字符。

## 功能

### 三种控制模式

| 模式 | 说明 |
| --- | --- |
| 键盘 | 完整键盘界面：字母、数字、功能键、小键盘、方向键，支持组合键与修饰键 |
| 触摸板 | 唯一的指针控制方式：移动、点击、拖动、多指手势（默认进入此模式） |
| 模板 | 把一段文本按宏模板展开成按键序列发送 |

### 键盘

- 键位与键面文字跟随所选的**宿主键盘布局**（内置 9 张布局图例表），也可跟随应用语言自动选择
- 组合键（`Ctrl+Shift+u` 这类）与长按
- 键盘灯：显示主机下发的 Num / Caps / Scroll 状态
- 无障碍：键盘界面提供语义层，支持触摸探索与读屏

### 模板（宏模板系统）

- 用一套小 DSL 描述「输入一个字符要按哪些键」：`tap` / `down` / `up` / `sleep`、组合键、`<code>` 占位符（可设补零位数）
- 内置预设：Blank、OEM Alt Code、ANSI Alt Code、Windows Hex Numpad、RichEdit Alt+X、macOS unicode Hex (unverified)、Linux Ctrl+Shift+U
- 编辑器带诊断：错误 / 警告分级标注，加载失败时弹窗；单条步骤可停用（注释掉）
- 支持试发与展开预览，预览与实际发送共用同一套展开逻辑

### 字符集与编码检查

- 内置 14 项：11 个 Windows 代码页（GBK、Big5、windows-31j、windows-949、windows-1252、IBM437、IBM850、windows-1251、IBM866、windows-1256、IBM720）+ Unicode（完整码点）/ UTF-16 / US-ASCII
- 支持新增自定义字符集，也可禁用不需要的项
- 发送前做编码检查：当前字符集编不出来的字符会先弹确认框（指出第几个字符、字符本身、`U+xxxx`），可选择【仍然发送】

### 其他

- 界面语言 12 种（另可跟随系统）
- 主题：深色 / 浅色 / 跟随系统，6 套色相方案
- 连接在后台保持：切到其他应用、退出控制页都不会断开（连接由进程级单例持有，前台服务保活）

## 运行要求

| 项 | 要求 |
| --- | --- |
| Android | 12（API 31）及以上 |
| 设备 | 必须支持蓝牙 HID Device Profile（`BluetoothHidDevice`）。部分机型 / ROM 不支持，应用会明确提示 |
| 主机 | 任何支持蓝牙键盘 / 触摸板的系统（Windows、macOS、Linux 等） |

权限及各自用途：

- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`（后者带 `neverForLocation`）：扫描并连接主机
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE`：连接期间把进程钉在前台。否则退到后台的普通进程随时可能被系统回收，HID 链路随之断开
- `POST_NOTIFICATIONS`：显示连接期间的常驻通知（Android 13+ 需用户授权）。拒绝只影响通知可见性，连接仍由前台服务维持

## 构建

需要 JDK 21 与 Android SDK。

```bash
git clone <仓库地址>
cd RemoteInput
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

也可以直接用 Android Studio 打开项目。产物在 `app/build/outputs/apk/debug/`。

release 构建默认开启 R8 代码裁剪与资源收缩。依赖版本集中在 `gradle/libs.versions.toml`。

## 使用

1. 打开应用，授予蓝牙权限
2. 扫描并选择主机
3. 在主机侧确认配对
4. 连接建立后进入控制页，在三种模式之间切换
5. 首次用模板输入非 ASCII 字符前，先在设置里选好与宿主匹配的字符集与预设

## 项目结构

```
app/src/main/java/com/github/remotecontrol/
├─ MainActivity.kt          扫描 + 连接入口
├─ ControlActivity.kt       控制页（键盘 / 触摸板 / 模板）
├─ SettingsActivity.kt      设置页
├─ HidForegroundService.kt  连接期间的前台服务（保活 + 常驻通知）
├─ ui/                      Compose 界面
│   ├─ keyboard/            键盘界面与键盘语义层
│   ├─ template/            模板编辑器
│   └─ theme/               主题与配色
├─ util/                    常量、语言清单、宿主键盘布局目录等
│   └─ template/            宏模板解析与展开引擎
└─ viewmodel/               HID 连接状态机与各页 ViewModel
```

## 已知限制

- 依赖蓝牙 HID Device Profile，设备不支持时无法使用。
- 输入非 ASCII 字符要靠宿主系统自带的输入方案（Alt Code / Unicode Hex Input / `Ctrl+Shift+U` / `Alt+X` 等）配合模板实现，并非直接发送字符。宿主需要相应设置（例如 macOS 需先添加 Unicode Hex Input 输入法），且并非所有程序都接受这套输入方式。
- 「选错代码页」检测不出来：发送前的编码检查只能判断当前字符集编不编得出来，无法判断宿主实际用的是哪一页。
- 指针控制只有触摸板，没有独立的鼠标模式，也没有媒体键：HID 报告描述符上限 256 字节，注册时的组合描述符已用 227 字节，放不下鼠标描述符。
- 锁屏 / 熄屏时连接会断开（未持有 wake lock，属有意取舍）。
- 进程被系统回收后不会自动重连。

## 许可

[Apache License 2.0](LICENSE)
