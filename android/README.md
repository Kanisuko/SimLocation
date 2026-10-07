# SimLocation · Android Root Edition

作者 **Kanisuko** · `org.ethertaco.simlocation` · [AGPL-3.0-only](../LICENSE)。最低 Android 15，优先适配 Redmi K60 / HyperOS 3。

## 0.5.2 使用

打开应用后自动通过 `su id -u` 检测 Root；首次使用仍需在 Root 管理器允许应用授权。已授予的系统权限不会重复请求。
检测失败可到“设置 / 设备与权限”重试，或单独检查精确位置、通知和电话状态权限。ADB shell 的 su 授权不能替代应用授权。

- **地图**：选择单点并持续发送，使用所设更新间隔且速度为零；运行时可直接停止单点。或点击添加 / 拖动路线节点。
- **路线**：管理节点，导入 / 导出 GPX（最多 10000 节点、8 MB）。
- **实时**：先选起点，再自动点击途经点或手绘；开始后可追加。队列结束等待追加，启用运动模型时会先减速。
  平滑转弯在开始时生成，运行中的手绘追加仅处理新段，不改动已提交的旧路径。
  手绘起笔位置与队尾不同，会连接两者；绘制时地图拖动关闭，自动模式可拖动地图。
  不自动规划道路，曲线可能离开路面；草稿保留在当前 Activity，保存为路线后可持久化与导出。
- **回放**：启动保存的路线，暂停 / 继续，往返循环，更新间隔与随机种子。
  本页直接选择基础速度／配速模型；基础速度、波动与周期始终显示且可编辑，配速开启时额外显示配速范围、目标和方差。
  配速模型开启时，基础参数保留但不参与计算，关闭模型后生效；未启用模型的字段不参与校验。运行中暂停后保存，参数与模型切换会立即应用。
- **设置**：组合 GPS / network 开关，设置各自精度、network 最短更新间隔，配置运动模型。
  定位源下次启动生效；运动模型可在暂停后保存生效，实时模式不循环。
  定位更新间隔同时影响发送节奏，network 不会快于全局发送间隔。
- **设置 / 无线观测与系统后端**：独立开启 WiFi 扫描、WiFi 连接、LTE / NR、mock 属性选项，编辑或导入场景 JSON。
  默认仅作用于本应用，额外目标以包名填写；启动回放后生效。样例均为测试标识，无法映射为地图选中的地点。
- **设置 / 功能验证**：监听 GPS / network / fused 实际回调，展示偏差、年龄、精度、mock 与速度；
  WiFi / 基站实际标识与强度对照活动场景，展示系统接口命中报告，支持基站异步查询和持续订阅。
  “检查最近 / 单次位置”分别调用 Android 的缓存与一次性定位接口。不主动发起 WiFi 扫描。
  速度图表来自模型，统计窗口最多 120 个样本；更新间隔为 500 ms。

配速以 `分:秒` 输入，默认巡航 3:00–9:00，目标 6:00。速度标准差默认 0.8 km/h，方差为其平方。
速度波动采用相关随机目标与固定 50 ms 步长，限制加减速，预判转弯与终点停车。
巡航配速限制不要求起步、停留、转弯与终点减速保持巡航最低速度。
周期停留是减速目标时段，实际静止时间取决于制动过程，不识别现实红绿灯。
暂停停止运动时钟；暂停按钮将回报速度设为零，继续时使用暂停前的模型速度。

橙点是最近成功写入的模拟位置。验证页的坐标来自本应用实际回调，不证明所有第三方应用采用同一定位源。
底图来自在线 OpenStreetMap，网络失败会提示。坐标使用 WGS84；系统地理编码可搜索地点。
未启用的真实定位源可能与测试位置冲突。传感器、GNSS 原始数据、真实无线连接、SIM 和公网 IP 未模拟。
mock 属性默认保留，实验开关只在所选应用的 Android LocationManager 接收路径处理位置副本。
独立 SDK 的缓存、Google Play services 定位和 WiFi NetworkCapabilities 回调尚未适配，不能宣称所有来源均已覆盖。

停止会移除测试源并请求恢复原 AppOps；异常退出后重新打开并停止可重试清理。
运行时持有有超时的 CPU 唤醒锁。HyperOS 请保留最近任务；尚未覆盖长时间息屏、系统强杀和所有融合定位场景。

## 构建与检查

已有 JDK 17+、Android SDK platform 36（独立探针仍用 35）、build-tools 和 Python 3.11+。构建脚本只使用 Python 标准库。
Gradle Wrapper 固定 9.6.0，AGP 9.3.2，Compose 编译器 2.2.10，Miuix 0.5.1。
首次需要下载 Maven / Gradle 依赖。Windows 示例：

```powershell
$env:ANDROID_HOME = 'C:\Users\用户名\AppData\Local\Android\Sdk'
python android/build.py
android\gradlew.bat -p android :app:lintDebug --console=plain
```

脚本运行 RouteTest / PlaybackTest / MotionTest / SessionPolicyTest，构建并验签，输出
`artifacts/simlocation-android/SimLocation-root-debug.apk`。
开发签名密钥保存在同目录且不提交；正式发行需单独配置稳定签名。

地图回归 `python tests/browser_android_map.py` 需要自行准备 Playwright / Edge 测试环境，拦截瓦片网络，验证点击、拖动、只读、手绘、尺寸变化与失败提示。

## 可选系统探针

这是独立模块 `org.ethertaco.simlocation.systemprobe`，不属于主应用的启动依赖。
详见 [系统后端适配方案](SYSTEM_BACKEND.md)。仅观察接口，不实现 WiFi / 基站结果注入。
已获用户授权安装、启用并重启验证。K60 的 API 102 运行时已命中四个系统查询入口，
基站异步查询也返回了实际观测；未对第三方应用或观测替换作兼容性声明。

```powershell
android\gradlew.bat -p android :system-probe:assembleDebug
android\gradlew.bat -p android :system-probe:compileDebugJavaWithJavac '-PxposedApi=102.0.0'
```

默认以现代 API 101.0.1 构建，另用 102.0.0 做源码编译检查。未来版本仍需单独验证。
框架提供 API 类，APK 不打包旧 Xposed API 或新 API 库本身。
作用域为 `system`（LSPosed“系统框架”）和 `com.android.phone`，不能用 `android` 代替系统框架作用域。

## 0.5.0 系统后端启用

主 APK 同时包含可选现代模块，不依赖独立探针提供功能。
在 LSPosed 启用 **SimLocation**，仅勾选“系统框架”和“电话服务”，重启后打开主应用，检查现代框架连接与系统接口报告。
独立 **SimLocation System Probe** 只记录调用，不替换数据；可以保留或自行停用。
升级主 APK 后仍需重启以重新加载系统模块；检测到旧版系统报告会阻止启用了系统后端选项的回放，并明确提示重启。
本轮未重启的升级测试未收到新的无线替换结果，因此不能把旧版接口报告当作当前配置已生效的证据。
同一版本的覆盖安装也观察到配置替换失效。0.5.2 发布配置后，通过受权限保护的系统查询触发回执：
系统框架必须确认当前会话，启用基站时电话服务也必须确认；4 s 内未收到匹配版本／本次启动／会话 token 的回执，则取消配置并停止初始化，提示重启。
回执仅证明模块接受了当前配置；具体数据替换和第三方采用仍要另行验证。功能验证页显示当前／历史配置回执。
后端使用 libxposed service 101 与 remote preferences，不使用世界可读文件或修改 SELinux。
WiFi／基站替换继续执行原查询的权限检查；空缓存、脱敏或不可用数据保持原结果，接口构造失败也回退并报告。
停止会清空配置租约，应用停止发布心跳后最多 12 s 失效，启动计数变化也使旧租约失效。

场景格式、接口覆盖与测试边界见 [WIRELESS_SCENARIOS.md](WIRELESS_SCENARIOS.md)。

配置解析的 Android 真机测试不需要启用 LSPosed：

```powershell
android\gradlew.bat -p android :app:assembleDebugAndroidTest
adb -s <连接地址> install -r android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <连接地址> shell am instrument -w org.ethertaco.simlocation.test/org.ethertaco.simlocation.BackendTestRunner
```

显式追加 `-e mode e2e` 才运行系统替换测试。它需现代模块已经启用、Root／精确位置／电话状态权限，以及可读取的真实 WiFi 与基站缓存。
测试会暂用仅本应用的样例场景并启动单点回放，检查 WiFi、LTE/NR、异步／订阅、Location mock 属性及 12 s 租约恢复，结束恢复原场景并停止。
运行前先停止已有回放；测试 APK 不随正式应用安装。

显式追加 `-e mode ui` 运行界面到服务的回归：不手动验证 Root 即启动，单点持续发送与指定更新间隔，
忽略未启用模型的无效字段，以及暂停后切换基础速度模型并继续。暂时关闭无线后端，结束恢复运动／定位源／无线设置并停止。
`-e mode map` 则暂时仅为 `com.autonavi.minimap` 开启 mock 属性选项，在上海测试点持续发送 45 s 并打开高德；
须单独观察地图实际采用，命令的配置解析 PASS 不能作为高德定位通过的结论。结束恢复原设置并停止。
`-e mode map-route` 在相同地点和系统选项下对照 15 km/h 路线；静止属性诊断模式 `map-bearing` / `map-no-speed` 分别补方向、省略 GPS 速度字段，
临时额外发布 GPS 结果；`map-noise` 对照约 2 米范围的静止测量误差，报告速度仍为零。
这些模式仅供排查，不代表主应用默认行为，也不能代替地图采用验证。

## 本轮验证（2026-10-07）

Redmi K60 / HyperOS 3，通过无线 ADB 安装主 APK，检查五 Tab 与地图。
配速启用、自动节点、手绘、实时追加、暂停位置冻结、GPS / network / fused 实际回读与停止清理已通过短时测试。
配速算法检查覆盖时间步长一致性、加减速度限制、波动、暂停、路径追加、终点零速度及曲线端点。
主应用与探针 Android Lint、地图浏览器回归、APK 构建与主 APK 验签通过。
系统探针使用 API 101.0.1 构建，同一源码通过 API 102.0.0 编译检查；
LSPosed 2.2.0（7854）的实际运行时 API 为 102。系统框架与电话服务加载、四个查询入口命中、
异步回调返回均已在该设备验证。未测试 API 101 真机运行时、其他 ROM 或未来 API。

上述探针结论属于 0.4.0。0.5.0 主 APK 系统后端另行完成以下检查：

- API 101.0.1 构建与 102.0.0 编译检查、四组纯 Java 测试、Android Lint 和 APK 验签通过。
- K60 上 Android 场景解析与 Location 副本测试通过，共 25 项检查。
- API 102 真机端到端测试仅作用于本应用：WiFi 扫描与连接字段、LTE / NR 缓存、异步回调与基站订阅均收到样例数据。
- GPS 持续回调、最近位置与单次位置收到 `mock=false` 的副本；原始对象保留标记。
- 停止心跳后，12 s 租约到期恢复 WiFi / 基站原结果与 GPS 的 mock 标记；测试结束停止回放、恢复模拟定位权限及原场景。

修复了电话服务启动早期错误缓存系统 Context 的问题，等待 PhoneApp 初始化后再报告能力。
HyperOS 的测试前台启动通过 instrumentation 的 shell 通道执行；普通应用使用正常启动方式。
未验证第三方应用、第三方融合定位 SDK、其他机型或 API 101 真机运行时；上述 GPS 结论不等同于所有定位来源均已覆盖。

0.5.1 补丁的四组纯 Java 检查、APK 构建与验签、主应用 Lint、API 102 编译检查通过。
K60 真机界面到服务回归及 25 项配置检查通过：自动 Root、GPS / network 单点持续回调与零速度、
指定发送间隔、未启用模型的字段不参与校验、暂停切换模型并继续，结束恢复原配置与 AppOps。
高德单点采用尚未确认；系统 fused 缓存仍可能保留真实坐标，不能仅凭本应用收到测试点认定第三方已采用。
升级但尚未重启时，系统后端端到端复测在 WiFi 替换步骤失败，旧启动报告没有新的会话命中；
保留版本一致性检查，重启加载当前 APK 后需重新验证 WiFi／基站与高德单点采用。

随后重启加载 0.5.1 的 API 102 系统模块，WiFi／LTE／NR／Location 属性和租约恢复端到端复测通过。
高德对照仍未通过单点检查：GPS / network 系统缓存为测试点，系统记录有高德 GPS 订阅与投递；
仅启用位置属性选项并把高德加入目标列表后，固定点画面仍在真实位置，15 km/h 路线画面则进入上海测试路线。
补方向字段的静止对照也未改善；这只确认测试中静止与运动结果有差异，尚未证明具体 SDK 过滤条件。
连接恢复后，省略速度字段及有界静止测量误差的对照也未改善高德显示。
当前目标配置为空时仍只处理本应用，样例无线标识也不对应上海地点；不能把这些结果解释成已定位到某一具体 SDK 过滤条件。
用户另观察到地图打开时短暂显示测试点，约 0.5 s 后切回真实位置；下一步按时间比较缓存初始化和后续定位结果，确认覆盖来源。

0.5.2 已通过四组纯 Java 检查、构建与验签、Lint 和 API 102 编译；真机 UI 回归与 25 项配置检查通过。
基础速度三个字段始终可见、可编辑。重启后的 API 102 系统端到端验证通过：系统框架／电话服务的当前会话回执、
无关 token 拒绝、WiFi／LTE／NR／Location 属性替换、12 s 租约到期和停止恢复。
高德的瞬时测试点随后被真实位置覆盖仍未修复，不把本应用的端到端通过作为高德持续采用的证明。
