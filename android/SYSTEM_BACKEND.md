# SimLocation 系统后端适配方案

作者 Kanisuko · AGPL-3.0-only · 调查日期 2026-10-07。

目标设备：Redmi K60，用户当前的 HyperOS 3 / Android 15。现有 Root 定位回放继续独立工作；LSPosed 是可选组件。

## 现代 API 与作用域

采用 `io.github.libxposed.api.XposedModule`，入口为 `META-INF/xposed/java_init.list`，
声明 `minApiVersion=101`、`targetApiVersion=101`、`staticScope=true`。
系统入口为 `onSystemServerStarting`，电话进程入口为 `onPackageReady`。
不使用旧 `XposedBridge`、`XposedHelpers`、`assets/xposed_init`，不注入第三方应用进程。

官方已发布 API 101.0.1 与 102.0.0。使用两者分别编译同一套源代码检查兼容；
只调用共同的 101 接口。框架运行时 API 版本另行记录，不根据 LSPosed 的产品版本号推断。
这不构成对未来所有 API 版本的兼容承诺。API 102 的热重载等扩展暂不启用。

作用域固定为 `system`（LSPosed 系统框架特殊作用域）和 `com.android.phone`。WiFi 服务位于 APEX，
需要在 `SystemServiceManager.startServiceFromJar(String,String)` 完成加载后取服务 ClassLoader，
不能假定 system_server 最初的 ClassLoader 可以找到 WiFi 类。

## K60 实际 ROM 已核对的接口

| 信号 | 系统类 / 方法 | 实际返回形态 | 当前阶段 |
|---|---|---|---|
| WiFi 扫描 | `com.android.server.wifi.WifiServiceImpl.getScanResults(String,String)` | `com.android.wifi.x.com.android.modules.utils.ParceledListSlice`，不是普通 List | 实际查询命中 |
| 已连接 WiFi | `WifiServiceImpl.getConnectionInfo(String,String)` | `WifiInfo`，包含权限脱敏 | 实际查询命中 |
| 基站缓存 | `com.android.phone.PhoneInterfaceManager.getAllCellInfo(String,String)` | LTE/NR 等 CellInfo 列表 | 实际查询命中 |
| 主动基站查询 | `PhoneInterfaceManager.requestCellInfoUpdate(int,ICellInfoCallback,String,String)` | 异步回调 | 请求命中，本应用收到实际回调 |
| 基站持续订阅 | TelephonyRegistry / PhoneStateListener 回调链 | 异步事件 | 尚未核对与实现 |
| GPS / network 坐标 | APK LocationManager 系统测试源 | Location，保留 mock | 主应用已实现 |

观测替换后的权限检查、缓存、异步回调、已连接 WiFi 与扫描列表仍需要单独验证。
只修改同步 `getAllCellInfo` 不能覆盖基站订阅；只修改旧版 WiFi 的 List 返回值也不适用于此 ROM。

## 独立探针与真机验证

`system-probe` 生成独立包 `org.ethertaco.simlocation.systemprobe`。
APK 输出为 `artifacts/simlocation-android/SimLocation-system-probe-api101.apk`。
它在系统侧 Hook 上表的四个查询入口，原样执行并返回原方法。
仅对调用参数中包名为 `org.ethertaco.simlocation` 的成功请求记录一次接口名称及返回类型；
不记录 AP、基站标识、位置、其他应用的调用内容，也不修改参数、结果或权限。

2026-10-07 已获用户授权安装和启用，重启后在 K60 / HyperOS 3 / Android 15 验证。
LSPosed 2.2.0（7854）日志报告实际 API 102，系统框架与电话服务均已加载模块。
WiFi APEX 加载器、四个方法签名及主应用实际请求均通过日志核对。
验证页读取到了 WiFi 扫描缓存、WiFi 连接 RSSI 和基站缓存；
点击“检查基站异步回调”后，请求入口命中且本应用收到实际基站观测。
这是调用链与现代 API 兼容性验证，不是模拟数据替换验证。未在 API 101 真机运行时测试。

最初误选 `android` 时仅电话进程加载；改用 `system`（管理器中的“系统框架”）后，
system_server 与 WiFi 查询才命中。模块元数据已修正为 `system` 与 `com.android.phone`。
验证页不主动发起 WiFi 扫描；缓存观测不证明扫描刷新、持续订阅或第三方应用接口覆盖。

## 观测注入下一阶段

1. 建立可编辑场景：路线、运动参数、GPS/network 参数、WiFi AP 集合、LTE/NR 基站集合。
   各项独立开关，可组合启用；同类来源每次查询只有一个最终结果，避免多个配置互相覆盖。
2. 主应用与模块使用现代 libxposed service / remote preferences 通信。
   不使用世界可读文件，不放宽 SELinux。停止回放、心跳超时和配置失效立即回到原系统数据。
3. 先限制到 SimLocation 验证进程的调用 UID，逐一验证同步返回与异步回调。
   通过后再提供明确的目标应用选择，而不是在安装时改变所有应用的系统结果。
4. 验证页分别呈现“请求的配置”“系统接口命中”“本应用收到的观测”“未覆盖的接口”。
   没有模块、签名不符或数据构造失败时报告未支持，并保留原方法。

AP 和基站标识本身不是经纬度。任意编造 BSSID、MCC/MNC、TAC、CI/NCI，
无法保证地图服务的数据库把它们解释为选中的位置。需要对应地点的合法观测场景数据，
并随路线更新信号强度、邻区、连接状态与时间戳；不能仅填一个坐标就宣称所有无线观测一致。

## 其他需要区分的信号

| 来源 | 含义与边界 |
|---|---|
| 融合定位 / 第三方定位 SDK | 可以组合 GPS、WiFi、基站、缓存和服务器数据；不等同于单一 network 提供者 |
| BLE 信标 / Bluetooth 扫描 | 商场或室内 SDK 可能使用；需单独的数据场景与系统接口 |
| GNSS 原始测量、卫星状态、NMEA | 坐标 Location 对象没有覆盖这些数据 |
| 加速度、陀螺仪、方向、计步、气压 | 提供运动、方向和高度线索；配速模型目前只影响位置与速度，不产生传感器事件 |
| 公网 IP / 网络路由 | “移动数据位置”若指 IP 地理定位，需要改变网络出口；修改 WiFi/CellInfo 不会改变公网 IP |
| UWB / WiFi RTT | 依赖硬件与室内设施，支持情况需逐项检测 |

系统侧 Hook 避免向目标应用注入本模块，但不保证设备环境、侧信道或数据矛盾不可被识别。
本项目不承诺绝对不可检测，也不实现应用的 Hook 检测隐藏。

参考：
- [LSPosed 现代模块文档](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [libxposed API 官方发布元数据](https://repo.maven.apache.org/maven2/io/github/libxposed/api/maven-metadata.xml)
- [Android WiFiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)
- [Android TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager)
