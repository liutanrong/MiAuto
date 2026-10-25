# MiAuto 0.3.14 — 2026-10-25

- **冷启动重绑窗口 2500 → 4000ms。** 0.3.13 首份实测（07:38）：空配退役生效 ——
  协商 90→100 从 ~1900ms 降到 264ms，第 2 场首帧 progress100+211ms 秒到。
  但第一场仍被冤杀一次：原生 logcat 实锤 video 管线初始化
  （onCastVideoInitialized / onWfdSinkReady）在 startCast 后 ~2.55s 才完成，
  2.5s 窗口在管线就绪前 50ms 就到期。参照第 2 场模式
  （onWfdSinkReady → ~200ms 出首帧），第一场大概率能自然出画。
  4s 给足「init → 首帧」余量；最坏情况比原来多等 1.5s 才走
  重绑→断开重播的老兜底。

# MiAuto 0.3.13 — 2026-10-25

- **离屏空配退役（首场黑的真正元凶）。** 07:17 两次冷启动实测：空配的
  setOutputSurface 交接不真正生效 —— 第 1 场视频管线已初始化、真实屏幕像素恒 0
  （同样的手机内容第 2 场像素 14），且 prime 把 SDK 的 surface-ready 状态标记掉，
  onSurfaceReady 首场永不回调；断开重连后 SDK 重建解码器，第 2 场秒好。
  而空配想防的「首帧被清队列」在现时序下不存在：投屏页 progress 10 自动打开，
  真实 startCast 距第一帧约 5.8s，configure 直接在真实 Surface 上完成、关键帧无损。
  现改为真实 Surface 直接投；空配代码保留、开关默认关闭。
- 预期：冷启动第一场一次连上，不再有第 2 场。

# MiAuto 0.3.12 — 2026-10-25

- **首屏判定重构：「无像素即死」退役，改为重绑优先。** 06:39 两次冷启动的 logcat
  实锤：被 900ms 判死的第一场里 `onCastVideoInitialized` / `onWfdSinkReady` /
  `uibc->started!` 全部正常到达 —— 手机一直在推流，只是首连时手机端画面（CarWith
  深色确认页）暗到像素采样读 0。旧判据把好会话冤杀，逼出「重建 3 轮才连上」。
  现在：2.5s 无首帧信号 → 先做一次**无感重绑**（pauseCast(false)+重投，不拆链路）；
  连续两窗（≈5s）仍无信号才断会话重播（快判初衷保留，换掉失明的眼睛）。
- **秒断彻底退役**：第二场 progress 10 起投、700ms 被掐同样是冤杀
  （logcat 同帧可证流在跑）。首屏判定全部交给看门狗 + 12s 起投兜底。
- 像素采样降级为纯正向信号：采到非黑 → 立即确认成功撤遮罩；全黑 → 不作为失败证据。

# MiAuto 0.3.11 — 2026-10-25

- **首连不再"重建 3 轮才连上"。** 秒断（progress 10 + 700ms）的前提是旧版本的
  "首场解码器未预热、手机必不推流"；离屏空配落地后该前提已不成立，首场完全可能
  出画面，却被 700ms 无条件掐死 —— 至少重建 2 轮起步。现改为：空配成功时跳过秒断，
  交回冷启动看门狗（progress 90 + 1300ms）按像素判据裁决；秒断仅保留为空配失败时的
  兜底，且触发窗口放宽到 progress 10..20（首个进度事件有时是 20）。
- **补上"协商卡在 90 以下 = 无任何计时器"的黑屏空洞。** 首屏看门狗只在 progress
  90/100 排程，秒断只认 10~20，45s 协商超时又被 castStarted 短路 —— 起投后卡在
  30~80 的会话黑屏可无限持续。新增起投兜底看门狗：startCast 成功后 12s 未确认画面
  即断会话重播；45s 协商超时改以 surfaceReady 为闸（出过画面才豁免）。
- **空配失败可重试**：handoffPrimed 只在空配真正成功后置位（旧写法先置位后尝试，
  失败不回滚，本进程后续永远补不上空配）。
- **空配不再抢真实输出面**：真实投屏已开始时跳过空配（否则 doAdvertise 的 +600ms
  延迟任务会把解码器输出切到离屏面，车机瞬间黑屏）。
- 冷启动看门狗 900ms → 1300ms：首帧判据要求距 startCast ≥600ms + 250ms 采样周期，
  原窗口对"起投偏晚"的场次余量不足，存在误杀。
- USB 接入时打印设备 VID:PID 列表：isUsbAttached() 把任何 UsbDevice 都算"手机插线"，
  U 盘/记录仪也会关掉秒断，先留证据。

# MiAuto 0.3.10 — 2026-10-24

- **终结"连上 2 秒就被杀"的无限循环。** SDK 在 progress 100 之后必然回调一次
  `CONNECTED -> STATE_-1` 的过渡值（实测每个正常会话都有，首帧出完它也照来），而此时
  progress 已到顶、不会再有事件来撤销 —— 0.3.9 在这里 2 秒后必误杀，手机其实一直连着，
  于是「连上 2 秒 → 退出 → 自动重连 → 再杀」循环（实测会话 5、6、7 连环）。判据改为：
  距 progress 100 不足 3 秒的 -1 一律视为过渡值跳过；稳态期的 -1 才武装 2 秒确认窗。
- **通道角标移到右下角、字号 12sp → 10sp。** 左上角容易压住车机原生状态信息。

# MiAuto 0.3.9 — 2026-10-24

- **僵死防护不再误杀冷启动，投屏页不再"永远进不了"。** 0.3.8 实测暴露两个问题：
  ① 首场冷启动的正常恢复流程（900ms 无像素 → 秒断 → 重播）会撞上 2 秒确认窗，
  投屏页被误杀；② `linkLost` 标志在重连成功后不被清除（SDK 重连走 STATE_11，
  不在清位条件 1..3 内），投屏页此后每次打开都立刻退出。修复：防护只在
  「本进程画面曾经确认出过」（everSurfaceReady）之后武装；新一轮协商开始
  （progress 1..20）即清除 linkLost；本端主动断开撤销待发的判定。

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

