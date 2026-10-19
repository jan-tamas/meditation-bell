"""Struck singing-bowl synthesis, and measuring a recording to drive it.

  bowl.py synth  params.json out.wav     render a bell from parameters
  bowl.py measure in.wav  params.json    estimate parameters from a recording

A struck bowl is a set of rim modes (n,0), n = 2, 3, 4 ... Each mode is a
DOUBLET: the bowl is never perfectly round, so each mode splits into two
frequencies a few hertz apart, and their sum is the slow beating a bowl is
known for. Each partial decays exponentially, the higher ones faster.

Mode frequencies, Terwagne & Bush, "Tibetan Singing Bowls" (arXiv 1106.6348),
eq. 5 with m = 0:  f(n,0) ∝ (n²-1) / sqrt(1 + 1/n²)
which puts the partials at 1 : 2.83 : 5.42 : 8.77 ... of the lowest. Measured
bowls sit a little lower (1 : 2.78 : 5.18 : 8.16, Csound's modal table for a
180 mm bowl), so `measure` replaces the model whenever a recording exists.
"""
import json
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import find_peaks

RATE = 48000


def model_ratios(count):
    f = [(n * n - 1) / np.sqrt(1 + 1 / (n * n)) for n in range(2, 2 + count)]
    return [x / f[0] for x in f]


def synth(p):
    """p: {f0, seconds, partials: [{ratio, amp, t60, split}], mallet_ms, noise}"""
    t = np.arange(int(p["seconds"] * RATE)) / RATE
    out = np.zeros_like(t)
    rng = np.random.default_rng(p.get("seed", 1))
    for q in p["partials"]:
        f = p["f0"] * q["ratio"]
        if f > RATE / 2.2:
            continue
        decay = np.exp(-6.91 * t / q["t60"])          # 60 dB down at t60
        # The strike point decides how much of each doublet half sounds.
        w = q.get("mix", 0.5)
        ph = rng.uniform(0, 2 * np.pi, 2)
        tone = ((1 - w) * np.sin(2 * np.pi * (f - q["split"] / 2) * t + ph[0])
                + w * np.sin(2 * np.pi * (f + q["split"] / 2) * t + ph[1]))
        # A soft mallet stays on the rim for a few ms, which damps high partials:
        # the spectrum of a half-sine contact pulse of that length.
        T = p["mallet_ms"] / 1000
        x = 2 * f * T
        mallet = abs(np.cos(np.pi * x / 2) / (1 - x * x)) if abs(x - 1) > 1e-6 else np.pi / 4
        out += q["amp"] * mallet * tone * decay
    # The knock of the mallet itself: a few ms of low-passed noise.
    n = int(0.006 * RATE)
    knock = np.convolve(rng.standard_normal(n), np.ones(24) / 24, "same")
    out[:n] += p.get("noise", 0.02) * knock * np.hanning(2 * n)[n:]
    # Soften the very first sample so the attack does not click.
    a = int(0.002 * RATE)
    out[:a] *= np.linspace(0, 1, a)
    return out / np.max(np.abs(out)) * 0.89


def write(path, y):
    wavfile.write(path, RATE, (y * 32767).astype(np.int16))


def read(path):
    rate, y = wavfile.read(path)
    y = y.astype(np.float64)
    if y.ndim > 1:
        y = y.mean(axis=1)
    return rate, y / (np.max(np.abs(y)) or 1)


def measure(path, count=6):
    """Partials, their decay and their beating, from a recording of one strike."""
    rate, y = read(path)
    start = int(np.argmax(np.abs(y) > 0.3))
    y = y[start:]
    # A long window resolves the doublets; skip the strike's first 50 ms.
    seg = y[int(0.05 * rate):int(0.05 * rate) + 8 * rate]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    freqs = np.fft.rfftfreq(len(seg), 1 / rate)
    band = freqs > 60
    peaks, _ = find_peaks(spec * band, height=spec.max() * 0.003, distance=int(15 / (freqs[1])))
    peaks = sorted(peaks, key=lambda i: -spec[i])[:count]
    peaks.sort()
    f0 = freqs[peaks[0]]
    partials = []
    for i in peaks:
        f = freqs[i]
        # Envelope of this partial over time: magnitude at f in 0.25 s frames.
        hop, win = rate // 4, rate // 2
        env = []
        for s in range(0, len(y) - win, hop):
            fr = y[s:s + win] * np.hanning(win)
            k = np.exp(-2j * np.pi * f * np.arange(win) / rate)
            env.append(abs(np.dot(fr, k)))
        env = np.array(env)
        db = 20 * np.log10(env / env.max() + 1e-12)
        tt = np.arange(len(env)) * hop / rate
        use = db > -50
        slope = np.polyfit(tt[use], db[use], 1)[0] if use.sum() > 3 else -60
        t60 = float(min(120, -60 / slope)) if slope < 0 else 120.0
        # Beating: the strongest slow wobble left after removing the decay.
        resid = db[use] - np.polyval(np.polyfit(tt[use], db[use], 1), tt[use])
        split = 0.0
        if use.sum() > 16:
            r = np.abs(np.fft.rfft(resid * np.hanning(len(resid))))
            rf = np.fft.rfftfreq(len(resid), hop / rate)
            ok = (rf > 0.1) & (rf < 1.9)
            if ok.any() and r[ok].max() > 2 * np.median(r[1:]):
                split = float(rf[ok][np.argmax(r[ok])])
        partials.append({"ratio": round(f / f0, 4), "amp": round(float(spec[i] / spec[peaks[0]]), 4),
                         "t60": round(t60, 1), "split": round(split, 2)})
    return {"f0": round(float(f0), 2), "seconds": round(max(q["t60"] for q in partials[:2]), 1),
            "mallet_ms": 0.1, "noise": 0.02, "partials": partials}


if __name__ == "__main__":
    cmd, src, dst = sys.argv[1:4]
    if cmd == "synth":
        write(dst, synth(json.load(open(src))))
    elif cmd == "measure":
        json.dump(measure(src), open(dst, "w"), indent=1)
    else:
        sys.exit(__doc__)
