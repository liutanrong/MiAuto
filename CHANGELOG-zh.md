# MiAuto 0.3.22 — 2026-10-30

**三个假设被数据否掉；这一版让"断音那一刻"第一次能被看见**

10/30 的报告把三条线索全部排除了，这不是坏消息 —— 省下了三轮瞎调：

| 假设 | 读数 | 判定 |
|---|---|---|
| CPU 降频 / 爬频太慢 | `gov=performance`，8 核 1804/2016 MHz，64 个采样点无一例外 | **否** |
| 音频焦点被反复抢占 | 三场共 30 次回调，每场只有 `0→1` 起播 + `-5` 释放，无反复 | **否** |
| 音频通道反复重建 | CAST_MUSIC 通道分别存活 227s / 27.5s / 584s，每场只建一次 | **否** |

第一场尤其干净：TTS 在 +27s 起播、+38s 结束，之后 CAST_MUSIC 从 +43s 一直活到 +270s
**227 秒无人打扰**。这一场里导航没抢过焦点，通道没重建过，CPU 满频 —— 声音照样断。
剩下能指向的只有一条：**音频包在空口排队等视频包**。

- **A1 会话期间常驻音频追踪（AudioTrace）** —— 这是本版唯一真正重要的事。
  前三份报告的音频段都只有 **4 秒**（0.3.21 是 128 行 / 3.87 秒）。0.3.21 把配额从
  500 提到 760，方向就是错的：**配额不是瓶颈，logcat 环形缓冲长度才是**。
  `UCar_AudioQueueManager-pcm` 每秒打十几条 `buffer queue size: 10`，几秒就把本进程
  的缓冲灌满，`logcat -d` 再读也只能读到尾巴。现在会话一开始就把 logcat 挂上，
  边产生边收，自己维护 1500 行环形缓冲 —— 导出时拿到的是**整场**，不是最后 4 秒。

- **A2 水位只在变化时记录** —— 队列稳定在满水位时一行不记，掉到 9 / 7 / 0 时每次
  变化都记。既省空间，又恰好是最该看的那几行。同时按 stream 分别统计
  （TTS 满值 5、MUSIC 满值 10，不能用同一个阈值），会话结束打一行：

  ```
  [音频] 本场水位：STREAM_CAST_MUSIC 满 10 / 最低 3 / 见底 0 次 / 低于半满 12 次
  ```

  这一行就是「这一场断了几次、断到多低」的量化答案，报告头部也会带一份。

- **A3 崩溃段不再误导** —— 10/29、10/30 两份报告的 `[crash]` 都是
  `version=0.3.18-debug` 的旧栈，读起来像当轮又崩了。现在只有崩溃版本等于当前
  版本时才展开全文，否则只列异常行并标注来源版本。

- **A4 su 确认未授权** —— `isAppGrantedRoot=false`。所以 0.3.21 的 A3
  （`dumpsys media.audio_flinger` 读 underrun）在这台车机上永远拿不到数，
  由 A2 的水位统计替代。CPU 满频开关本来也不需要 —— 车机本来就是 performance。

**没有改投屏链路。** 三个假设刚被否掉，此时动帧率、动码率、动分辨率都是盲调，
只会把下一份报告的读数搅浑。

**下一轮请做这个 A/B（App 内就有，不用刷机）：**

设置 → 视频分辨率，从当前 **720p** 切到 **480p**，跑一场 10 分钟以上，
再切回 720p 跑一场。两份报告对比：

- 480p 的 `最低 / 见底 / 低于半满` 明显更小 → 空口带宽与突发是主因
- 两份持平 → 空口无辜，只剩 SDK 内部，那时该抓车机侧

注意 `投屏尺寸` 那行现在写的是 `resolutionId=0`（auto → 1280x672）。
之前车机上改的分辨率**没有通过 SDK 协商下来**，等于从来没真正降过。

# MiAuto 0.3.21 — 2026-10-29

**断音收窄：数据没丢，是时序抖动；这版先装仪表，再给一把可选的手术刀**

10/29 的实测反馈是「还断，但有缓解」，补充的三个特征是：**随机无规律、每次很短、
之后一下全补齐**。这三条合在一起，把"丢包"这个方向彻底排掉了 —— 单向实时流丢了就是
永久缺口，补不回来；能补齐说明数据全在，只是晚了。瓶颈从"带宽"移到"时序"。

USB 不卡、Wi-Fi 卡，差的正是「每一包都要打断一次 CPU」。骁龙 665 车机版通常跑
schedutil/powersave：平时低频，音频线程被唤醒时得先等频率爬升，那几十毫秒正好
够把 AudioTrack 抽干。本版不直接押注这个假设，而是先补观测，再给一个可开关的手段。

- **A1 音频日志过滤重写**：0.3.20 的报告里音频段只剩 118 行 / 4 秒 ——
  `UCar_AudioQueueManager-pcm: buffer queue size: 10` 每秒刷十余条，把 500 行配额
  全吃掉了，`wait buffer to fill` 一条没进来。现在把「队列满」的常规行按 30:1 抽样，
  额度全让给异常行，上限 760。不改这个，后面每一轮都是在盲猜。

- **A2 会话内 CPU 采样**：每 10 秒一行，8 核实时频率 + governor：
  ```
  [CPU] gov=schedutil | 1804 1804 800 800 1804 800 800 800 MHz
  ```
  断音是亚秒级事件，抓不到现场；但"断的那一刻频率是多少"可以从时间轴反推。

- **A3 AudioFlinger 欠载计数**：会话结束时 `dumpsys media.audio_flinger`，只取
  `underrun` 行。logcat 是环形缓冲，采样窗口再窄也不影响累计值 —— 相邻两场做差，
  就是"这一场被抽干了几次"。有增长 = 硬件缓冲真的空了；没有 = 问题在别处。

- **A4 su 身份探针**：会话建立时打一行 `id`。没有它就是一直在猜有没有 root。

- **B CPU 档位开关（默认关）**：设置 → 连接设置 → 「声音流畅度」。打开后会话期间
  切 `performance`，断开立刻还原；原值记账，App 启动时兜底还原一次，进程被杀也不会
  让车机长期锁在满频。没有 su 或内核不支持时自动跳过并记一行原因，不影响投屏本身。

- **构建修复**：`injectIccoaDex` 读 SDK 路径时把「已设置但为空」的环境变量当成有效值，
  `file("")` 解析成项目根目录 → 静默退到硬编码的 Windows 路径。现在跳过空白值，
  并支持从 `local.properties` 的 `sdk.dir` 取值。

验证要点见 Release 说明。

# MiAuto 0.3.20 — 2026-10-28

**崩溃根因找到 + 四项诊断修正**

0.3.19 该生效的都生效了：`WifiLock(LOW_LATENCY)` 拿到、组频段从 5180MHz（ch36）跳到
**5745MHz（ch149）**、MulticastLock 建链即释放，D2 也终于跑起来了 ——
`[重建] 已释放 VideoPlayerV2(VideoPlayerV2@b9d527c)` 之后 **158ms** 就出画。
本进程的第一场不再需要"断开重连"那一轮。

这轮补上同一份报告暴露出来的四件事，外加一个真正的崩溃根因。

- **崩溃根因（实锤）**：不是解码器、不是竞态，是 Direct Boot 解锁时序：
  ```
  Unable to create application ... IllegalStateException: SharedPreferences in credential
  encrypted storage are not available until after user is unlocked
      at Settings.getPrefs(Settings.kt:29)
      at AppComponent.<init>(AppComponent.kt:18)
      at App.onCreate(App.kt:47)
  ```
  开机自启发生在车机解锁之前，而 `App.onCreate` 一路摸到 `component.suExecutor` →
  构造 `AppComponent` → 读 SharedPreferences。三处都堵上：`component` 的访问移进
  `isUserUnlocked()` 分支；`Settings` 退回 `createDeviceProtectedStorageContext()`；
  `debugForceMemoryProfile` 用 `runCatching` 兜。
- **A3 轮结果仍有 1/2 次写"通道=未知"**：固定延后 600ms 不够 —— 有一场 state=4 和 state=2
  相隔 **850ms**。改成轮询等 `connectType` 落定，上限 2 秒。
- **首帧计时跨会话串场**：`tProgress100` 在 `onSurfaceDestroyed` 后没清，恢复会话打出
  `首帧 距 progress100 152095ms`。两个计时器都清零。
- **链路采样第三条路**：本机 `/proc/net/dev` 同样被挡（0.3.19 打的就是「采样不可用」）。
  改用 `TrafficStats.getTotalRxBytes/getTotalTxBytes` 兜底 —— 全机计数，标签写「全机」，
  不冒充 p2p0。
- **音频焦点丢失不再抢**：`focus=-5` 出现在断开瞬间，这时候 `allowGainAudioFocus()` 是反的。
  只在非负（GAIN）时放行。

**关于断续本身。** 扩大的 logcat 终于看到音频链路：

```
UCar_AudioPlayer@STREAM_CAST_MUSIC.Play: wait buffer to fill bytes: 57600
UCar_AudioPlayer@STREAM_CAST_MUSIC.Play: buffer fill done, number: 15, bytes: 61440
UCar_AudioQueueManager-aac@STREAM_CAST_MUSIC: buffer queue size: 0, total: 11400
```

音频是 AAC 走网络、车机侧解码，播放器每次起播前要攒 57600 字节（0.3~0.6 秒）的垫子。
抓到的窗口里它在 42.705s、44.271s、44.663s 各攒了一次 —— **1.5 秒内重攒两次**。
垫子反复重建，说明流反复见底，这正是"声音断一下"的物理形态。
这块缓冲在 SDK 内部，我们能动的只有"让数据来得更连续"，也就是 0.3.19 那几条 Wi-Fi 改动的方向。

# MiAuto 0.3.19 — 2026-10-28

**声音断续 + 连接 P0**

这轮的起点是你给的三条对照：Wi-Fi 下声音断续、USB 不卡、降分辨率无效、单独音频也卡。
音频码流约 200 kbps，比视频小 30 倍，它都在断，就不可能是带宽；USB 不卡又不可能是手机端
或解码能力。剩下的只有介质本身 —— P2P 的空口调度。

- **E1-1 WifiLock 改 LOW_LATENCY**：`HIGH_PERF` 只保证射频不全睡，不改变空口排队；
  `WIFI_MODE_FULL_LOW_LATENCY`（API 29，本机 Android 10）才会禁用 PS 省电轮询 ——
  它的代价正是 100ms 量级的周期性延迟尖峰，而 AudioTrack 的缓冲一被抽干就是声音断续 ——
  同时把包放进 WMM 高优先级队列。定制机芯不认这个档位时自动退回 HIGH_PERF。
- **E1-2 会话建立即释放 MulticastLock**：它只在"还没连上、靠组播找设备"时有用。一直持有会让
  芯片每个 DTIM 都醒来收组播帧，白占空口 —— 而这段空口正是音频要抢的。
- **E1-3 只声明 UNII-3**：实测组一直落在 5180MHz（ch36，UNII-1，低功率、通常 20MHz）。
  白名单改为 149/153/157/161。个别只支持 UNII-1 的机型可用 `iccoa-5g-unii3-only=0` 关掉。
- **E2-1/E2-2 别再自己拖自己**：流畅度采样用 PixelCopy 在 video SurfaceView 上 4 次/秒跑 30 秒，
  等于在渲染关键路径上插 GPU 同步点 —— 用制造卡顿的工具去测卡顿。现在默认关闭
  （`iccoa-smooth-probe`），首帧探测也从 250ms×60s 降到 500ms×15s。
- **E3-3 应答音频焦点**：`onAudioFocusChanged` 一直是空实现且不打日志，
  `UCarAudioManager.allowGainAudioFocus()` 从未被调用。现在记日志并无条件放行 ——
  车机上没有别的应用在抢音频。

连接：

- **A1 `stopCast` 是 private**：0.3.18 用 `getMethod("stopCast")`，而它只搜 public 成员；
  解 dex 得到的真身是 `direct PRIVATE V stopCast()`。所以 D2 一次都没跑起来。
  现在改用 `getDeclaredMethod` + `setAccessible`，失败时会把方法名全表打出来，不用再猜。
- **A2 USB 优先死循环**：`usbPriorityFallback` 的守卫用了 `castStarted`，而 AOA 分支只要
  预热解码器就会把它置真 —— 10 秒回退永远不执行，12 秒看门狗于是反复断开：
  10/27 那次 5 轮 / 4 分 37 秒，最后靠手动插拔线才活过来。守卫改为 `lastProgress > 0`。
- **A8** 冷启动窗口 4000 → 2000ms。实测 6 次成功出画都在 43/423/430/433/454/455ms ——
  窗口到期意味着"不会再来"，再等 4 秒只是让用户多盯 2 秒黑屏。
- **A3–A7 诊断**：轮结果等 `connectType` 到达后再打（原来 10/12 次写"通道=未知"）；
  `[通道]` 同一结论不重复刷（原来一场 4 条）；计划重播取最早到期（原来同一毫秒打
  `+5000ms` 和 `+800ms` 两条）；`startCast` 时钟为 0 时打 `n/a`（原来打 20 天的鬼数字）；
  停机态回执不再记成"异常断连"。
- **E5 分辨率真正生效**：`buildConfig()` 每次重算画布，日志里补 `resolutionId`。
  以前只在 Activity 启动时算一次 —— 这就是 10/28 报告里 12 场会话全是 1280x672 的原因。
- **E3-1/E3-2 让取证不再骗人**：链路采样新增 `/proc/net/dev` 通道（本机 sysfs 对
  untrusted_app 不可读，这就是 `[链路]` 从未出现过一次的原因），并输出丢包数与
  RSSI/协商速率/频点。报告新增 AudioFlinger / AudioTrack / WifiP2p / wpa_supplicant 行，
  以及最新一份崩溃文件的前 40 行。
- **B1/B3** 每次重建都记 `VideoPlayerV2@hash` 实例指纹，用来验证"第二场能出画"到底是
  「复用旧解码器」还是「换了新解码器」。

# MiAuto 0.3.18 — 2026-10-26

**D1/D2/D4/D5/D6/D8/D10/D11（D3、D7 本轮不做）**

- **D1 自伤停机修复（本轮最关键）**：SDK 把我们自己的 `disconnect()` 也回调成
  `DISCONNECTED(param=11)`，0.3.17 因此把每次看门狗断开都判成"用户主动断开"→ 停机 →
  只能手动点刷新（10/26 21:19、21:20 两次实测）。现在记 `selfDisconnectAt`：
  3 秒内到达的 `param=11` 视为本端断开回执，按已排程的重播继续；只有无本端记录的 11 才停机。
- **D2 首场专项**：本进程第一场不出画时，先做**会话内重建解码器**——反射
  `UCarAdapter.mCastManager` → `CastManager.stopCast()`（反编译确认它只 `mVideoPlayer.release()`，
  不动 WFD 会话），再 `startCast()` 重建一个新的 VideoPlayerV2。最多 2 次（D11），
  仍失败才回到"重绑 → 断开重播"。
- **D4 AOA 判定**：不再只看 `getAccessoryList()`（本机恒为空）。新增三选一：
  accessory 列表 / `18D1:2D00|2D01` UsbDevice / `usb0|rndis` 接口。
- **D5 通道标签**：`phoneId=-1` 时不再拿上一场设备猜 connectType，明确置"未知"；
  每场会话重置 `connectType`；未知时显示 `P2P Wi-Fi（频段未知）`，绝不贴旧值。
  判定优先级固定为 **AOA 证据 → connectType → 频段**。
- **D6 5G 只提示**：`[5G] 组频段=… 判定=…` 每会话只打一次；2.4G 只改角标，不中断会话。
- **D8 日志去重**：连接轮次结果只记一次（state 4/2 不再各打一条）；断开/停机各只处理一次。
- **D10**：`[连接] 第 N 轮结果=… 通道=USB/P2P/未知 用时=… 阶段=…%`。

# MiAuto 0.3.17 — 2026-10-26

**A 批：连接稳定性（A1–A10）**

- **回调语义修正（A1，本轮根因）**：`onConnectStateChanged(deviceID, state, param)` 的第二参是状态码、
  第三参是附加信息。旧代码当成 `oldState -> newState`，于是 `state=1`（断开）几乎全被漏掉、
  `param=11`（用户主动断开）没有分支 —— 手机走了车机还以为会话在跑，只能等 30s 刷新或 45s 超时。
  现按 1=断开 / 2=已连 / 3=失败 / 4=投屏已连 / 5=投屏已停 / 6,7=手机桌面前后台 处理；
  state=5 先挂 3s 等 state=1 带原因，等不到按异常断连处理。
- **指数退避（A2）**：新增 `ReconnectBackoff`，1.2s 起、×2、封顶 8s，只对 10003/10006/10008；
  不再用固定 1s 去撞还在拆除的 P2P 组。
- **握手期禁播 + 90s 自救（A3）**：收到任意 progress>0 即置 `negotiating`，自动刷新与配对码看门狗
  一律让路（重播会换掉广播序列号 = 配对会话号，正在进行的握手会被作废）；只保留 90s 自救。
- **广播失败自愈（A4）**：`startAdvertise=false` → 1.2/2.4/4.8/9.6s 重试，永不放弃；等待页显示
  「蓝牙广播重试中（第 N 次）」。旧实现打一行日志就结束，一次失败 = 只能重启 App。
- **统一会话清理（A5）**：`endSession()` 收口所有清状态路径，杜绝「会话进行中，跳过本轮」空转。
- **看门狗互斥（A6）**：12s 起投看门狗在 progress 到 100 后立即让位，只由 4s 首帧链负责，
  一次故障不再被放大成 3 次重建。
- **兜底 BLE 广播（A7）**：移植 `FallbackBleAdvertiser`（传统 31 字节广播，FCFB 服务 UUID，
  序列号镜像 SDK）。仅在「SDK 广播失败」「8s 无配对码」「初始化 5s 未广播」时启动，SDK 一成功即停。
- **用户断开 → 保持停机（A8）**：`param=11` 或投屏页「断开」→ 停广播、不再自动回连，
  等待页显示「已断开 · 点刷新配对码重新连接」。
- **5G 事后校验（A9，只提示）**：连上后读 p2p0 实际频率并打 `[5G] 组频段=… 判定=…`；2.4G 时角标
  显示「建议改 5G」，不中断会话。设置页新增「优先 5GHz」（默认关，仅建组前信道偏置）。
- **连接诊断单行化（A10）**：`[连接] 第 N 轮开始/结果=… 用时=… 阶段=…%`，失败附错误码可读名。

# MiAuto 0.3.16 — 2026-10-26

- **双指缩放修复（action 带 pointer index）。** 0.3.15 的 08:22 日志里双指事件发了 95 次
  （汇总行 `多点=95次/最多2指`），用户实测仍然不能缩放 —— 因为我们传的是 masked 值
  5/6，index 位恒为 0，手机端解析成「第 0 根手指又按下一次」，第二根手指从未登记。
  现在 POINTER_DOWN/UP 传 `getAction()` 原始值（`5|index<<8`）；若 SDK 拒收则自动退回
  masked，保证单击不受影响。日志里会写 `action=261(多指,index=1)` 便于复核。
- **汇总「结果=黑屏」再修一处。** 0.3.15 只在像素路径记 `stats.onFirstFrame()`，
  `onSurfaceReady` 出画的会话仍被记成黑屏（08:22 报告最后一条即是）。
- **5GHz 卡顿开始量化。** 新增两项采样：
  ① `[链路] p2p0 收 X Mbps / 发 Y Mbps`，每 10 秒一行 —— 速率贴着码流上限说明是空口带宽；
  ② `[流畅度] 30s 采样 N 次，画面变化 M 次，最长静止 Zms，平均差异 D` —— 最长静止就是
  最直观的卡顿指标。
- **触摸 MOVE 节流 16ms → 20ms**（60Hz → 50Hz）：UIBC 每条事件都要组 JSON + 加密 + 走 RTP，
  665 上 60Hz 触摸流会和解码抢 CPU，拖动时尤其明显。

# MiAuto 0.3.15 — 2026-10-26

- **通道角标改以 SDK 的 connectType 为准（修「插过一次 USB 就永远显示 USB」）。**
  10/25 全量验证：手机插着线但走的是 MTP/充电模式，`[通道判定] connectType=1`
  （P2P）而角标写「USB AOA 有线」。旧实现先看「有没有 UsbDevice」，
  线一插上就恒为 USB。现在 2=AOA、1=P2P、3=SoftAP，读不到才退回「AOA 配件是否真在」；
  汇总行里的 `通道=` 也一并修正（旧代码把 2 打成 SoftAP）。
- **USB 优先只在真的 AOA 配件存在时生效。** 同上，旧判据让每次冷启动都停广播等 10 秒，
  AOA 一次没建起来，回退广播还常失败 —— 这是 10/25 大量「重建多轮」的直接来源。
  现在线插着但手机没进 AOA 模式时继续广播，并在日志里写明。
- **诊断计时不再串场。** 旧清零条件只认「上一场到过 progress 100」，卡在 0/20/40 死掉的
  场次从不清零，于是出现「首帧 距 progress100 3117331ms」这种 52 分钟的鬼数字。
  现补上「上一场未到 100」也清零；多点触控事件数与最大手指数进汇总（验证双指手势用）。

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

