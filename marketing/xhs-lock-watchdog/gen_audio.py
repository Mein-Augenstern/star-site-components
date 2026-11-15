#!/usr/bin/env python3
"""为小红书视频合成音轨（纯标准库，可复现）。

  bgm  : 暗色氛围垫乐（Am9→Fmaj7→Cmaj7→G6，无打击无人声，微失谐正弦 + 唱片碎响）
  sfx  : whoosh（章节转场）/ ding（成功）/ thud（危险、P0 图章）

输出 mix.wav（63.5s 单轨混音成品，供 ffmpeg 直 mux）。
"""
import array
import math
import random
import struct
import wave

SR = 22050
DUR = 63.5
N = int(SR * DUR)
mix = [0.0] * N

def add_sample(i, v):
    if 0 <= i < N:
        mix[i] += v

def tone(start, dur, freq, vol, attack=0.6, release=1.2, detune=0.15, harmonics=((1, 1.0),)):
    """柔和正弦叠层：慢起音慢释放 + 双层微失谐。"""
    n0, n1 = int(start * SR), min(int((start + dur) * SR), N)
    for i in range(n0, n1):
        t = i / SR - start
        env = min(1.0, t / attack) * min(1.0, max(0.0, (dur - t) / release))
        if env <= 0:
            continue
        s = 0.0
        for mult, amp in harmonics:
            s += amp * (
                math.sin(2 * math.pi * freq * mult * t)
                + 0.5 * math.sin(2 * math.pi * freq * mult * (1 + detune * 0.001) * t + 0.7)
            ) / 1.5
        add_sample(i, s * env * vol)

def pluck(start, freq, vol):
    """低音量拨弦：正弦 + 指数衰减。"""
    dur = 1.1
    n0, n1 = int(start * SR), min(int((start + dur) * SR), N)
    for i in range(n0, n1):
        t = i / SR - start
        env = math.exp(-t * 5.5) * min(1.0, t / 0.004)
        add_sample(i, (math.sin(2 * math.pi * freq * t)
                       + 0.25 * math.sin(2 * math.pi * freq * 2 * t)) * env * vol)

# ---------- BGM：D 小调 tech-documentary（低音脉冲 + 规整琶音 + 空气垫） ----------
# Dm9 → Bbmaj7 → Fmaj7 → Cadd9，16s 环 × 4
CHORDS = [
    (73.42,  [174.61, 261.63, 329.63], [293.66, 349.23, 440.00, 587.33]),  # D2 + F3 C4 E4 / D4 F4 A4 D5
    (116.54, [146.83, 293.66, 349.23], [233.08, 293.66, 440.00, 587.33]),  # Bb2 + D3 D4 F4 / Bb3 D4 A4 D5
    (87.31,  [220.00, 261.63, 329.63], [349.23, 440.00, 523.25, 698.46]),  # F2 + A3 C4 E4 / F4 A4 C5 F5
    (65.41,  [164.81, 196.00, 293.66], [261.63, 329.63, 392.00, 587.33]),  # C2 + E3 G3 D4 / C4 E4 G4 D5
]
BAR = 4.0
pos = 0.0
bar_i = 0
while pos < DUR - 0.5:
    root, pad_notes, arp_notes = CHORDS[bar_i % 4]
    fade = 0.4
    tone(pos, BAR + fade, root, 0.15, attack=0.9, release=fade, harmonics=((1, 1.0), (2, 0.18)))
    for j, f in enumerate(pad_notes):
        tone(pos + 0.05 * j, BAR + fade, f, 0.045, attack=1.3, release=fade)
    # 规整琶音：八分音符循环和弦音（tech 感的机械律动）
    for step in range(8):
        f = arp_notes[step % len(arp_notes)]
        pluck(pos + step * 0.5, f, 0.045)
    pos += BAR
    bar_i += 1

# 低音心跳脉冲：每 0.5s 一次 55Hz 短促垫底（每小节首拍加重）
pulse_i = 0
while pulse_i * 0.5 < DUR - 0.3:
    at = pulse_i * 0.5
    accent = 1.5 if pulse_i % 8 == 0 else 1.0
    n0, n1 = int(at * SR), min(int((at + 0.22) * SR), N)
    for k in range(n1 - n0):
        t = k / SR
        env = math.exp(-t * 14) * min(1.0, t / 0.003)
        add_sample(n0 + k, math.sin(2 * math.pi * 55 * t) * env * 0.11 * accent)
    pulse_i += 1

# 唱片碎响（琥珀氛围的点睛）
rnd = random.Random(42)
crackle_n = int(DUR * SR * 0.0007)
for _ in range(crackle_n):
    i = rnd.randrange(N)
    add_sample(i, rnd.uniform(-1, 1) * 0.045)
    add_sample(i + 1, rnd.uniform(-1, 1) * 0.02)

# ---------- SFX ----------
def sfx_whoosh(at, vol=0.5):
    """噪声扫频：幅度包络先升后降 + 伪带通（相邻样本平滑）。"""
    dur = 0.7
    n0 = int(at * SR)
    prev = 0.0
    for k in range(int(dur * SR)):
        t = k / (dur * SR)
        env = math.sin(math.pi * min(1.0, t)) ** 1.6
        x = rnd2.uniform(-1, 1)
        prev = prev * 0.86 + x * 0.14          # 简单单极低通 → 风
        add_sample(n0 + k, prev * env * vol)

def sfx_ding(at, vol=0.34):
    for f, v, d in ((880.0, 1.0, 0.9), (1318.5, 0.45, 0.7), (1760.0, 0.2, 0.5)):
        n0, n1 = int(at * SR), min(int((at + d) * SR), N)
        for k in range(n1 - n0):
            t = k / SR
            env = math.exp(-t * 4.2) * min(1.0, t / 0.003)
            add_sample(n0 + k, math.sin(2 * math.pi * f * t) * env * v * vol)

def sfx_thud(at, vol=0.5):
    n0, n1 = int(at * SR), min(int((at + 0.5) * SR), N)
    for k in range(n1 - n0):
        t = k / SR
        f = 90 * math.exp(-t * 6) + 38         # 快速下坠的厚低音
        env = math.exp(-t * 8) * min(1.0, t / 0.002)
        add_sample(n0 + k, math.sin(2 * math.pi * f * t) * env * vol)

rnd2 = random.Random(7)
# 章节转场 whoosh
for at in (3.5, 12.0, 22.0, 36.0, 48.0, 58.0):
    sfx_whoosh(at)
# 危险 thud：TTL 过期锁被抢（8.7 屏震起点）+ P0 图章
sfx_thud(8.7, 0.55)
sfx_thud(30.0, 0.6)
# 成功 ding：S3 集群恰一完成 / S5 修复 / CTA 钉住
sfx_ding(20.3)
sfx_ding(42.2)
sfx_ding(58.2, 0.26)

# ---------- 总线：淡入淡出 + 软限幅 ----------
FADE_IN, FADE_OUT = 1.5, 2.5
peak = max(abs(v) for v in mix) or 1.0
gain = 0.82 / peak
out = array.array("h", bytes(N * 2))
for i in range(N):
    t = i / SR
    g = gain
    if t < FADE_IN:
        g *= t / FADE_IN
    if t > DUR - FADE_OUT:
        g *= max(0.0, (DUR - t) / FADE_OUT)
    v = mix[i] * g
    v = math.tanh(v * 1.1)                    # 软限幅去毛刺
    out[i] = int(v * 32767 * 0.96)

with wave.open("mix.wav", "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(out.tobytes())
print(f"mix.wav written: {DUR}s @ {SR}Hz")
