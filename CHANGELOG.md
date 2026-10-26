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

