# Original Default audio synthesis. CC0-1.0; no third-party samples.
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
ROOT.mkdir(parents=True, exist_ok=True)
RATE = 24000
TONES = {
    "default_send": (.13, [(0, .12, 510, 640, .18)]),
    "default_receive": (.18, [(0, .16, 620, 510, .15), (.045, .10, 770, 650, .045)]),
    "default_error": (.16, [(0, .14, 390, 330, .13)]),
    "default_menu": (.10, [(0, .09, 450, 510, .10)]),
    "default_module": (.10, [(0, .08, 550, 490, .10)]),
    "default_confirm": (.15, [(0, .13, 580, 690, .13)]),
    "default_notification": (.38, [(0, .25, 480, 540, .17), (.075, .27, 640, 640, .11)]),
}
for name, (duration, voices) in TONES.items():
    values = []
    for i in range(round(duration * RATE)):
        t = i / RATE
        value = 0.
        for start, length, first, last, level in voices:
            u = t - start
            if 0 <= u < length:
                attack = min(1., u / .006)
                release = min(1., (length - u) / .024)
                envelope = attack * release * math.exp(-3.2 * u / length)
                phase = 2 * math.pi * (first * u + (last - first) * u * u / (2 * length))
                value += level * envelope * (math.sin(phase) + .11 * math.sin(2 * phase))
        values.append(struct.pack("<h", round(max(-1., min(1., value)) * 32767)))
    with wave.open(str(ROOT / (name + ".wav")), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(b"".join(values))
