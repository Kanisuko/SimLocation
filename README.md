# SimLocation

作者 **Kanisuko** · 主应用包名 `org.ethertaco.simlocation` · [AGPL-3.0-only](LICENSE)。

[源码仓库](https://github.com/Kanisuko/SimLocation) · 优先适配 Redmi K60 / HyperOS 3 / Android 15。

Android Root 位置与路径模拟应用。界面使用 Kotlin / Compose / Miuix，定位服务与运动算法使用 Java。

0.5.2 提供五个页面：**地图 / 路线 / 实时 / 回放 / 设置**，启动时自动检测应用 Root 权限；基础速度参数始终可见，可选系统后端启动前验证当前配置回执。

- 地图选择位置、编辑节点，GPX 导入导出，路线与参数保存。
- 实时模式选择起点、点击添加途经点或手绘路径，运行中继续追加；按运动模型回放，不以手指速度瞬移。
- 配速范围、相关速度波动、加减速限制、转弯预判与周期停留；随机种子可复现。
- GPS / network 独立开关、精度和更新节奏，可同时启用。
- 可选现代 LSPosed 系统后端：WiFi 扫描与连接观测、LTE / NR 缓存、异步查询与基站订阅；可独立开关，支持场景 JSON 导入导出。
- 设置中的功能验证读取实际观测，与活动配置逐项比较，显示定位回调、mock、最近／单次位置与系统接口报告。
- 小幅页面切换动画，暂停调参、停止清理测试源，前台服务回放。

Root 回放仍使用系统测试定位源，默认保留 mock 标记。可选系统后端提供按接收应用修改 mock 属性的实验开关。
系统后端默认只作用于本应用，额外目标由用户填写包名；仅回放期间生效，停止或 12 s 心跳超时恢复原结果。
WiFi / 基站数据需要对应地点的场景；样例标识不能转换为所选经纬度，真实无线连接和公网 IP 不变。
0.5.0 已在 K60 / HyperOS 3 / LSPosed API 102 上通过仅本应用的样例数据替换、Location 属性和租约恢复测试；第三方定位 SDK 与其他设备尚未验证。
项目不承诺绝对不可检测，不实现目标应用的 Hook 检测隐藏。

[Android 构建与使用](android/README.md) · [计划与剩余功能](ROADMAP.md) · [系统后端适配方案](android/SYSTEM_BACKEND.md) · [第三方组件](android/THIRD_PARTY_NOTICES.md)。

仓库仅保留 Android SimLocation。原桌面 Python/Web 服务、ADB 工作台、旧项目测试与入口已移除；Leaflet 资源直接随 Android 应用打包。
