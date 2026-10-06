# SimLocation · Android Root Edition

作者 **Kanisuko** · `org.ethertaco.simlocation` · [AGPL-3.0-only](../LICENSE)。最低 Android 15，优先适配 Redmi K60 / HyperOS 3。

## 0.4.0 使用

安装后到“设置 / 配置”验证 Root，向 SimLocation 授予精确位置与通知权限。ADB shell 的 su 授权不能替代应用授权。

- **地图**：选择单点并模拟，或点击添加 / 拖动路线节点。
- **路线**：管理节点，导入 / 导出 GPX（最多 10000 节点、8 MB）。
- **实时**：先选起点，再自动点击途经点或手绘；开始后可追加。队列结束等待追加，启用运动模型时会先减速。
  平滑转弯在开始时生成，运行中的手绘追加仅处理新段，不改动已提交的旧路径。
  手绘起笔位置与队尾不同，会连接两者；绘制时地图拖动关闭，自动模式可拖动地图。
  不自动规划道路，曲线可能离开路面；草稿保留在当前 Activity，保存为路线后可持久化与导出。
- **回放**：启动保存的路线，暂停 / 继续，往返循环，更新间隔与随机种子。
- **设置**：组合 GPS / network 开关，设置各自精度、network 最短更新间隔，配置运动模型。
  定位源下次启动生效；运动模型可在暂停后保存生效，实时模式不循环。
  定位更新间隔同时影响发送节奏，network 不会快于全局发送间隔。
- **设置 / 功能验证**：独立监听 GPS / network / fused 实际回调，展示偏差、年龄、精度、mock 与速度；
  展示 WiFi / 基站缓存数量与 WiFi 连接 RSSI，不主动扫描；可点击“检查基站异步回调”发起一次实际查询。
  观测显示不等于已注入数据。
  速度图表来自模型，统计窗口最多 120 个样本；更新间隔为 500 ms。

配速以 `分:秒` 输入，默认巡航 3:00–9:00，目标 6:00。速度标准差默认 0.8 km/h，方差为其平方。
速度波动采用相关随机目标与固定 50 ms 步长，限制加减速，预判转弯与终点停车。
巡航配速限制不要求起步、停留、转弯与终点减速保持巡航最低速度。
周期停留是减速目标时段，实际静止时间取决于制动过程，不识别现实红绿灯。
暂停停止运动时钟；暂停按钮将回报速度设为零，继续时使用暂停前的模型速度。

橙点是最近成功写入的模拟位置。验证页的坐标来自本应用实际回调，不证明所有第三方应用采用同一定位源。
底图来自在线 OpenStreetMap，网络失败会提示。坐标使用 WGS84；系统地理编码可搜索地点。
未启用的真实定位源可能与测试位置冲突；当前 mock 标记保留，传感器、GNSS 原始数据、WiFi/基站观测和公网 IP 未模拟。

停止会移除测试源并请求恢复原 AppOps；异常退出后重新打开并停止可重试清理。
运行时持有有超时的 CPU 唤醒锁。HyperOS 请保留最近任务；尚未覆盖长时间息屏、系统强杀和所有融合定位场景。

## 构建与检查

已有 JDK 17+、Android SDK platform 35、build-tools 和 Python 3.11+。构建脚本只使用 Python 标准库。
Gradle Wrapper 固定 9.6.0，AGP 9.3.2，Compose 编译器 2.2.10，Miuix 0.5.1。
首次需要下载 Maven / Gradle 依赖。Windows 示例：

```powershell
$env:ANDROID_HOME = 'C:\Users\用户名\AppData\Local\Android\Sdk'
python android/build.py
android\gradlew.bat -p android :app:lintDebug --console=plain
```

脚本运行 RouteTest / PlaybackTest / MotionTest，构建并验签，输出
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

## 本轮验证（2026-10-07）

Redmi K60 / HyperOS 3，通过无线 ADB 安装主 APK，检查五 Tab 与地图。
配速启用、自动节点、手绘、实时追加、暂停位置冻结、GPS / network / fused 实际回读与停止清理已通过短时测试。
配速算法检查覆盖时间步长一致性、加减速度限制、波动、暂停、路径追加、终点零速度及曲线端点。
主应用与探针 Android Lint、地图浏览器回归、APK 构建与主 APK 验签通过。
系统探针使用 API 101.0.1 构建，同一源码通过 API 102.0.0 编译检查；
LSPosed 2.2.0（7854）的实际运行时 API 为 102。系统框架与电话服务加载、四个查询入口命中、
异步回调返回均已在该设备验证。未测试 API 101 真机运行时、其他 ROM 或未来 API。
