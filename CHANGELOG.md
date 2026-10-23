# MiAuto 0.3.2 — 2026-10-24

- **Volume keys now work while projecting.** The mirrored audio plays on the head unit's media
  stream, and ICCOA has no volume key codes (only mute), so forwarding them to the phone would
  be meaningless. The projection screen now consumes VOLUME_UP / VOLUME_DOWN / VOLUME_MUTE,
  adjusts the local music stream, and draws its own 1.2 s percentage overlay — the system does
  not always show one on a full-screen projection activity.
- **USB priority, on by default.** Once a wireless link completes its handshake the SDK calls
  stopAdvertise(), which shuts the cable out for the rest of the session. With USB priority
  enabled, attaching a USB accessory stops the Bluetooth advertisement so the phone can only
  come in over AOA; detaching restores it. Switchable in Connection setup → "USB priority".
  Turn it off for "whichever connects first".

