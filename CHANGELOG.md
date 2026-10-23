# MiAuto 0.2.1 — 2026-10-24

DiLink 3.0 (BYD Qin PLUS DM-i 2021, Android 10 / Snapdragon 665) hardening release.
Everything below was driven by on-device logs, not by guesswork; the reverse-engineering
notes behind it are in `docs/ICCOA-RUNTIME-FINDINGS.md`.

- **Wireless link: declare one transport instead of both.** Declaring Wi-Fi Direct and SoftAP
  together made the SDK defer to the phone's preference, and a mismatch ended in
  `notifyConnectFailed(3)` → code 10003 after ~15 seconds stuck at progress 10. The transport now
  follows the "Network transport" setting and only one capability is advertised.
- **SoftAP no longer crashes the config.** An empty 5 GHz channel list throws
  `channels can't be null` and aborts `buildConfig()`, so no pairing code ever appeared. The list
  is now only set on the Wi-Fi Direct path.
- **USB (AOA) actually works.** `enableUsbDeviceDetection(true)` was never called upstream, and
  four places in the Android Auto stack had to stop claiming the accessory bulk endpoint.
- **Real DPI and configurable frame rate.** DPI follows the panel (1920×990 is ~172 dpi, not 320);
  FPS follows the existing "FPS Limit" setting so a 665 can be dropped to 30 without a rebuild.
- **Touch injection.** `sendTouchEvent()` existed but was never called — the mirrored UI accepted
  no input at all. Coordinates are normalised to the video canvas.
- **No more black screen while connecting.** A first-frame probe (PixelCopy, 64×36, non-black
  pixel count) replaced the SDK's `onSurfaceReady()`, which provably never fires on healthy
  sessions. The projection screen keeps an opaque brand veil on top until pixels are confirmed.
- **Cold start is judged quickly.** The first session after a process start never receives video
  from the phone (see the findings doc for the five-round evidence). It is now abandoned at
  `progress 90 + 900ms` and reconnected, instead of waiting 9 seconds on a screen that will stay black.
- **Streaming quality:** low-latency decode mode is actually enabled (a duplicated setter used to
  override it), and Wi-Fi high-performance + multicast locks are held while projecting.

Known limitation: the first connection after a cold start still needs one automatic reconnect.
It is a phone-side behaviour and cannot be fixed from the head unit.

