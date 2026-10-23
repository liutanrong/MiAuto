# MiAuto 0.2.1 — 2026-10-24

针对 DiLink 3.0（比亚迪秦 PLUS DM-i 2021 / Android 10 / 骁龙 665）的加固版本。
以下每一条都由实机日志驱动，不是猜测；逆向与实测依据见 `docs/ICCOA-RUNTIME-FINDINGS.md`。

- **无线通道改为只声明一种。** 同时声明 Wi-Fi Direct 与 SoftAP 时，SDK 会按手机期望值二选一，
  一旦对不上就 `notifyConnectFailed(3)` → 10003，表现为卡在 progress 10 约 15 秒。
  现在跟随「网络通道」设置，只声明一种。
- **SoftAP 不再让配置崩溃。** 空 5GHz 信道表会抛 `channels can't be null`，
  `buildConfig()` 直接失败，界面上连配对码都不出现。现在只在 Wi-Fi Direct 分支设置。
- **USB（AOA）真正可用。** 上游从未调用 `enableUsbDeviceDetection(true)`；
  另外 Android Auto 栈有四处会抢读 accessory bulk 端点，已全部让开。
- **DPI 跟随面板、帧率跟随设置。** 1920×990 约 172dpi（原来写死 320 导致车机端元素偏小）；
  FPS 读「FPS Limit」设置，665 上可降到 30，无需单独编译。
- **触控回传。** `sendTouchEvent()` 一直存在但从未被调用，车机上点什么都没反应。
  现在坐标按视频画布归一化后回传。
- **连接期间不再黑屏。** 用首帧探针（PixelCopy 64×36 统计非黑像素）替代 SDK 的
  `onSurfaceReady()`——后者在正常会话里根本不会触发。投屏页在确认出画前一直盖着不透明品牌页。
- **冷启动首场快速判死。** 进程启动后的第一次连接，手机不推视频流（五轮实测证据见文档）。
  现在在 `progress 90 + 900ms` 无像素就断开重连，而不是在一个注定全黑的画面上等 9 秒。
- **画质相关：** 低延迟解码模式真正生效（此前重复的 setter 把它覆盖成了 false）；
  投屏期间持有 Wi-Fi 高性能锁与组播锁。

已知限制：冷启动后的首次连接仍需一次自动重连。这是手机端行为，车机侧无法修复。

