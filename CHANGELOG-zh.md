# MiAuto 0.3.4 — 2026-10-24

- **USB 下触摸不再被静默拒绝。** 反编译 `UibcManager.sendTouchEvent` 发现三道前置检查；
  其中 `mSourceWidth/mSourceHeight` 只可能由 `onWfdSinkReady ← onCastVideoInitialized`
  这条链赋值，而 CastListener 的三个回调全是 Wi-Fi Display 专属。走 AOA（USB）时
  这条链不触发，source size 停在 0，每一刀都被 SDK 拒掉 —— 而方控 `sendKeyEvent`
  不查这两项，所以按键照常。0.3.4 在 `startCast` 成功后（以及每次触摸被拒时，有预算制）
  反射调用 `UCarAdapter.updateVideoSourceSize(videoW, videoH)`，让 source 与 display
  相等、坐标 1:1。同一个懒补也修掉无线会话中「手机上报的源尺寸与视频画布不一致」
  造成的坐标错位。
- **UIBC 状态进报告。** 新增 `[UIBC]` 快照行（source/display 尺寸、加密标志、sessionID，
  全部反射自 SDK 的 UibcManager），附在 `[能力]` 行后面、每次触摸被拒时也会打 ——
  `source=0x0` 从此是直接证据，不用再从行为反推。
- **崩溃记录不再丢失。** 每次未捕获异常写一份带时间戳的 `crash-<ts>.txt`（保留最近 5 份），
  头部含版本号/板子/线程/完整栈；下次启动时摘要注入 AppLog，随诊断报告一起导出。
  报告同时抓取系统崩溃缓冲（`logcat -b crash`），能覆盖 Java handler 抓不到的
  native crash（SinkNative/解码器段错误）。
- **加固：** 组播锁获取失败只记一次（原先每次进投屏页刷一条，白占报告 6000 行预算）；
  音量调节加 `SecurityException` 兜底（勿扰模式下锁音量的定制 ROM 会抛）。

# MiAuto 0.3.3 — 2026-10-24

- **USB 优先加了回退。** 插着配件但 10 秒内没有 AOA 建链（只供电的线，或手机不肯切 accessory
  模式）时，会自动恢复蓝牙广播。没有这条兜底的话，界面会一直停在「等待手机」、连配对码都不出现。

