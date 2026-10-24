# MiAuto 0.3.8 — 2026-10-24

- **僵死画面防护真的生效了。** 0.3.7 的触发条件要求 `deviceID == "-1"`，但实测真实断联时
  设备标识往往仍是有效值（`dev=099b29`），只有状态机掉回 `STATE_-1` —— 条件从不命中，
  车机依旧冻在最后一帧。现改为：投屏已开始、状态掉回 -1 即开 2 秒确认窗；窗口内协商
  仍在推进（状态 1..3 或 connecting progress > 0）则撤销。本端主动断开不受影响。
- **通道角标修复。** 首帧可以从 SDK `onSurfaceReady()` 这条路进来，此时像素采样路径
  会因 `surfaceReady` 已置位提前返回，`resolveTransportLabel()` 永远不执行 —— 实测
  0.3.7 装机后全程无 `[通道]` 日志、角标不显示。两条首帧路径现在都判定通道。

# MiAuto 0.3.7 — 2026-10-24

- **USB 优先终于生效。** 原实现用 `UsbManager.getAccessoryList()` 判断 —— 它只返回已进入
  AOA accessory 模式的设备，而手机插上车机时以 MTP 模式枚举成 UsbDevice，判定恒 false，
  功能从未触发（实测报告里 0 条「USB 优先」日志）。现在检查 UsbDevice 列表；receiver 同时
  监听 `USB_DEVICE_ATTACHED/DETACHED`；拔出改为 2.5 秒去抖后恢复广播（实测手机插线时
  USB 会以 1~2 秒间隔 ATTACH/DETACH 抖动）。
- **通道角标。** 投屏页左上角常显当前数据通道（"USB AOA 有线" / "P2P Wi-Fi 5GHz"，
  频段经反射 `WifiP2pGroup.getFrequency` 尽力获取）；等待页状态行拼接同一描述。
- **僵死画面防护。** 投屏期间出现 `dev=-1 → STATE_-1`（SDK 连手机的设备标识都拿不到）
  且持续 2 秒 → 判定手机端异常离开：断开会话、投屏页自动退出、等待页重新广播，
  不再把最后一帧冻在屏幕上直到断电。

# MiAuto 0.3.6 — 2026-10-24

- **触屏终于修好 —— 是我们自己的 bug。** 0.3.5 的 native 日志抓取让真实拒绝原因现形：
  `UibcManager: sendTouchEvent failed: count error!`。SDK 要求
  `ids.length == xs.length == ys.length == count`，而 IccoaTouch 传的是固定 16 格的
  内部缓冲，count 却是真实手指数（单击就是 1）—— 16 ≠ 1，每一刀都非法，与通道无关。
  现在调用前按 count 截取数组。这也解释了「旧版本触摸是好着的」：旧实现按 count
  拷贝数组，重写时回归了这个细节。

# MiAuto 0.3.5 — 2026-10-24

- **native 层 UIBC 日志进报告。** `UibcManager.sendTouchEvent` 的最后一跳是
  `SinkNative.onUibcEvent` —— native 方法，实现在 `libovmsink.so`。它返回 false 时
  Java 层看不到原因，但 native 栈会打 `uibc->started, connect failed!`、
  `uibc->event, ECONNRESET, EPIPE!`、`send datalen check failed` 以及协商出的
  `wfd_uibc_capability` 行 —— 这些全都匹配不上 `easylog_` 过滤器，0.3.4 的报告
  恰好在触屏死掉的那一层是盲的。现在报告补抓这些行（上限 300）。
- **libusbio 加载预检。** 实测出现过安装包在设备上损坏：`/data/app/.../libusbio.so`
  读出全零（`bad ELF magic: 00000000`），主线程启动即崩、连崩 9 次。现在 App 启动时
  抢先加载该库，失败时打明确诊断（文件头字节 + 重装指引），不再留一个裸的
  `UnsatisfiedLinkError`。
- **构建期 ELF 校验。** 新增 `verifyNativeLibsElf` gradle 任务：打包前校验 jniLibs
  里所有 `.so` 的前 4 字节，坏文件直接 fail build。

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

