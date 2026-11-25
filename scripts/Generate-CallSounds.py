# Original Default call cues. CC0-1.0. No Telegram recordings or third-party samples.
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
RATE = 24000
# duration; (start, length, first frequency, final frequency, amplitude)
TONES = {
    "call_start": (.17, [(0, .14, 440, 600, .13)]),
    "call_ringback": (3.0, [(0, .85, 440, 440, .09), (0, .85, 480, 480, .07)]),
    "call_connecting": (1.25, [(0, .11, 520, 520, .085), (.22, .11, 520, 520, .065)]),
    "call_connected": (.16, [(0, .13, 600, 800, .10)]),
    "call_busy": (.80, [(0, .22, 480, 480, .10), (0, .22, 620, 620, .06),
                        (.42, .22, 480, 480, .10), (.42, .22, 620, 620, .06)]),
    "call_ended": (.30, [(0, .26, 550, 330, .12)]),
    "call_failed": (.48, [(0, .17, 420, 330, .10), (.23, .18, 330, 250, .10)]),
}
ROOT.mkdir(parents=True, exist_ok=True)
for name, (duration, voices) in TONES.items():
    frames = bytearray()
    for i in range(round(duration * RATE)):
        t = i / RATE
        value = 0.0
        for start, length, first, last, level in voices:
            u = t - start
            if 0 <= u < length:
                envelope = min(1.0, u / .008) * min(1.0, (length - u) / .025)
                phase = 2 * math.pi * (first * u + (last - first) * u * u / (2 * length))
                value += level * envelope * math.sin(phase)
        frames.extend(struct.pack("<h", round(max(-1.0, min(1.0, value)) * 32767)))
    with wave.open(str(ROOT / (name + ".wav")), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(frames)
