# Meditation bell

"Meditation bell" is a meditation timer for Android that rings bells and saves each sitting to
Health Connect. The design is in Jan's vault:
`wiki/analyses/2026-10-12-meditation-bell-app-to-health-connect.md`.

## What it does

- One screen: sitting length (default 1 h 30), an interval bell (default the
  medium bowl at 45 minutes), a closing bell (default the large bowl), and Do
  Not Disturb while sitting (on by default, can be switched off).
- No bell at the start, and nothing shown at the end: no count, no total.
- Pause, then resume or end. Paused time does not count: the bells still to
  come move later by the length of the pause, and no bell rings on resume. Do
  Not Disturb stays on while paused. A pause left for an hour ends the sitting.
  Each unbroken stretch of sitting is saved as its own session, so the minutes
  in Health Connect are the minutes sat.
- Each bell is an alarm-clock alarm, the one kind Android never delays, and
  plays as an alarm sound, so silent mode does not mute it. Do Not Disturb is
  set to "alarms only" and put back as it was when the sitting ends.
- Each sitting is saved to Health Connect as one meditation session (start
  and end; nothing is read). Ending early saves the real end. A sitting
  Health Connect refuses is kept and retried when the app is next opened.

## Building

GitHub Actions builds it (`.github/workflows/build.yml`): a test copy on every
push to `main`, and a signed release on a tag like `v0.1`, which is what
Obtainium installs. Locally: `gradle assembleDebug` with JDK 17 and the
Android SDK. The timing (`Clock.kt`) has unit tests, run on every build.

## Bells

Four struck singing bowls, synthesized from the physics of a bowl (`bells/bowl.py`),
so no recorded sound is used and all four can ship with the app.

| File | Lowest tone | Rings for | Default use |
|---|---|---|---|
| `bowl-medium-165hz.ogg` | 165 Hz | ~40 s | interval bell, at 45 minutes |
| `bowl-large-110hz.ogg` | 110 Hz | ~45 s | closing bell, at 1 h 30 |
| `bowl-small-245hz.ogg` | 245 Hz | ~32 s | option |
| `bowl-small-330hz.ogg` | 330 Hz | ~28 s | option |

No bell at the start. Rebuild: `cd bells && python make.py`, then convert each
`.wav` to `.ogg` (`ffmpeg -i x.wav -c:a libvorbis -q:a 6 x.ogg`). Needs numpy
and scipy. `python bowl.py measure rec.wav params.json` estimates the
parameters of a recorded bell, and `python bowl.py synth params.json out.wav`
renders one.
