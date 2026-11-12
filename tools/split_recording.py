#!/usr/bin/env python3
"""녹음 파일 하나를 문장 사이의 쉼에서 잘라 문장별 소리 파일과 pack.json으로 만듭니다.

녹음할 때 문장과 문장 사이를 1초 넘게 쉬어 두면, 그 쉼을 찾아 자릅니다. ffmpeg가 있어야 합니다.

쓰는 법
  1) 어떻게 잘리는지 먼저 봅니다.
     python3 tools/split_recording.py 녹음.m4a --list
  2) 잘못 읽어 다시 읽은 조각의 번호를 빼고 만듭니다.
     python3 tools/split_recording.py 녹음.m4a --title "낭독 제목" --drop 3,7

결과는 app/src/main/assets/packs/my_recording 에 들어갑니다. 앱을 다시 빌드하면 콘텐츠 목록에 나타납니다.

주의: 이 도구는 소리만 다룹니다. 다른 사람이 쓴 작품의 글은 pack.json에 넣지 마세요.
직접 쓴 글을 읽은 녹음이라면 --texts 로 문장 글 파일(한 줄에 한 문장)을 함께 줄 수 있습니다.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKS = ROOT / "app" / "src" / "main" / "assets" / "packs"


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def total_seconds(src: str) -> float:
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src])
    if r.returncode != 0 or not r.stdout.strip():
        sys.exit(f"길이를 읽지 못했습니다: {src}\n{r.stderr.strip()}")
    return float(r.stdout.strip())


def find_segments(src: str, noise_db: float, min_silence: float, min_speech: float, pad: float):
    """말소리 구간의 (시작, 끝) 목록. 쉼은 ffmpeg silencedetect로 찾습니다."""
    total = total_seconds(src)
    r = run(["ffmpeg", "-hide_banner", "-nostats", "-i", src,
             "-af", f"silencedetect=noise={noise_db}dB:d={min_silence}", "-f", "null", "-"])
    starts = [float(x) for x in re.findall(r"silence_start: (-?[0-9.]+)", r.stderr)]
    ends = [float(x) for x in re.findall(r"silence_end: (-?[0-9.]+)", r.stderr)]
    silences = []
    for i, s in enumerate(starts):
        e = ends[i] if i < len(ends) else total
        silences.append((max(0.0, s), min(total, e)))
    segments = []
    cursor = 0.0
    for s, e in silences:
        if s - cursor >= min_speech:
            segments.append((cursor, s))
        cursor = max(cursor, e)
    if total - cursor >= min_speech:
        segments.append((cursor, total))
    # 앞뒤로 조금 여유를 둡니다.
    return [(max(0.0, a - pad), min(total, b + pad)) for a, b in segments], total


def peak_gain_db(src: str, target_db: float) -> float:
    r = run(["ffmpeg", "-hide_banner", "-nostats", "-i", src, "-af", "volumedetect", "-f", "null", "-"])
    m = re.search(r"max_volume: (-?[0-9.]+) dB", r.stderr)
    if not m:
        return 0.0
    return max(-6.0, min(20.0, target_db - float(m.group(1))))


def has_encoder(name: str) -> bool:
    return name in run(["ffmpeg", "-hide_banner", "-encoders"]).stdout


def main() -> int:
    ap = argparse.ArgumentParser(description="녹음을 문장별로 나눠 앱의 콘텐츠 묶음으로 만듭니다.")
    ap.add_argument("recording", help="녹음 파일 (m4a, mp3, wav 등)")
    ap.add_argument("--list", action="store_true", help="자를 구간만 보여 주고 파일은 만들지 않음")
    ap.add_argument("--pack", default="my_recording", help="묶음 폴더 이름 (기본 my_recording)")
    ap.add_argument("--title", default="직접 녹음한 낭독", help="앱에 보일 제목")
    ap.add_argument("--kind", choices=["story", "language"], default="story")
    ap.add_argument("--language", default="ko")
    ap.add_argument("--order", type=int, default=5, help="콘텐츠 목록에서의 순서. 작을수록 앞")
    ap.add_argument("--drop", default="", help="뺄 조각 번호. 쉼표로 구분 (예: 3,7)")
    ap.add_argument("--texts", default="", help="직접 쓴 글일 때만: 한 줄에 한 문장인 글 파일")
    ap.add_argument("--noise", type=float, default=-35.0, help="이 크기(dB)보다 작으면 쉼으로 봄")
    ap.add_argument("--min-silence", type=float, default=0.7, help="이 길이(초)보다 길어야 문장 사이로 봄")
    ap.add_argument("--min-speech", type=float, default=0.5, help="이보다 짧은 소리는 버림")
    ap.add_argument("--pad", type=float, default=0.12, help="문장 앞뒤에 남길 여유(초)")
    ap.add_argument("--out", default="", help="결과 폴더를 직접 지정 (시험용)")
    args = ap.parse_args()

    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        sys.exit("ffmpeg와 ffprobe가 필요합니다.")
    src = args.recording
    if not pathlib.Path(src).is_file():
        sys.exit(f"파일이 없습니다: {src}")

    segments, total = find_segments(src, args.noise, args.min_silence, args.min_speech, args.pad)
    drop = {int(x) for x in args.drop.split(",") if x.strip()}
    print(f"전체 {total:.1f}초에서 조각 {len(segments)}개를 찾았습니다.")
    for i, (a, b) in enumerate(segments, start=1):
        mark = "  (뺌)" if i in drop else ""
        print(f"  {i:2d}  {a:7.2f}초 ~ {b:7.2f}초  길이 {b - a:5.2f}초{mark}")
    if not segments:
        sys.exit("말소리 구간을 찾지 못했습니다. --noise 값을 -30이나 -40으로 바꿔 보세요.")
    if args.list:
        print("문장 수와 다르면 --min-silence 를 0.5나 1.0으로, --noise 를 -30이나 -40으로 바꿔 보세요.")
        return 0

    kept = [(a, b) for i, (a, b) in enumerate(segments, start=1) if i not in drop]
    texts = []
    if args.texts:
        texts = [l.strip() for l in pathlib.Path(args.texts).read_text(encoding="utf-8").splitlines() if l.strip()]
        if len(texts) != len(kept):
            sys.exit(f"글은 {len(texts)}줄인데 남긴 조각은 {len(kept)}개입니다. 수를 맞춰 주세요.")

    out = pathlib.Path(args.out) if args.out else PACKS / args.pack
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("s*.ogg")) + list(out.glob("s*.m4a")):
        old.unlink()

    vorbis = has_encoder("libvorbis")
    ext = "ogg" if vorbis else "m4a"
    codec = ["-c:a", "libvorbis", "-q:a", "5"] if vorbis else ["-c:a", "aac", "-b:a", "96k"]
    gain = peak_gain_db(src, -2.0)
    sentences = []
    for n, (a, b) in enumerate(kept, start=1):
        name = f"s{n:02d}.{ext}"
        length = b - a
        fade_out = max(0.0, length - 0.06)
        r = run(["ffmpeg", "-v", "error", "-y", "-ss", f"{a:.3f}", "-to", f"{b:.3f}", "-i", src,
                 "-af", f"volume={gain:.1f}dB,afade=t=in:d=0.03,afade=t=out:st={fade_out:.3f}:d=0.06",
                 "-ar", "44100", "-ac", "1", *codec, str(out / name)])
        if r.returncode != 0:
            sys.exit(f"{name} 을 만들지 못했습니다.\n{r.stderr.strip()}")
        item = {"audio": name, "ms": int(round(total_seconds(str(out / name)) * 1000))}
        if texts:
            item["text"] = texts[n - 1]
        sentences.append(item)

    pack = {
        "id": args.pack,
        "title": args.title,
        "kind": args.kind,
        "language": args.language,
        "order": args.order,
        "description": "직접 녹음한 낭독을 문장별로 나눈 묶음입니다.",
        "voice": "직접 녹음",
        "license": "녹음: 직접 녹음.",
        "sentences": sentences,
    }
    (out / "pack.json").write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    seconds = sum(s["ms"] for s in sentences) / 1000
    print(f"{out} 에 문장 {len(sentences)}개, 전체 {seconds:.1f}초를 넣었습니다. 소리 크기는 {gain:+.1f}dB 조정했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
