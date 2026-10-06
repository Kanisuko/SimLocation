# SimLocation

作者 **Kanisuko** · 主应用包名 `org.ethertaco.simlocation` · [AGPL-3.0-only](LICENSE)。

[源码仓库](https://github.com/Kanisuko/SimLocation) · 优先适配 Redmi K60 / HyperOS 3 / Android 15。

Android Root 位置与路径模拟应用。界面使用 Kotlin / Compose / Miuix，定位服务与运动算法使用 Java。

0.4.0 提供五个页面：**地图 / 路线 / 实时 / 回放 / 设置**。

- 地图选择位置、编辑节点，GPX 导入导出，路线与参数保存。
- 实时模式选择起点、点击添加途经点或手绘路径，运行中继续追加；按运动模型回放，不以手指速度瞬移。
- 配速范围、相关速度波动、加减速限制、转弯预判与周期停留；随机种子可复现。
- GPS / network 独立开关、精度和更新节奏，可同时启用。
- 设置中的功能验证读取 GPS / network / fused 回调，显示位置偏差、回调年龄、mock 状态与速度图表；WiFi / 基站显示实际缓存观测。
- 小幅页面切换动画，暂停调参、停止清理测试源，前台服务回放。

当前主应用使用系统测试定位接口，保留 mock 标记。network 提供者不等于 WiFi / 基站原始观测注入。
WiFi / 基站注入仍处于系统后端适配阶段。独立现代 LSPosed 探针已在 K60 安装并启用，
API 102 运行时已验证四个系统查询入口和基站异步回调；探针原样返回真实数据，不实现观测注入。
项目不承诺绝对不可检测，不实现目标应用的 Hook 检测隐藏。

[Android 构建与使用](android/README.md) · [系统后端适配方案](android/SYSTEM_BACKEND.md) · [第三方组件](android/THIRD_PARTY_NOTICES.md)。

仓库仅保留 Android SimLocation。原桌面 Python/Web 服务、ADB 工作台、旧项目测试与入口已移除；Leaflet 资源直接随 Android 应用打包。
