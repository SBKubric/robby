#!/usr/bin/env python3
"""Tiny WAV helper for the SIP checks (stdlib only).

  wavtool.py tone FILE FREQ_HZ SECONDS      write a 8 kHz / 16-bit / mono sine tone
  wavtool.py analyze FILE EXPECT_HZ [OTHER_HZ]
      print duration, RMS (dBFS), share of 20 ms frames with signal, and the
      Goertzel power at EXPECT_HZ vs OTHER_HZ; exit 0 only if the recording is
      non-silent and EXPECT_HZ clearly dominates (the far side's tone came through).
"""
import math
import struct
import sys
import wave

RATE = 8000


def tone(path, freq, seconds):
    n = int(RATE * seconds)
    amp = 0.5 * 32767
    frames = b"".join(
        struct.pack("<h", int(amp * math.sin(2 * math.pi * freq * i / RATE))) for i in range(n)
    )
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


def goertzel(samples, rate, freq):
    k = 2 * math.cos(2 * math.pi * freq / rate)
    s1 = s2 = 0.0
    for x in samples:
        s0 = x + k * s1 - s2
        s2, s1 = s1, s0
    return s1 * s1 + s2 * s2 - k * s1 * s2


def analyze(path, expect, other):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            print(f"{path}: unsupported sample width {w.getsampwidth()}")
            return 2
        rate, ch = w.getframerate(), w.getnchannels()
        raw = w.readframes(w.getnframes())
    samples = list(struct.unpack(f"<{len(raw) // 2}h", raw))[::ch]
    dur = len(samples) / rate if rate else 0
    if not samples:
        print(f"{path}: empty recording")
        return 1
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    dbfs = 20 * math.log10(rms / 32768) if rms > 0 else -200.0
    frame = rate // 50  # 20 ms
    voiced = 0
    total = 0
    for i in range(0, len(samples) - frame + 1, frame):
        total += 1
        f = samples[i:i + frame]
        if math.sqrt(sum(s * s for s in f) / frame) > 300:  # about -40 dBFS
            voiced += 1
    p_exp = goertzel(samples, rate, expect)
    p_oth = goertzel(samples, rate, other)
    ratio_db = 10 * math.log10((p_exp + 1e-9) / (p_oth + 1e-9))
    share = voiced / total if total else 0
    ok = share > 0.3 and ratio_db > 20
    print(
        f"{path}: {dur:.1f} s, {rate} Hz, RMS {dbfs:.1f} dBFS, "
        f"frames with signal {voiced}/{total} ({share:.0%}), "
        f"{expect:g} Hz vs {other:g} Hz: {ratio_db:+.1f} dB -> {'OK' if ok else 'FAIL'}"
    )
    return 0 if ok else 1


def main(argv):
    if len(argv) >= 5 and argv[1] == "tone":
        tone(argv[2], float(argv[3]), float(argv[4]))
        return 0
    if len(argv) >= 4 and argv[1] == "analyze":
        other = float(argv[4]) if len(argv) > 4 else 3000.0
        return analyze(argv[2], float(argv[3]), other)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
