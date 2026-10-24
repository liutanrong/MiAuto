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

