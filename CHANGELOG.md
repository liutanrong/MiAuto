# MiAuto 0.3.0 — 2026-10-24

First release that uses the half of the ICCOA SDK the projector never touched: key events,
day/night, phone state, vehicle location. Plus a round of robustness fixes on the projection
path itself.

## Projection

- **Pixel probe no longer runs out of budget.** It used to sample 40 times (10 s) from
  `surfaceChanged` and then stop; if the phone took longer than that, the veil could never be
  lifted and the screen stayed black forever. The budget is now a 60 s window that is renewed
  on every connection-progress callback, so it waits as long as the link does.
- **Cold-start fast path.** The first session after a process start never receives video over
  Wi-Fi Direct (see `docs/ICCOA-RUNTIME-FINDINGS.md` §9). Instead of waiting for
  `progress 90 + 900 ms`, the session is now dropped 700 ms after `progress 10` and
  re-advertised — about 2.6 s earlier. **USB links are excluded**: AOA streams on the first
  session, so cutting it early would kill a working link.
- **Auto-open no longer interrupts Settings.** Changing a setting while a phone connects used
  to throw the user out of the settings screen and into the projection page; it now only
  auto-opens when the home screen is actually resumed.

## Car connection (new)

- **Steering-wheel / panel keys** are forwarded to the phone (`sendKeyEvent`): D-pad, enter,
  back, home, menu, media transport, page up/down, call and end-call. Volume keys are
  deliberately left local.
- **Day/night sync** (`notifySwitchDayOrNight`): the phone's UI follows the head unit's
  light/dark mode.
- **Call control** (`notifyPickUpCall` / `notifyHungUpCall`) is wired to the same key path.
- **Vehicle GPS uplink**: the head unit's last known position is pushed every 5 s while
  projecting, so the phone's navigation uses the car antenna. Constructed reflectively because
  the compile-time stub only declares the no-argument constructor.
- **Phone media / call state** (`onMusicInfoReceived` / `onPhoneStateInfoReceived`) now reach
  the UI layer through `IcocaBridge.State.music` / `.callState`.

## Internals

- Split `IccoaManager` (1.7 kLOC) into `IccoaTouch`, `IccoaSessionStats`, `IccoaCarLink` and
  `IccoaPrefs`; removed the dead 2×2-ImageReader prewarm path.
- The session summary line now also reports session count, reconnect count, the transport
  actually used and the phone model.
- Setting keys are constants instead of string literals.

