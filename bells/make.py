import json, sys
sys.path.insert(0, ".")
import bowl
# Measured 180 mm bowl ratios (Csound modal table); model ratios for comparison.
MEASURED = [1, 2.77828, 5.18099, 8.16289, 11.66063, 15.63801]
def bell(f0, t60, split, mallet_ms, seconds):
    parts = []
    for k, r in enumerate(MEASURED):
        parts.append({"ratio": r, "amp": [1, .7, .45, .25, .12, .06][k],
                      "t60": round(t60 / r ** 0.8, 1), "split": round(split * r ** 0.5, 2),
                      "mix": 0.4})
    return {"f0": f0, "seconds": seconds, "mallet_ms": mallet_ms, "noise": 0.015, "partials": parts}
cands = {
 "bowl-large-110hz": bell(110, 50, 0.6, 1.6, 45),
 "bowl-medium-165hz": bell(165, 40, 0.9, 1.2, 40),
 "bowl-small-245hz": bell(245, 30, 1.2, 0.9, 32),
 "bowl-small-330hz": bell(330, 24, 1.6, 0.7, 28),
}
for name, p in cands.items():  
    json.dump(p, open(f"{name}.json", "w"), indent=1)
    bowl.write(f"{name}.wav", bowl.synth(p))
print(bowl.model_ratios(6))
