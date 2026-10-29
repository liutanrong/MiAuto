# MiAuto 0.3.21 — 2026-10-29

**Audio dropouts narrowed: nothing is lost, it is timing. Instruments first, then one optional knife**

The 10/29 feedback was "still drops, but better", plus three characteristics: **random, very
short, and then it all catches up at once**. Together those rule out packet loss — a one-way
real-time stream that loses packets has permanent gaps and cannot catch up. Catching up means
every sample arrived, just late. The bottleneck moves from bandwidth to timing.

USB is fine and Wi-Fi is not, and the difference is that every packet interrupts the CPU. A
Snapdragon 665 head unit usually runs schedutil or powersave: low clock at idle, so the audio
thread has to wait for the ramp-up, and those tens of milliseconds are enough to drain the
AudioTrack. This version does not bet on that hypothesis — it adds the instruments first and
ships one switchable lever.

- **A1 audio log filtering rewritten.** 0.3.20's audio section was 118 lines covering 4 seconds:
  `UCar_AudioQueueManager-pcm: buffer queue size: 10` prints a dozen lines a second and ate the
  whole 500-line quota, so `wait buffer to fill` never made it in. Full-queue lines are now
  sampled 30:1 and the budget goes to anomalies, cap raised to 760.
- **A2 in-session CPU sampling.** Every 10 s, one line: governor plus all eight core frequencies.
  Dropouts are sub-second and cannot be caught live, but "what was the clock when it dropped"
  can be reconstructed from the timeline.
- **A3 AudioFlinger underrun counter.** On session end, `dumpsys media.audio_flinger`, filtered to
  `underrun`. It is cumulative, so a narrow log window does not matter — the delta between two
  sessions is how many times the buffer was actually drained.
- **A4 su identity probe.** One `id` line at session start. Without it, "do we have root" was
  guesswork.
- **B CPU governor switch (off by default).** Settings → connection → "Audio smoothness". Switches
  to `performance` for the session and restores on disconnect; the original value is recorded and
  restored once at app start, so a killed process cannot leave the unit pinned at full clock.
  Skips itself with a logged reason when there is no su or the kernel lacks the governor.
- **Build fix.** `injectIccoaDex` treated an *empty* `ANDROID_HOME` as valid, resolved it to the
  project root, and silently fell back to a hard-coded Windows path. Blank values are now skipped
  and `local.properties`'s `sdk.dir` is honoured.

# MiAuto 0.3.20 — 2026-10-28

**Crash root cause found + four diagnostics fixes**

0.3.19 verified well where it mattered: `WifiLock(LOW_LATENCY)` took, the group moved from
5180 MHz (ch36) to **5745 MHz (ch149)**, MulticastLock is released on connect, and D2 finally ran —
`[重建] 已释放 VideoPlayerV2(VideoPlayerV2@b9d527c)` followed by a **158 ms** first frame. The
first session of a process no longer needs a disconnect/reconnect cycle.

This round closes what the same report exposed.

- **Crash root cause (the real one).** Not a decoder or a race — Direct Boot:
  ```
  Unable to create application ... IllegalStateException: SharedPreferences in credential
  encrypted storage are not available until after user is unlocked
      at Settings.getPrefs(Settings.kt:29)
      at AppComponent.<init>(AppComponent.kt:18)
      at App.onCreate(App.kt:47)
  ```
  Boot auto-start fires before the head unit is unlocked, and `App.onCreate` touched
  `component.suExecutor`, which constructs `AppComponent`, which reads SharedPreferences. Fixed in
  three places: `component` access moved inside the `isUserUnlocked()` branch; `Settings` falls back
  to `createDeviceProtectedStorageContext()`; `debugForceMemoryProfile` is read under `runCatching`.
- **A3 round-result channel still "unknown" 1 in 2 times.** A fixed 600 ms delay is not enough —
  one session had state=4 and state=2 **850 ms** apart. Now it polls until `connectType` lands,
  capped at 2 s.
- **First-frame timing leaked across sessions.** `tProgress100` survived `onSurfaceDestroyed`, so a
  resumed session printed `首帧 距 progress100 152095ms`. Both counters are cleared now.
- **Link sampling: third path.** `/proc/net/dev` is blocked for untrusted apps on this unit too
  (0.3.19 printed `采样不可用`). Added `TrafficStats.getTotalRxBytes/getTotalTxBytes` as a last
  resort — machine-wide counters, labelled `全机` so nobody mistakes them for p2p0.
- **Audio focus: do not grab on loss.** `focus=-5` arrives during teardown; granting then is
  backwards. Only non-negative (gain) events call `allowGainAudioFocus()`.

**On the stutter itself.** The expanded logcat finally shows the audio path:

```
UCar_AudioPlayer@STREAM_CAST_MUSIC.Play: wait buffer to fill bytes: 57600
UCar_AudioPlayer@STREAM_CAST_MUSIC.Play: buffer fill done, number: 15, bytes: 61440
UCar_AudioQueueManager-aac@STREAM_CAST_MUSIC: buffer queue size: 0, total: 11400
```

Audio is AAC-over-network, decoded on the head unit, and the player re-fills a 57600-byte cushion
(0.3–0.6 s) before every start. In the captured window it did so at 42.705 s, 44.271 s and
44.663 s — twice in 1.5 seconds. A cushion that keeps being rebuilt means the stream keeps running
dry, which is exactly what "sound breaks up" sounds like. That buffer lives inside the SDK; the
only lever we have is making delivery more continuous, which is what the 0.3.19 Wi-Fi changes aim at.

# MiAuto 0.3.19 — 2026-10-28

**Audio stutter + the connection P0s**

The reported symptom set — "sound breaks up on Wi-Fi, not on USB; lowering resolution does not
help; audio-only also stutters" — is what drove this round. A ~200 kbps audio stream cannot be
bandwidth-starved while 8 Mbps of video over USB is fine, so bandwidth, codec load and the phone
are all ruled out. What remains is the medium: P2P airtime scheduling.

- **E1-1 WifiLock LOW_LATENCY.** `WIFI_MODE_FULL_HIGH_PERF` only keeps the radio awake; it does not
  change airtime scheduling. `WIFI_MODE_FULL_LOW_LATENCY` (API 29; this unit is Android 10)
  disables PS polling — its ~100 ms periodic latency spikes are exactly what starves an AudioTrack —
  and places frames in the high-priority WMM queue. Falls back to HIGH_PERF if the OEM rejects it.
- **E1-2 MulticastLock released on connect.** It is only useful before the link exists. Holding it
  makes the chip wake on every DTIM to receive multicast frames, stealing airtime from audio.
- **E1-3 UNII-3 only.** The group kept landing on 5180 MHz (ch36, UNII-1: lower power ceiling,
  usually 20 MHz). The allowed list now defaults to 149/153/157/161. Opt out with
  `iccoa-5g-unii3-only=0`.
- **E2-1/E2-2 stop hurting ourselves.** The smoothness probe ran PixelCopy on the video SurfaceView
  4×/s for 30 s — a GPU sync point on the render path, i.e. a stutter generator used to measure
  stutter. Off by default now (`iccoa-smooth-probe`); first-frame probing is 500 ms × 15 s instead
  of 250 ms × 60 s.
- **E3-3 audio focus answered.** `onAudioFocusChanged` was an empty override with no logging, and
  `UCarAudioManager.allowGainAudioFocus()` was never called. Now logged and always granted — nothing
  else competes for audio on the head unit.

Connection:

- **A1 `stopCast` is private.** 0.3.18 called `getMethod("stopCast")`, which only searches public
  members; the dex says `direct PRIVATE V stopCast()`. D2 therefore never ran a single time. Now
  `getDeclaredMethod` + `setAccessible`, and on failure the full method list is dumped so we never
  have to guess again.
- **A2 USB priority deadlock.** `usbPriorityFallback` bailed out on `castStarted`, which the AOA
  branch sets just by pre-arming the decoder. The 10 s fallback to BLE advertising could never fire,
  so the 12 s stall watchdog kept disconnecting: 5 rounds / 4 min 37 s on 10/27, until the cable was
  physically replugged. Now guarded by `lastProgress > 0`.
- **A8** cold-start window 4000 → 2000 ms. Six successful first frames measured 43 / 423 / 430 /
  433 / 454 / 455 ms — when the window expires the frame is not late, it is never coming.
- **A3–A7** diagnostics: round result printed after `connectType` arrives (was `通道=未知` 10/12
  times); `[通道]` deduplicated (was 4 lines per session); replay scheduling keeps the *earliest*
  deadline instead of logging `+5000ms` and `+800ms` at the same instant; `startCast` timing prints
  `n/a` instead of a 20-day number; a stopped-state ack is no longer logged as an abnormal
  disconnect.
- **E5 resolution actually applies.** `buildConfig()` now recomputes the canvas every time and the
  log prints `resolutionId`. It used to be computed once at activity start — which is why all 12
  sessions on 10/28 reported 1280x672 no matter what was selected.
- **E3-1/E3-2 forensics that cannot lie to us.** Link sampling now also reads `/proc/net/dev`
  (sysfs is blocked for untrusted apps on this unit — that is why `[链路]` never appeared) and
  reports dropped packets plus RSSI / link speed / frequency. The report now also pulls
  AudioFlinger / AudioTrack / WifiP2p / wpa_supplicant lines and the first 40 lines of the newest
  crash file.
- **B1/B3** decoder instance fingerprint (`VideoPlayerV2@hash`) on every rebuild, to settle whether
  "reused decoder" — not "new decoder" — is what makes the second session work.

# MiAuto 0.3.18 — 2026-10-26

**D1/D2/D4/D5/D6/D8/D10/D11 (D3 and D7 not in this round)**

- **D1 self-disconnect fix (the big one).** The SDK reports *our own* `disconnect()` as
  `DISCONNECTED(param=11)` — identical to a phone-side user disconnect. 0.3.17 therefore treated
  every watchdog disconnect as user intent and stopped advertising, forcing a manual refresh
  (reproduced twice on 10/26 at 21:19 and 21:20). A `selfDisconnectAt` timestamp now marks
  `param=11` within 3 s as our own teardown; only an unclaimed 11 enters the stopped state.
- **D2 first-session rebuild.** When the first session of a process shows no frame, rebuild the
  decoder *inside* the session: reflect `UCarAdapter.mCastManager` → `CastManager.stopCast()`
  (decompiled: it only releases `mVideoPlayer`, the WFD session is untouched) and `startCast()`
  again. At most twice (D11), then fall back to rebind → reconnect.
- **D4 AOA detection** no longer relies on `getAccessoryList()` (always empty on this unit):
  accessory list OR `18D1:2D00|2D01` UsbDevice OR `usb0|rndis` interface.
- **D5 channel label**: a `phoneId` of -1 no longer reuses the previous session's connectType;
  reset per session, and show `P2P Wi-Fi（band unknown）` instead of a stale value. Priority is
  now AOA evidence → connectType → band.
- **D6** the `[5G]` line is printed once per session; 2.4 GHz only annotates the badge.
- **D8** deduplicated logs (one round-result line, one disconnect/stop handling).
- **D10** round result now includes the channel: `通道=USB/P2P/未知`.

# MiAuto 0.3.17 — 2026-10-26

**Batch A: connection stability (A1–A10)**

- **Callback semantics fixed (A1, the root cause).** `onConnectStateChanged(deviceID, state, param)`:
  the second argument is the state code, the third is extra info. We had been reading it as
  `oldState -> newState`, so `state=1` (disconnected) was almost always dropped and `param=11`
  (user-initiated disconnect) had no branch — the head unit kept believing a dead session was alive,
  waiting for the 30 s refresh or the 45 s timeout. Now: 1=disconnected, 2=connected, 3=failed,
  4=cast connected, 5=cast stopped, 6/7=phone home/foreground; state 5 defers 3 s for the reason
  carried by state 1.
- **Exponential backoff (A2)**: `ReconnectBackoff` 1.2 s, ×2, cap 8 s, only for 10003/10006/10008.
- **No re-advertise during handshake + 90 s rescue (A3)**: re-advertising changes the broadcast
  serial number, which *is* the pairing session id — it invalidated in-flight handshakes.
- **Advertise self-healing (A4)**: retries 1.2/2.4/4.8/9.6 s instead of giving up after one failure.
- **Unified `endSession()` (A5)** and **watchdog arbitration (A6)**: the 12 s stall watchdog yields
  once progress hits 100, so one failure is no longer amplified into three rebuilds.
- **Fallback BLE advertiser (A7)**: classic 31-byte advertising with the FCFB service UUID and the
  SDK's serial mirrored; starts only when the SDK fails, no PIN within 8 s, or no advertise at all
  after 5 s, and stops the moment the SDK succeeds.
- **User disconnect stays stopped (A8)**: `param=11` or the disconnect button stops advertising and
  waits for the user, showing "Disconnected · tap Refresh code to reconnect".
- **5 GHz post-check, report-only (A9)**: reads the p2p0 frequency after the group is up, logs
  `[5G] …`, and annotates the badge on 2.4 GHz without dropping the session; new "Prefer 5 GHz"
  setting (default off, pre-group channel bias only).
- **Single-line connection diagnostics (A10)**: `[连接] round N start/result/duration/phase`.

# MiAuto 0.3.16 — 2026-10-26

- **Pinch-to-zoom fixed (action now carries the pointer index).** The 08:22 log shows 95
  two-finger events actually sent (`多点=95次/最多2指`) yet pinching did nothing: we sent the
  masked value 5/6, whose index bits are always 0, so the phone parsed it as "pointer 0 down
  again" and never registered the second finger. POINTER_DOWN/UP now send `getAction()`
  (`5|index<<8`), falling back to the masked value if the SDK rejects it — taps unaffected.
- **"结果=黑屏" fixed on the onSurfaceReady path too** (0.3.15 only recorded stats from the
  pixel path, so SDK-reported first frames were still logged as black).
- **5 GHz stutter is now measurable**: per-10 s `[链路] p2p0 rx/tx Mbps`, and a 30 s
  `[流畅度]` probe reporting samples, changed frames, longest static gap and mean diff.
- **Touch MOVE throttle 16 ms → 20 ms** (60 → 50 Hz): each UIBC event is JSON + encryption +
  RTP; on the 665 that competes with decoding while dragging.

# MiAuto 0.3.15 — 2026-10-26

- **Channel badge now follows the SDK's connectType (fixes "once USB, always USB").**
  In the 10/25 full validation the phone was plugged in for charging while video ran over
  P2P (`[通道判定] connectType=1`), yet the badge said "USB AOA". The old code checked
  "is any UsbDevice present", which stays true as long as the cable is in.
  Now: 2 = AOA, 1 = P2P, 3 = SoftAP; only when unreadable does it fall back to a real AOA
  accessory check. The summary's `通道=` field is fixed too (it printed 2 as "SoftAP").
- **USB priority only engages for an actual AOA accessory.** The old predicate made every
  cold start stop advertising for 10 s while AOA never came up (and the fallback re-advertise
  often failed) — the main source of the repeated rebuilds on 10/25. A cable without AOA now
  keeps advertising, and the log says so.
- **Session timers no longer leak across sessions.** Reset used to require a previous session
  that had reached progress 100; sessions dying at 0/20/40 never reset, producing
  "first frame at progress100 + 3117331 ms". Multi-touch event/pointer counts are now in the
  summary line (for verifying two-finger gestures).

# MiAuto 0.3.14 — 2026-10-25

- **Cold-start rebind window widened 2500 → 4000 ms.** First 0.3.13 field report (07:38):
  priming retirement works — the 90→100 negotiation dropped from ~1900 ms to 264 ms and
  session 2 rendered its first frame 211 ms after progress 100. But the first session was
  still bounced once: the video pipeline (onCastVideoInitialized / onWfdSinkReady, confirmed
  in raw logcat) finished ~2.55 s after startCast, i.e. the 2.5 s window expired ~50 ms
  *before* the pipeline was even ready. Session 2's pattern (onWfdSinkReady → first frame
  ~200 ms later) says session 1 would most likely have painted on its own. 4 s leaves a
  full init→first-frame margin; worst case is ~1.5 s extra wait before the existing
  rebind→re-advertise fallback.

# MiAuto 0.3.13 — 2026-10-25

- **Offscreen priming retired — the true culprit of the dead first session.** The 07:17
  cold starts proved the handoff never really happens: session 1 had the video pipeline
  initialized yet the real screen stayed at 0 lit pixels (session 2, same phone content,
  read 14), and priming marked the SDK's internal surface-ready state so onSurfaceReady
  never fired in the first session; after a disconnect the SDK rebuilds the decoder and
  session 2 works instantly. The "cleared keyframe queue" that priming was meant to prevent
  no longer exists — the projection page auto-opens at progress 10, so the real startCast
  precedes the first frame by ~5.8 s and configure completes directly on the real surface.
  Now casting goes straight to the real surface; priming code kept behind a flag, default off.
- Expected: the very first session connects; no second attempt.

# MiAuto 0.3.12 — 2026-10-25

- **First-frame judgment rebuilt: "no pixels = dead" retired, rebind-first.** Logcat from
  the 06:39 cold starts proves the killed first session had `onCastVideoInitialized`,
  `onWfdSinkReady` and `uibc->started!` all firing — the phone was streaming the whole time;
  the CarWith dark confirmation page is simply too dark for the pixel sampler, which read 0
  and doomed a healthy session (hence "3 attempts to connect"). Now: 2.5 s with no
  first-frame signal → one **seamless rebind** (pauseCast(false) + recast, phone unaware);
  only after two silent windows (≈5 s) is the session torn down. Fast-fail intent kept,
  blind eye replaced.
- **Early bail fully retired**: the second session killed 700 ms after progress 10 was
  equally innocent (same logcat proof). First-frame decisions now belong to the watchdog
  plus the 12 s cast-stall backstop.
- Pixel sampling demoted to a positive-only signal: non-black confirms and lifts the veil;
  all-black is no longer evidence of failure.

# MiAuto 0.3.11 — 2026-10-25

- **First connect no longer needs 3 attempts.** The 700 ms early-bail assumed the old
  world where the decoder was never pre-warmed and the phone never streamed on the first
  session. With offscreen priming that premise no longer holds, yet the bail still killed
  every first session unconditionally. Now: when priming succeeded, the bail is skipped and
  the cold-start watchdog (progress 90 + 1300 ms) judges by actual pixels; the bail remains
  only as a fallback when priming failed, with its trigger widened to progress 10..20.
- **Close the "stuck below progress 90 = no timer at all" hole.** The surface watchdog is
  only scheduled at progress 90/100, the bail only fires at 10..20, and the 45 s connecting
  timeout used to short-circuit on castStarted — a session stuck at 30..80 after startCast
  could stay black forever. New 12 s cast-stall watchdog: if no frame is confirmed within
  12 s of startCast, tear down and re-advertise; the 45 s timeout now gates on surfaceReady.
- **Priming can be retried**: handoffPrimed is set only after the offscreen prime actually
  succeeded (previously set before attempting and never rolled back on failure).
- **Priming no longer steals the real output surface**: skipped when a real cast is already
  running (the +600 ms delayed prime in doAdvertise could otherwise switch the decoder
  output off-screen mid-session).
- Cold-start watchdog 900 ms → 1300 ms: the pixel path needs ≥600 ms after startCast plus a
  250 ms sampling period; the old window mis-killed late-starting sessions.
- USB attach now logs device VID:PID list: isUsbAttached() treats any UsbDevice as "phone
  plugged in", so a USB stick or dashcam also disables the bail — evidence first.

# MiAuto 0.3.10 — 2026-10-24

- **End the "connected for 2 s then killed" loop.** The SDK always fires a transitional
  `CONNECTED -> STATE_-1` right after progress 100 (observed on every normal session, even
  after the first frame), and no later progress event ever arrives to cancel the guard — so
  0.3.9 mis-killed every session after 2 s while the phone was still connected, looping
  connect → exit → reconnect (sessions 5, 6, 7...). New rule: a `-1` within 3 s of
  progress 100 is treated as transitional and skipped; only a steady-state `-1` arms the
  2 s window.
- **Transport badge moved to bottom-right, 12sp → 10sp** — the top-left corner overlapped
  head-unit status info.

# MiAuto 0.3.9 — 2026-10-24

- **Dead-frame guard no longer kills cold start; projection page enterable again.** Field
  testing of 0.3.8 exposed two flaws: (1) the normal first-session recovery flow
  (900 ms no-pixels → quick reconnect) hit the 2 s confirmation window and wrongly exited the
  page; (2) the `linkLost` flag was never cleared after a successful reconnect (the SDK
  re-enters via STATE_11, outside the 1..3 clearing condition), so every subsequent open of
  the projection page exited instantly. Fix: the guard now arms only after this process has
  actually shown a picture (`everSurfaceReady`); a new negotiation round (progress 1..20)
  clears `linkLost`; local disconnects cancel any pending verdict.

# MiAuto 0.3.8 — 2026-10-24

- **Dead-frame protection actually fires now.** The 0.3.7 trigger required `deviceID == "-1"`,
  but in real disconnects the device id usually stays valid (`dev=099b29`) — only the state
  machine falls back to `STATE_-1`, so the guard never armed and the unit stayed frozen on the
  last frame. Now any `-1` while a cast is running opens a 2 s confirmation window; it is
  cancelled if negotiation keeps moving (states 1..3 or connecting progress > 0). Local
  disconnects are unaffected (`disconnectSession` clears `castStarted` first).
- **Transport badge fix.** The first frame can also arrive via SDK `onSurfaceReady()`, in which
  case the pixel-sampling path returns early (`surfaceReady` already set) and
  `resolveTransportLabel()` never ran — field logs show zero `[通道]` lines and no badge on
  0.3.7. Both first-frame paths now resolve the transport.

# MiAuto 0.3.7 — 2026-10-24

- **USB priority actually works now.** `isUsbAttached()` used `UsbManager.getAccessoryList()`,
  which only lists devices already in AOA accessory mode — a phone plugged in enumerates as an
  MTP `UsbDevice`, so the check was always false and the feature never fired (zero "USB 优先"
  lines in field logs). Now it checks the `UsbDevice` list; the receiver also listens to
  `USB_DEVICE_ATTACHED/DETACHED`, and detach triggers a 2.5 s debounced re-advertise (field
  logs show 1–2 s attach/detach flapping during phone re-enumeration).
- **Transport badge.** The projection page shows the active data channel ("USB AOA 有线" /
  "P2P Wi-Fi 5GHz" — band via reflected `WifiP2pGroup.getFrequency`, best-effort) and the
  standby screen appends it to the status line.
- **Dead-frame protection.** `dev=-1 → STATE_-1` (the SDK can no longer even identify the
  phone) held for 2 s while a cast was running is treated as phone-side loss: the session is
  torn down, the projection page exits, and the standby screen re-advertises — instead of
  freezing on the last frame until the car powers down.

# MiAuto 0.3.6 — 2026-10-24

- **Touch finally fixed — it was our own bug.** 0.3.5's native-log capture exposed the
  actual rejection reason: `UibcManager: sendTouchEvent failed: count error!` The SDK
  requires `ids.length == xs.length == ys.length == count`, but IccoaTouch hands over
  fixed 16-slot buffers while count is the real pointer count (1 for a tap). Every touch
  was therefore illegal — regardless of transport. Arrays are now trimmed to `count`
  before the call. This also explains "touch used to work in older versions": the old
  code copied per-count arrays; the rewrite regressed it.

# MiAuto 0.3.5 — 2026-10-24

- **Native UIBC logs are now in the report.** `UibcManager.sendTouchEvent`'s last hop is
  `SinkNative.onUibcEvent` — a native method implemented in `libovmsink.so`. When it returns
  false the Java side can't see why, but the native stack logs `uibc->started, connect failed!`,
  `uibc->event, ECONNRESET, EPIPE!`, `send datalen check failed` and the negotiated
  `wfd_uibc_capability` line. None of that matched the `easylog_` filter, so 0.3.4 reports
  were blind at the exact layer where touch dies. Reports now capture these lines (≤300).
- **libusbio preload check.** Field evidence: an installed package whose
  `/data/app/.../libusbio.so` read back as all zeros (`bad ELF magic: 00000000`) crashed on
  the main thread 9 times in a row at startup. The app now loads the library itself first and
  logs an explicit diagnosis (file head bytes + reinstall hint) instead of leaving a bare
  `UnsatisfiedLinkError`.
- **Build-time ELF verification.** A new `verifyNativeLibsElf` gradle task checks the first
  4 bytes of every jniLibs `.so` before packaging and fails the build on a bad file.

# MiAuto 0.3.4 — 2026-10-24

- **Touch over USB is no longer silently rejected.** Decompiling `UibcManager.sendTouchEvent`
  shows three preconditions; `mSourceWidth/mSourceHeight` is only ever set from
  `onWfdSinkReady ← onCastVideoInitialized`, and every `CastListener` callback is
  Wi-Fi-Display-only. On an AOA (USB) session that callback never fires, so the source size
  stays 0 and the SDK rejects every touch — while `sendKeyEvent` (steering-wheel keys) checks
  none of it, which is why buttons kept working. 0.3.4 reflectively calls
  `UCarAdapter.updateVideoSourceSize(videoW, videoH)` after `startCast` (and once per rejected
  touch, budgeted), making source == display so the mapping is 1:1. The same lazy fix removes
  the coordinate skew on wireless sessions when the phone reports a source size that differs
  from the video canvas.
- **UIBC state is now visible in reports.** A `[UIBC]` snapshot line (source/display size,
  encryption flag, session id, read reflectively from the SDK's `UibcManager`) is appended to
  the capability line and logged on every rejected touch — "source=0x0" is now direct
  evidence instead of something to reverse-engineer from behaviour.
- **Crash records survive.** Each uncaught exception writes a timestamped
  `crash-<ts>.txt` (last 5 kept) with version/board/thread/stack; on next start the summary is
  injected into the app log so it rides along with the diagnostic report. Reports also read
  the system crash buffer (`logcat -b crash`), which catches native crashes
  (SinkNative/decoder segfaults) that a Java handler cannot see.
- **Hardening:** multicast-lock failure logs once instead of per projection entry (it was
  eating the report's 6000-line budget); volume adjustment is guarded against
  `SecurityException` on ROMs that lock volume under DND.

# MiAuto 0.3.3 — 2026-10-24

- **USB priority now has a way back.** If an accessory is attached but no AOA link comes up
  within 10 s (a charge-only cable, or a phone that will not switch to accessory mode), the
  Bluetooth advertisement is restored automatically. Without the fallback the app would sit
  on "waiting for phone" with no pairing code and no way forward.

