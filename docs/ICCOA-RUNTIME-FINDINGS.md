# ICCOA CarLink 运行期逆向与排障笔记

> 本文记录的是**逆向 + 实测**得来的东西，不是官方文档里的内容。
> 集成层面的「怎么把 SDK 塞进 APK」见 [ICCOA_SDK_INTEGRATION.md](ICCOA_SDK_INTEGRATION.md)；
> 本文只回答一类问题：**连上了但没画面 / 卡在 10% / 10003 / 触控不准 / 黑屏几秒，到底是谁的问题。**

适用环境：比亚迪 DiLink 3.0（Android 10 / API 29 / 骁龙 665 / 1920×990 面板）、
手机 vivo X200s（Jovi InCar）。方法本身与机型无关。

---

## 0. 一句话结论（先看这个）

> ICCOA 的「建链」和「出画面」是两条独立的链。**连接进度到 100% 只代表 RTSP/控制面通了，
> 不保证有任何一帧视频**；而且 `onSurfaceReady()` 这个「首帧就绪」回调在多数正常会话里
> **根本不会触发**。任何依赖它来判断黑屏的逻辑都会误判。

本项目最终采用两条判据替代它：

1. **像素采样**（PixelCopy 小图统计非黑像素）—— 唯一不依赖 SDK 内部状态的真判据；
2. **连接进度 + 计时**（`progress 90 + 900ms`）—— 只用来快速判定「这一场不会有了」。

---

## 1. 工具链与可复现的前置

### 1.1 SDK 二进制的来源

| 项 | 值 |
|---|---|
| 文件 | `app/libs/icar-sdk.dex`（**不入库**，`.gitignore` 的 `*.dex` 覆盖） |
| 大小 | 7,084,144 字节 |
| 来源 | 官方车机端 APK 的 `classes3.dex`，原名提取后直接使用 |
| 包根 | `com.ucar.vehiclesdk` / `com.share.connect` / `com.ucar` / `com.ucarsink` |
| 编译期依赖 | `app/stubs/icar-sdk-stubs.jar`（纯签名存根，**入库**，`compileOnly`） |
| 运行期依赖 | `androidx.room:room-runtime:2.4.3`（SDK 用它维护信任设备表，缺了会在首次访问 Peer 库时 `NoClassDefFoundError`） |

> **不要改包名，不要搬包。** SDK 内部有跨进程组件名和写死在字节码里的包路径，
> 改名后会话会静默失败，没有任何报错。

### 1.2 反编译：用 androguard，不要用 jadx

jadx 在这份 dex 上会因控制流还原失败而大面积报错；androguard 的**字节码直读**反而更可靠 ——
本项目的所有结论都是从 `DEX.get_classes()` + 逐条指令读出来的。

```python
# 一次性依赖：pip3 install androguard loguru
from loguru import logger
logger.remove()                       # androguard 的日志会淹没输出
from androguard.core.dex import DEX

d = DEX(open("app/libs/icar-sdk.dex", "rb").read())

# ① 列出某个类的全部方法
for c in d.get_classes():
    if c.get_name() == "Lcom/ucar/vehiclesdk/UCarAdapter;":
        for m in c.get_methods():
            print(m.get_name(), m.get_descriptor())

# ② 反汇编某个方法（最常用）
for c in d.get_classes():
    if c.get_name() == "Lcom/ucar/vehiclesdk/player/VideoPlayerV2$1;":
        for m in c.get_methods():
            if m.get_name() == "onOutputBufferAvailable":
                for ins in m.get_code().get_bc().get_instructions():
                    print(f"{ins.get_name():26s} {ins.get_output()}")

# ③ 找「谁调用了 X」——定位状态变更的源头
for c in d.get_classes():
    for m in c.get_methods():
        code = m.get_code()
        if not code:
            continue
        for ins in code.get_bc().get_instructions():
            if "pauseCast" in ins.get_output():
                print(c.get_name(), m.get_name(), ins.get_output())
```

读字节码时注意两点：

- **`if-*` 的跳转目标要自己算**：`ins.get_output()` 末尾是 `+1ah` 这类**代码单元**偏移（1 unit = 2 字节），
  不是字节偏移。算错一格就会得出完全相反的结论（本项目早期就误判过一次 `onSurfaceReady` 的判据）。
- **`lambda$` / `access$` 方法是关键**：AGP 的 desugar 把回调拆成了 `UCarAdapter$$Lambda$...`、
  `access$800` 这类合成方法，真正的字段读写在里面。

### 1.3 日志

| 关注点 | 做法 |
|---|---|
| SDK 自身日志 | tag 前缀是 **`easylog_` + 类名**（`EasyLog` 的 `<clinit>` 里写死），不是类名本身 |
| 本项目日志 | 统一 `OPENHU` 前缀，投屏相关再加 `ICCOA` |
| 抓取 | `DiagnosticReport` 先 `logcat -d` 全量落盘，再按上面两个前缀过滤 |

> 曾经的坑：过滤写成 `<SDK 类名>:V`，结果一条 SDK 日志都抓不到，误以为 SDK 不打印。

---

## 2. SDK 的对象图

```
UCarAdapter (单例 · UCarAdapter.getInstance())
 ├─ init(Context, UCarConfig, ICarInitCallback) -> onInitSuccess()
 ├─ registerCarConnectListener(ICarConnectListener)     // 状态/进度/配对码/失败
 ├─ startAdvertise() / stopAdvertise()                  // BLE 广播，手机据此发现车机
 ├─ enableUsbDeviceDetection(boolean)                   // AOA 有线通道总开关
 ├─ startCast(Surface, w, h[, IAppSurfaceStateListener]) // 建解码器 + 挂输出面
 ├─ pauseCast() / pauseCast(boolean)
 ├─ disconnect()
 ├─ sendTouchEvent(action, count, trackIds[], xs[], ys[]) // UIBC 触控回传
 └─ initCastManager()
        └─ CastManager(MDevice, CastListener)
             ├─ startCast(surface, w, h, flagWfdPerf, sessionId, listener)
             ├─ startWfdServer(...) / stopWfdServer()
             ├─ pauseWfd(String) / resumeWfd(String)
             └─ VideoPlayerV2  (跨会话复用，不销毁)
                    ├─ init(surface, w, h, flagWfdPerf, sessionId, listener)
                    │     ├─ !mIsConfigured -> MediaCodec.configure(surface) + mIsFirstLaunch = true
                    │     └─  mIsConfigured -> 仅 setOutputSurface(surface)
                    └─ VideoPlayerV2$1 (MediaCodec.Callback)
                          ├─ onInputBufferAvailable  -> VideoQueueManager 取数据喂解码器
                          └─ onOutputBufferAvailable -> 渲染 + 唯一的 onSurfaceReady 触发点
```

要点：

- **`VideoPlayerV2` 的生命周期 = `CastManager` 的生命周期 = 进程生命周期。**
  会话断开不会销毁它，`mIsConfigured` 一旦为 `true` 就永久为 `true`。
  这是「第一次黑、第二次正常」这类现象的物理基础。
- `UCarConfig` 里的 `supportLowLatencyDecodingMode` 有两层作用：
  ① 作为 `boolean` 参数传给 `CastManager.startCast` → `VideoPlayerV2.setFlagWfdPerf()`，影响解码/渲染排队策略；
  ② 随 `GetUCarConfigResponse` 上报给手机，手机据此选更短 GOP 的编码档。

---

## 3. 连接状态与进度语义

### 3.1 状态码

| `onConnectStateChanged(newState)` | 含义 | 本项目的处理 |
|---|---|---|
| `0` | DISCONNECTED（会话真正结束） | 清 `sessionActive`、清配对码、延时重播 |
| `1` | CONNECTING（协商中，**鉴权也在这段**） | 置 `sessionActive=true`，换用 45 秒超时 |
| `2` / `3` | 已连接 | 撤掉待执行的广播 |
| `11` | 另一路 CONNECTING 视图（日志实测） | 同 1 |
| `>= 10000` | 失败码，见 3.2 | 交回看门狗 |

> `1` 必须置 `sessionActive=true`。否则 30 秒看门狗会在鉴权进行到一半时
> `stop→start` 重播，把正在跑的会话连根拔掉 —— vivo 上表现为永久卡在协商。

### 3.2 失败码

`UCarAdapter.handleConnectFailed(reason)` 做了一层映射：

| 内部 reason | 对外 | 语义 |
|---|---|---|
| 1 | `10008` | **不是真失败**：本端刚重启广播，上一会话的回声 |
| 2 | `10004` | 建链失败（另一条通道） |
| 3 | `10003` | 无线建链失败（日志原文：`the phone don't support wireless connection`） |

`10008` 永远紧跟 `startAdvertise()` 出现。**对 10008 再触发重播会形成 1.2 秒自激循环**
（10003 → 重播 → 10008 → 重播 → …），本项目对它是显式跳过的。

### 3.3 进度

`onConnectingProgress(0..100)`，10 一档。实测节奏（P2P，骁龙 665）：

```
10 ─ 1.1s ─> 20/30 ─ 0.45s ─> 40 ─ 0.13s ─> 50..80 ─ 0.4s ─> 90 ─1.8~1.9s─> 100
```

- `10` 是「手机已连上、开始协商」，也是**投屏页最早可以打开的时刻**；
- `90 → 100` 这段固定 1.8~1.9 秒，且**首帧全部落在 90 之后 193~206ms**；
- 所以「`progress 90` 之后再等 900ms 仍无像素」可以判定这一场不会出画面了。

---

## 4. 传输通道：P2P vs SoftAP

### 4.1 只能声明一个

`UCarConfig.Builder` 同时 `setSupportP2P(true)` + `setSupportSoftAP(true)` 时，
`mWirelessConnectType = 1003`（both），SDK 会按**手机期望值**二选一，且**选中后失败没有回退**：

```
手机期望 AP(1002)  + 车机 both(1003) -> 关 P2P，createSoftAp
手机期望 P2P(1001) + 车机 both(1003) -> 关 AP，createGroupForClient
手机期望与车机能力对不上          -> notifyConnectFailed(3) -> 10003
```

这是「卡在 progress 10 约 15 秒然后 10003」的头号原因。
本项目改为跟随设置项 `native-ap-transport`（0 = P2P，1 = SoftAP），**只声明一个**。

### 4.2 5GHz 信道表的坑

```java
b.setAllowed5gChannels(new int[]{36,40,44,48,149,153,157,161});  // 全部避开 DFS
```

**只能在 P2P 分支里设。** SoftAP 模式传空数组会抛
`java.security.InvalidParameterException: channels can't be null`，
`buildConfig()` 直接失败 → `start()` 提前 return → 界面上连配对码都不出现。

### 4.3 `pm list features` 没有 `wifi.direct` ≠ 不支持 P2P

DiLink 3.0 的 feature 表里没有 `android.hardware.wifi.direct`，但 P2P **实测可用**
（`[通道判定] connectType=1 → WiFi Direct (P2P) | ifaces=p2p0,wlan0`）。

> 判断通道是否真的建立，看 `ifaces` 里有没有 `p2p0`，不要看 features，也不要只看 `WifiP2pManager` 的返回值。

---

## 5. USB（AOA）与 Android Auto 栈的冲突

`enableUsbDeviceDetection(true)` 上游从未调用过，所以插线只会走基座残留的 AA 握手。
补上之后仍然要处理**两套栈抢同一个 bulk 端点**：

```
AapService  <- 抢读 accessory bulk 端点 -> "Drained 422894 bytes"
                                          "Ignoring unexpected message (ch=5...)"
```

表现为 ICCOA 完全收不到数据。需要在**四处**让 AA 栈退出：

| 位置 | 处理 |
|---|---|
| `AapService.onUsbAttach` 的 accessory 分支 | `ICCOA_ONLY` 时不接管 |
| `AapService.checkAlreadyConnectedUsb` | 同上 |
| `AapService.onUsbPermission` | 同上 |
| `UsbAttachedActivity` 的 accessory 分支 | 不再启 `AapService` |

另外 `ensureUsbPermissionOnly()` 必须**切主线程**且**不弹 Toast** ——
在非主线程弹 Toast 会直接闪退。

---

## 6. Surface 与解码器时序

### 6.1 `startCast` 真正的语义

`startCast()` 返回 `true` 只代表**调用被接收**，不代表解码器就绪、更不代表有画面。
首次调用会走：

```
MediaFormat.createVideoFormat(mime, w, h)
  -> MediaCodec.configure(surface)
  -> mIsFirstLaunch = true
  -> VideoQueueManager.clearDataIfNeeded()   // 小心：这里会清掉已缓存的帧
```

第二次以后只 `setOutputSurface(surface)`。

### 6.2 `onSurfaceReady()` 的真实触发链（字节码级）

```
VideoPlayerV2$1.onOutputBufferAvailable:
    mNeedRender
 && !mIsSurfaceAlReady
 && mLastReadyCountTime != -1
 && mLastReadyCountTime >= presentationTimeUs
```

而：

- `mLastReadyCountTime` 只在**输入侧第一帧 且 `mLastShownTime == 0`** 时才被赋值；
- `VideoPlayerV2.init()` 每次都会把它重置成 `-1`。

**任一条件不满足 → 画面正常播放，回调永远不来。** 本项目 5 轮日志里，
所有成功会话都是「像素已经出来了，`onSurfaceReady` 还没来」。

### 6.3 Surface 尺寸

- 面板 1920×990（`2388×1080` 在另一台样机上）→ 视频画布按 1280×592（上限 1280×720，16 对齐）；
- `SurfaceView` 必须是 **landscape 锁死**：手机竖着拿时 SDK 协商的是横屏画布，
  `surfaceChanged 1260×2800` 会让协商直接失败；
- `surfaceCreated` 时宽高是 `0×0`，必须等 `surfaceChanged` 再启动投屏。

---

## 7. `pauseCast()` 的隐藏副作用

```java
pauseCast()        == pauseCast(true)   // 会给手机下发 "GotoBackground"
pauseCast(false)                        // 不下发
```

车机侧只是想「重绑输出面」时用 `pauseCast(true)`，手机端会认为**车机切到后台**，
可能把投屏应用退回后台、推迟自动重连 —— 而重连恰恰是冷启动后恢复画面的唯一路径。

本项目所有「重绑/断开」路径统一用 `pauseCast(false)`。

---

## 8. 触控回传（UIBC）

```java
boolean sendTouchEvent(int action, int count, int[] trackIds, int[] xs, int[] ys);
```

- 上游从未调用，车机上点什么都没反应，看起来像「画面冻住了」；
- **坐标必须按视频画布归一化**（`videoW/videoH`），不是按面板，也不是按 `SurfaceView` 的实测尺寸；
- `action` 用 Android `MotionEvent` 的语义：`0=DOWN / 1=UP / 2=MOVE`；
- 多点触控时 `count` 与三个数组长度必须一致。

实测日志（可用来自检）：

```
触控回传 action=0 count=1 view=2298x1080 video=1280x592 p0=(56,552) → 已发送
```

### 8.1 0.3.4 反编译：`UibcManager` 的三道前置检查与 source size 之谜

`UCarAdapter.sendTouchEvent` 只是个壳，实现在 `com.ucar.vehiclesdk.control.UibcManager`
（`icar-sdk.dex` 反汇编，0.3.4 开发期间完成）。按检查顺序：

| # | 检查 | 失败日志 | 赋值来源 |
|---|---|---|---|
| 1 | `trackIds/xs/ys` 长度都等于 `count` | `sendTouchEvent failed: count error!` | — |
| 2 | `mIsEncryptionSettled == true` | `sendTouchEvent failed: encryption is not settled` | `onWfdSinkReady` → `setEncryption(uibcEncryption)` |
| 3 | `mSourceWidth/Height > 0` | `sendTouchEvent failed: source size haven't been initialized!` | `onWfdSinkReady` → `updateVideoSourceSize(w, h)` |

赋值链唯一且窄：

```
SinkNative/native → CastManager$1.onCastVideoInitialized(mime, sessionId, 源宽, 源高)
  → CastListener.onWfdSinkReady(sessionId, mVideoSourceWidth, mVideoSourceHeight, mIsUibcEncryption)
    → UibcManager.setSessionID + setEncryption + UCarAdapter.updateVideoSourceSize(w, h)
```

而 `CastListener` 只有三个回调（`onWfdAudioInitialized` / `onWfdSinkReady` /
`onWfdSinkStopped`），**全是 WFD（Wi-Fi Display）语义**。AOA（USB）传输下媒体走另一条路，
`onCastVideoInitialized` 不触发 → source size 停在 0 → 触摸全被拒。方控
`sendKeyEvent` 走同一 `SinkNative.onUibcEvent` 出口、同样查 encryption，但**不查
source size**，所以按键不受影响 —— 这就是实测里「触摸全被拒、方控照常」的成因。

坐标公式（`getTouchCoordinateX/Y`）：

```java
mappedX = (int)(x * 1.0f * mDisplayWidth  / mSourceWidth);
mappedY = (int)(y * 1.0f * mDisplayHeight / mSourceHeight);
```

`mDisplayWidth/Height` 是 `startCast` 时由车机传入的 videoW/videoH；本项目的 `xs[]`
正是按这个基准归一化的。因此 **source 与 display 相等时映射 1:1**；若手机上报的源尺寸
与视频画布不一致（例如上报竖屏尺寸），坐标会按比例错位。0.3.4 的懒补
（反射 `UCarAdapter.updateVideoSourceSize(videoW, videoH)`，见 `IccoaUibcFix.java`）
同时解决两件事：USB 下补上缺失值、无线下强制 1:1。

> 另：`sendTouchEvent` 组包的 JSON 里 `width/height` 取 `mDisplayWidth/Height`，
> `x/y` 取映射后的值，`origin point / display size / source size / mapped point`
> 会以 d 级日志整条打出（tag `easylog_UibcManager`，走 EasyLog 文件通道，**不进
> logcat**，诊断报告抓不到 —— 这也是项目自己反射读字段打快照的原因）。

### 8.2 崩溃可见性（0.3.4）

- EasyLog（tag `easylog_*`）实测**完全不进 logcat**，`logcat -d` 与 `logcat -b crash`
  都抓不到 SDK 自己的行；诊断报告里的 SDK 段实际上条数为 0，真正可用的只有
  AppLog/DiagnosticJournal（tag `OPENHU`）。
- 因此 0.3.4 起：未捕获异常写 `crash-<ts>.txt`（保留 5 份）并在下次启动时把摘要注入
  AppLog；报告增加 `logcat -b crash` 抓取，覆盖 Java handler 看不到的 native crash。

---

## 9. 五轮实测：首场为什么黑

这是本项目花时间最多、也最容易被误判的问题。按版本列出可复现的数字。

### 9.1 会话级数据

| 版本 | 会话 | 真实 Surface 的 `startCast` | 首帧 |
|---|---|---|---|
| .17 | 冷启动首场 | progress 60 | 9000ms 内零像素 |
| .17 | 重连 | progress 0 | 6527ms ✓ |
| .17 | 第三次 | progress 0 | **1412ms ✓** |
| .18 | 冷启动首场 | progress 20 | 零像素 |
| .18 | 重连 | progress 0 | 7255ms ✓ |
| .19 | 冷启动首场（解码器提前 3.1s 就位） | progress 10 | **零像素** |
| .19 | 重连 | progress 0 | **4612ms ✓**（预热生效，快 2.6s） |
| .20 | 冷启动首场 | progress 30 | 零像素（900ms 判死生效，902ms 触发） |
| .20 | 重连 | progress 0 | 5075ms ✓ |
| .21 | 冷启动首场（startCast 已在 progress 0） | **progress 0** | **零像素** |
| .21 | 重连（同样 progress 0） | **progress 0** | **4731ms ✓** |

### 9.2 结论

`.21` 那一轮是决定性的：**两场 `startCast` 都在 `progress 0`，一场黑、一场正常。**
差别只剩「本进程第几次会话」。

所以：

> **进程冷启动后的第一次连接，手机不推视频流。第二次连接才推。**
> 这是手机端（vivo X200s / Jovi InCar）的行为，车机侧无法修复。

被逐一排除的解释：

| 假设 | 被哪一轮推翻 |
|---|---|
| 手机端首场 RTSP 会话没启动 | .16 起改为重开会话，首场照样黑 |
| 解码器建得太晚 | .19 解码器比手机连入早 3.1 秒，仍零像素 |
| 离屏预配置（空配）能救首场 | .17/.19 空配提前 6.2 秒完成，仍零像素 |
| `startCast` 落在 progress 0 就成功 | .21 两场都在 progress 0，一黑一正常 → 相关非因果 |
| 判死太慢、重连太久 | .20 已压到 progress 90 + 900ms，只影响等待时长 |

### 9.3 因此保留的两条妥协

1. **快速判死**：冷启动首场在 `progress 90 + 900ms` 无像素就断开重连，
   而不是等满 4.5 秒（省 3.6 秒）；
2. **过渡页**：`SurfaceView` 之上盖一层不透明品牌页，首帧确认后淡出。
   建链再慢，用户看到的是品牌页而不是黑屏。

---

## 10. 当前实现的时序

### 10.1 正常路径（同一进程的第二次及以后连接）

```
start() ─ init(config) ─ onInitSuccess ─ startAdvertise ─ onPinCode
                                                        │
                                          +600ms ──> primeDecoderOffscreen()   // 离屏 Surface 先 configure
                                                        │
手机连入 ─ progress 10 ─ 打开投屏页 ─ surfaceChanged ─ startCast(真实 Surface) ─ progress 90~100 ─ 像素采样确认 ─ 遮罩淡出
```

### 10.2 冷启动首场

```
… startCast(真实 Surface) ─ progress 90 ─(+900ms 无像素)─> 断开会话
                                                          │
                                              +800ms ──> startAdvertise
                                                          │
                                        手机重连 ─ progress 10 ─ 像素 4.6~5.4s ─ 遮罩淡出
```

---

## 11. 坑点清单

| # | 坑 | 症状 | 规避 |
|---|---|---|---|
| 1 | P2P + SoftAP 同时声明 | 卡 progress 10 约 15 秒 → 10003 | 只声明一个 |
| 2 | SoftAP 下设置空 5G 信道表 | 无配对码，`channels can't be null` | 只在 P2P 分支设 |
| 3 | `setSupportLowLatencyDecodingMode` 重复写 | 后一次 `false` 覆盖前一次 `true` | 只写一次 |
| 4 | 依赖 `onSurfaceReady` 判黑屏 | 画面正常却被判失败 → 自断正常会话 | 用像素采样 |
| 5 | `pauseCast()` 无参调用 | 手机收到 GotoBackground，推迟重连 | 用 `pauseCast(false)` |
| 6 | 在 progress 10 之前就 `startCast` | 部分机型 `configure` 与协商抢跑 | 等 Surface 真实尺寸 |
| 7 | 投屏页不锁横屏 | `surfaceChanged 1260×2800`，协商失败 | `SCREEN_ORIENTATION_LANDSCAPE` |
| 8 | `surfaceCreated` 时立刻用 0×0 启动 | 建出 0×0 的码流 | 等 `surfaceChanged` |
| 9 | AA 栈抢 USB bulk 端点 | `Drained N bytes`，ICCOA 收不到数据 | 四处拦截 AA |
| 10 | 非主线程弹 Toast | 闪退 | 切主线程且不弹 |
| 11 | logcat 过滤前缀写错 | 一条 SDK 日志都抓不到 | `easylog_` |
| 12 | 对 10008 重播 | 1.2 秒自激循环 | 显式跳过 |
| 13 | 看门狗在协商中途计时 | 打断正在进行的鉴权 | 从 progress 90 起算 |
| 14 | 用 feature 表判断 P2P 能力 | 误判「不支持」 | 看 `ifaces` 里的 `p2p0` |
| 15 | 断开会话用 `disconnect()` 前先 `pauseCast()` | 手机侧状态错乱 | `pauseCast(false)` + `disconnect()` |
| 16 | 像素采样预算过小 | 等待变长后遮罩撤不掉 | 预算要覆盖「等待 + 首帧」 |
| 17 | `carId` 不是恰好 6 字节 | `init` 失败 | 长度校验 + 随机兜底 |
| 18 | 投屏页提前到配对阶段打开 | 用户进不去主界面/设置 | 保持 `progress ≥ 10` 再进 |

---

## 12. SDK 提供但本项目尚未使用的能力

反编译 `UCarAdapter` 暴露的完整 API 面（可作为后续功能的备选清单）：

| API | 用途 |
|---|---|
| `sendGPSInfo` / `sendBatteryInfo` / `sendOilInfo` / `sendLightsInfo` / `sendGearStateInfo` / `sendAccelerationInfo` / `sendGyroscopeInfo` / `sendLightSensorInfo` | 车况回传手机，供手机端导航/能耗类应用使用 |
| `sendKeyEvent` / `notifySwitchDayOrNight` | 方向盘按键、日夜模式同步 |
| `sendVRCMD` / `awakenVoiceAssistant` | 语音助手唤醒 |
| `registerAudioRecorderListener` / `sendMicRecordData` / `setUseCustomAudioRecord` | 车机麦克风上行（当前 `setSupportMic(true)` 但未接管录音） |
| `registerCameraInfoListener` / `addCamera` / `sendCameraData` | 车机摄像头（倒车影像）回传手机 |
| `changeAppRotation` / `startApp` / `pauseApp` / `stopApp` / `notifyAppStateChanged` | 手机应用级控制 |
| `getAppList` / `getAppDetailInfo` / `notifyAppListChanged` | 车机端应用列表 |
| `notifyPickUpCall` / `notifyHungUpCall` / `onPhoneStateInfoReceived` | 电话控制与状态 |
| `onMusicInfoReceived` / `handleOnAudioPlayerControl` | 媒体信息与播放控制 |
| `onNavigationInfoReceived` / `notifyPOIAddressReceived` | 导航信息、POI 下发 |

> 0.3.0 起已接上其中五项：`sendKeyEvent`（方控）、`notifySwitchDayOrNight`（日夜）、
> `notifyPickUpCall` / `notifyHungUpCall`（电话）、`sendGPSInfo`（车况）、
> 以及 `onMusicInfoReceived` / `onPhoneStateInfoReceived` 的上抛。
> 封装在 `IccoaCarLink` 里，实现细节见该文件的注释。
>
> 两个实现上的坑，写在这里省得再踩：
>
> - **`GPSInfo` 没有 setter**，运行期只有一个十参构造
>   `(altitude, latitude, longitude, antennaState, pDop, speed, heading, satsUsed, satsVisible, timeStamp)`
>   —— 顺序由字节码的 `iput` 顺序确定。而 `app/stubs/icar-sdk-stubs.jar` 是裁剪过的存根，
>   **只有无参构造**，直接 `new GPSInfo(...)` 编译不过，必须反射。
> - **`sendKeyEvent` 的第三个参数是 repeatCount**，不是键码。播放/暂停这类"切换"语义
>   在 ICCOA 侧没有对应枚举（只有独立的 PLAY / PAUSE），所以只在按下时发一次，避免
>   手机端把一次点击解释成"播放后又暂停"。

---

## 13. 边界：到这里为止

| 已做到 | 做不到（客户端层面） |
|---|---|
| 等待期间不显示黑屏 | 让冷启动首场出画面 |
| 首场 2.6 秒判定失败并自动重连 | 消除这一次重连 |
| 重连后 4.6~5.4 秒出画面（原 7.3 秒） | 让手机首连就推流 |
| 触控回传、旋转/切后台恢复 | — |

要继续推进只剩三条路，都超出车机端：

1. **换手机端复测**（小米 CarWith / OPPO Car+），确认是否为 Jovi InCar 特有；
2. **常驻保活**：让会话永不走「首连」，代价是熄火/休眠后仍需重建；
3. **启动即消耗**：应用启动后主动断一次再广播，代价是用户看到两次配对码。
