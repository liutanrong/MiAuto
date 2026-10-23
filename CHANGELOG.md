# MiAuto 0.3.1 — 2026-10-24

Diagnostics release: every new 0.3.0 capability now says what it did, and the USB path stops
being treated like Wi-Fi Direct.

- **[能力] line at startup.** One log line reports the transport, video size, fps, and whether
  car-link / GPS uplink / touch mapping / the fast cold-start path are on, plus whether a USB
  accessory is attached. It is now obvious from a single report line which build and which
  switches produced the session.
- **USB is "slow", Wi-Fi Direct is "never".** A plugged-in AOA link does deliver video, only
  later than wireless; treating it with the 900 ms cold-start timeout (or the new 700 ms fast
  path) would kill a working session. USB now gets a 6 s first-frame budget and no fast bail,
  and logs the reason.
- **Key events log both outcomes.** A forwarded key is logged with its Android name; a key that
  has no ICCOA equivalent, or arrives while nothing is projecting, says so and falls through to
  the local handler. "I pressed it and nothing happened" is now diagnosable.
- **GPS uplink explains its silences.** Missing permission, no recent fix, and "SDK refused"
  are three distinct log lines instead of one silent skip.

