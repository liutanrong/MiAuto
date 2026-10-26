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

