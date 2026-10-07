# 无线观测场景与接口覆盖

0.5.0 · Kanisuko · AGPL-3.0-only。

设置中导入／导出的 JSON 为 `version: 1`。所有开关默认关闭；测试样例仅包含虚构标识。
WiFi 扫描、连接与基站可以同时启用，GPS/network 定位源仍在另一配置卡片独立选择。
一个场景可以包含多个 AP、多个 LTE／NR 服务或邻区基站；同一次查询返回一份统一结果。

| 场景字段 | 说明 |
|---|---|
| `name` | 场景名称 |
| `wifiScan` / `wifiConnection` / `cellEnabled` / `clearMock` | 独立布尔开关，最后一项为实验性位置属性修改 |
| `targets` | 额外应用包名数组；本应用始终可用于验证，空数组表示仅本应用 |
| `connectedWifi` | 连接 AP 在 `wifi` 数组中的索引，从 0 开始；未指定为 -1 |
| `wifi` | 最多 64 个 AP：`ssid`、`bssid`、`rssi`（dBm）、`frequency`（MHz）、`capabilities` |
| `cells` | 最多 32 个基站，字段如下 |

基站必填：`type`（LTE / NR）、`mcc`、`mnc`（字符串，保留前导零）、`id`（LTE CI / NR NCI）、
`tac`、`pci`、`arfcn`（LTE EARFCN / NR NRARFCN）、`rsrp`、`rsrq`、`sinr`（dB）。
可选：`bands`、`bandwidth`（LTE kHz）、`operator`、`registered`、`connectionStatus`（0 未连接、1 主服务、2 次服务）。
只能指定一个主服务基站；已连接项必须 `registered=true`。强度、标识、频率、长度与类型在两端校验。
扫描和基站时间戳在系统查询时生成，避免导入历史时间戳被当作新观测。

AP／基站标识需要对应地点的合法观测数据。软件不能从经纬度推算厂商位置数据库中的 BSSID 或基站编号。
当前场景是固定无线观测集合；沿路线动态切换邻区与场景、根据距离衰减信号，尚未实现。

| 系统接口 | 0.5.0 实现范围 |
|---|---|
| WifiServiceImpl.getScanResults | 原查询返回非空缓存后，使用该 ROM 的 ParceledListSlice 类型返回场景 AP |
| WifiServiceImpl.getConnectionInfo | 在有权限且实际已连接时复制 WifiInfo，替换 SSID/BSSID/RSSI/频率；真实连接、MAC/IP 等其他属性保留 |
| PhoneInterfaceManager.getAllCellInfo | 有可用原始位置标识时替换 LTE / NR 列表 |
| PhoneInterfaceManager.requestCellInfoUpdate | 包装回调，原请求与错误照常执行；回调到达时再次检查租约和请求所属会话 |
| TelephonyRegistry.listenWithEventList | 对指定应用的基站监听回调替换数据，保留 Binder 身份、权限与其他事件；已有第三方订阅可能需重新订阅 |
| LocationProviderManager 接收注册 | 按接收应用复制 GPS/network/fused mock 位置，实验性改为 false；系统过滤与权限检查继续执行 |
| LocationManagerService.getLastLocation | 同样处理本会话的最近位置副本 |

系统模块只注入 `system` / `com.android.phone`。目标应用列表是系统侧数据筛选，不能把目标应用添加到 LSPosed 模块作用域。
Root 回放默认仍有 mock；去掉单个属性不隐藏 Root／LSPosed 环境，也不保证数据与传感器一致或 SDK 无法识别。

尚未覆盖：WiFi NetworkCapabilities/NetworkCallback 中的 WifiInfo、旧 CellLocation、其他无线制式、Google Play services 与厂商 SDK 缓存、BLE、GNSS 原始观测、传感器、网络出口。
直接 radio/modem 数据也不会被此后端改变。未覆盖的来源必须在验证时单独检查，不能将已覆盖查询等同于全部定位链路。

租约同时校验系统启动计数、单调时钟和墙上时钟，期限 12 s，心跳每 3 s。暂停回放仍维持位置和租约，停止或租约过期恢复原系统结果。
不要求查询方拥有 Root；改变查询结果的可选后端本身需要现代 LSPosed 的系统作用域。

验证页将“配置一致性”和“系统接口报告”分开：真实数据偶然一致不能证明接口已替换；只报告 installed 也不能证明应用已收到模拟观测。
当前回读显示的标识仅供用户本机核对；模块日志不输出 AP／基站标识或经纬度。
