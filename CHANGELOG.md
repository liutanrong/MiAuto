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

