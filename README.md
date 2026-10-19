# Meditation bell

"Meditation bell" is a meditation timer for Android that rings bells and saves each sitting to
Health Connect. Nothing is built yet. The design is in Jan's vault:
`wiki/analyses/2026-10-12-meditation-bell-app-to-health-connect.md`.

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
