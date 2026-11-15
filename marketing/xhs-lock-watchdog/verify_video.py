#!/usr/bin/env python3
"""成片音频/视频验收门禁 —— 每次出片必须全绿才能交付（杜绝声音事故复发）。

用法:
    python3 verify_video.py <mp4路径>

背景（为什么要有这个脚本）:
    曾发生过两类声音事故：① 渲染器 bug 导致画面冻帧但音轨"看似正常"；
    ② 交付后才发现音轨静音/中断。肉眼抽检骗过了验收，因此本脚本对
    整条音轨做分段机器测量，任何一项不达标即判 FAIL（exit 1）。

六道门禁:
    G1 双流存在        必须同时含 h264 视频流与 aac 音频流
    G2 时长一致        视频时长与音频时长差 < 0.5s（防 -shortest 截断事故）
    G3 总响度合理      mean_volume ∈ [-30, -8] dB 且 max_volume ∈ [-8, -1] dB
    G4 六段分段响度    每段 mean > -30 dB（防"开头有声音后面静音"）；
                       相邻段差 < 6 dB（防音量突跳）
    G5 无中途静音      silencedetect 探测 ≥2s 静音段，只允许出现在
                       开头 2s（淡入）与结尾 3s（淡出）内
    G6 规格正确        1080x1920 @ 25fps（小红书竖版规格）

依赖: ffmpeg / ffprobe（无其他第三方包）。
"""
import json
import re
import subprocess
import sys

SEG = 6                      # 分段响度的段数
SEG_MEAN_MIN = -30.0         # G4: 每段最低平均响度
SEG_MAX_DIFF = 6.0           # G4: 相邻段平均响度最大跳变
MEAN_RANGE = (-30.0, -8.0)   # G3: 总平均响度区间
MAX_RANGE = (-8.0, -1.0)     # G3: 总峰值区间
SIL_MIN_DUR = 2.0            # G5: 静音判定时长
SIL_OK_HEAD, SIL_OK_TAIL = 2.0, 3.0

def sh(cmd):
    # 安全：参数列表形式 + 显式 shell=False——cmd 元素不经 shell 解析，无拼接注入面
    return subprocess.run(cmd, capture_output=True, text=True, shell=False)

def die(msg):
    print(f"FAIL  {msg}")
    sys.exit(1)

def parse_db(text):
    m = re.search(r"mean_volume: (-?[\d.]+) dB", text)
    mean = float(m.group(1)) if m else None
    m = re.search(r"max_volume: (-?[\d.]+) dB", text)
    peak = float(m.group(1)) if m else None
    return mean, peak

def main(path):
    gates = []

    # G1 双流
    r = sh(["ffprobe", "-v", "error", "-show_entries",
            "stream=index,codec_name,codec_type,width,height,r_frame_rate",
            "-of", "json", path])
    streams = json.loads(r.stdout or "{}").get("streams", [])
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    ok = v and a and v.get("codec_name") == "h264" and a.get("codec_name") == "aac"
    gates.append(("G1 双流 h264+aac", bool(ok),
                  f"video={v and v.get('codec_name')} audio={a and a.get('codec_name')}"))
    if not (v and a):
        die_report(gates)

    # G2 时长一致
    r = sh(["ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "json", path])
    dur = float(json.loads(r.stdout or "{}").get("format", {}).get("duration", 0))
    r = sh(["ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=duration", "-of", "json", path])
    adur = float(json.loads(r.stdout or "{}").get("streams", [{}])[0].get("duration", 0))
    ok = abs(dur - adur) < 0.5
    gates.append((f"G2 时长一致 ({dur:.2f}s vs {adur:.2f}s)", ok, ""))

    # G3 总响度
    r = sh(["ffmpeg", "-i", path, "-map", "0:a", "-af", "volumedetect",
            "-f", "null", "-"])
    mean, peak = parse_db(r.stderr)
    ok = (mean is not None and peak is not None
          and MEAN_RANGE[0] <= mean <= MEAN_RANGE[1]
          and MAX_RANGE[0] <= peak <= MAX_RANGE[1])
    gates.append((f"G3 总响度 mean={mean} peak={peak}", ok, ""))

    # G4 六段分段响度
    step = dur / SEG
    seg_means = []
    for i in range(SEG):
        r = sh(["ffmpeg", "-i", path, "-ss", f"{i * step:.2f}", "-t", f"{step:.2f}",
                "-map", "0:a", "-af", "volumedetect", "-f", "null", "-"])
        m, _ = parse_db(r.stderr)
        seg_means.append(m)
    ok_seg = all(m is not None and m > SEG_MEAN_MIN for m in seg_means)
    max_jump = 0.0
    if ok_seg:
        max_jump = max(abs(seg_means[i + 1] - seg_means[i]) for i in range(SEG - 1))
        ok_seg = max_jump < SEG_MAX_DIFF
    gates.append((f"G4 六段响度 {[round(m, 1) for m in seg_means]}",
                  ok_seg, f"相邻段最大跳变 {max_jump:.1f} dB"))

    # G5 无中途静音（silencedetect）
    r = sh(["ffmpeg", "-i", path, "-map", "0:a",
            "-af", f"silencedetect=noise=-35dB:d={SIL_MIN_DUR}", "-f", "null", "-"])
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", r.stderr)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", r.stderr)]
    bad = [(s, e) for s, e in zip(starts, ends) if s > SIL_OK_HEAD and e < dur - SIL_OK_TAIL]
    gates.append((f"G5 无中途静音(≥{SIL_MIN_DUR}s)", len(bad) == 0,
                  f"静音段 {bad}" if bad else f"检测到 {len(starts)} 段，均在允许区"))

    # G6 规格
    ok = (v.get("width") == 1080 and v.get("height") == 1920
          and v.get("r_frame_rate") in ("25/1", "25"))
    gates.append((f"G6 规格 1080x1920@25 ({v.get('width')}x{v.get('height')}@{v.get('r_frame_rate')})",
                  ok, ""))

    # G7 视频连续性（防冻帧事故复发）：6 个采样点画面亮度必须互异
    ys = []
    tmp = "/tmp/verify_video_sample.png"
    for i in range(6):
        t = dur * i / 6.0 + 0.5
        sh(["ffmpeg", "-y", "-v", "error", "-ss", f"{t:.2f}", "-i", path,
            "-frames:v", "1", tmp])
        r = sh(["ffmpeg", "-i", tmp, "-vf", "signalstats,metadata=print",
                "-f", "null", "-"])
        m = re.search(r"YAVG=([\d.]+)", r.stderr)
        ys.append(float(m.group(1)) if m else -1.0)
    ok = len(set(ys)) == 6
    gates.append((f"G7 视频连续性 (6 采样 YAVG 互异)",
                  ok, f"采样值 {ys}"))

    print("=" * 56)
    all_ok = True
    for name, ok, extra in gates:
        mark = "PASS" if ok else "FAIL"
        all_ok &= ok
        print(f"{mark}  {name}" + (f"   [{extra}]" if extra else ""))
    print("=" * 56)
    if not all_ok:
        die_report(gates)
    print("ALL GREEN —— 音视频验收通过，可交付")
    print(json.dumps({"file": path, "duration": dur,
                      "gates": [{"name": nm, "pass": o, "detail": e}
                                for nm, o, e in gates]},
                     ensure_ascii=False, indent=2))

def die_report(gates):
    for name, ok, extra in gates:
        print(("PASS  " if ok else "FAIL  ") + name + (f"   [{extra}]" if extra else ""))
    sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    import os
    # 输入校验（三重防穿越/防注入）：realpath 解析 + .mp4 后缀白名单 +
    # 拒绝 shell 元字符；subprocess 全程参数列表 + shell=False
    p = os.path.realpath(sys.argv[1])
    if not p.endswith(".mp4") or not os.path.isfile(p):
        print(f"FAIL  输入必须是本地存在的 .mp4 文件: {sys.argv[1]}")
        sys.exit(2)
    if re.search(r'[;|&$`<>()\\\n]', p):
        print(f"FAIL  路径含非法字符: {sys.argv[1]}")
        sys.exit(2)
    main(p)
