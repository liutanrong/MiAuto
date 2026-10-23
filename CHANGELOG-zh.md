# MiAuto 0.3.0 — 2026-10-24

第一个用上「SDK 里一直存在、但投屏器从未调用」的那半边能力的版本：方控、日夜、电话、车况。
同时修了一轮投屏链路上的健壮性问题。

## 投屏

- **像素采样不再有次数上限。** 原来是「从 surfaceChanged 起采 40 次（10 秒）就停」，
  手机连得慢一点，预算在画面出现前就用完，遮罩再也撤不掉 —— 看起来就是彻底黑屏。
  现在改成 60 秒时间窗，且每次收到连接进度回调都续命，等多久就采多久。
- **冷启动快速判死。** 进程启动后的第一场会话，走 Wi-Fi Direct 时手机不推流
  （证据见 `docs/ICCOA-RUNTIME-FINDINGS.md` §9）。不再等 `progress 90 + 900ms`，
  改为 `progress 10` 之后 700ms 就断开重连，约省 2.6 秒。
  **USB 链路不走这条路**：AOA 首场就能出画面，提前掐断会把好会话杀掉。
- **自动跳转不再打断设置。** 以前在设置页改选项时手机恰好连上，会被直接顶进投屏页；
  现在只有首页真正处于前台时才自动跳。

## 车联能力（新增）

- **方控 / 面板按键透传**（`sendKeyEvent`）：方向键、确认、返回、主页、菜单、
  媒体上一首/下一首、翻页、接听与挂断。音量键**故意留在本地**。
- **日夜同步**（`notifySwitchDayOrNight`）：车机切深色，手机端界面跟着切。
- **电话控制**（`notifyPickUpCall` / `notifyHungUpCall`）接到同一条按键通道上。
- **车况 GPS 回传**：投屏期间每 5 秒把车机最近一次定位发给手机，导航可直接用车机天线。
  由于编译期存根只有无参构造，这里用反射构造 `GPSInfo`。
- **手机端媒体 / 电话状态**（`onMusicInfoReceived` / `onPhoneStateInfoReceived`）
  通过 `IcocaBridge.State.music` / `.callState` 到达 UI 层。

## 内部

- `IccoaManager`（1700 行）拆成 `IccoaTouch` / `IccoaSessionStats` / `IccoaCarLink` /
  `IccoaPrefs`；删掉从未被调用的 2×2 ImageReader 预热方案。
- 会话汇总行增加：会话数、重连次数、实际通道、手机型号。
- 设置键改为常量，不再散落字面量。

