#!/usr/bin/env python3
"""영어 문장 묶음의 소리 파일과 pack.json을 만듭니다.

문장은 tools/content/english_commute.txt 에 한 줄에 하나씩 직접 쓴 것입니다.
소리는 Piper(MIT 라이선스)와 LJ Speech 데이터(퍼블릭 도메인)로 학습한 음성 en_US-ljspeech-medium 으로 합성합니다.

준비물
  piper 실행 파일: https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz
  음성 모델:      https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2
  ffmpeg

쓰는 법
  python3 tools/build_english_pack.py --piper /path/piper/piper \
      --model /path/vits-piper-en_US-ljspeech-medium/en_US-ljspeech-medium.onnx
"""
import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEXT = ROOT / "tools" / "content" / "english_commute.txt"
OUT = ROOT / "app" / "src" / "main" / "assets" / "packs" / "english_commute"


def duration_ms(path: pathlib.Path) -> int:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return int(round(float(out) * 1000))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--piper", required=True, help="piper 실행 파일")
    ap.add_argument("--model", required=True, help="en_US-ljspeech-medium.onnx")
    args = ap.parse_args()

    lines = [l.strip() for l in TEXT.read_text(encoding="utf-8").splitlines() if l.strip()]
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("s*.ogg"):
        old.unlink()

    sentences = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, text in enumerate(lines, start=1):
            wav = pathlib.Path(tmp) / f"s{i:02d}.wav"
            subprocess.run(
                [args.piper, "--model", args.model, "--output_file", str(wav)],
                input=text, text=True, check=True, capture_output=True,
            )
            ogg = OUT / f"s{i:02d}.ogg"
            # 앞뒤 무음을 0.12초만 남기고, 44.1kHz 모노 Vorbis로 바꿉니다.
            subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-i", str(wav),
                 "-af", "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.12,"
                        "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.12,areverse",
                 "-ar", "44100", "-ac", "1", "-c:a", "libvorbis", "-q:a", "4", str(ogg)],
                check=True,
            )
            sentences.append({"text": text, "audio": ogg.name, "ms": duration_ms(ogg)})
            print(f"{ogg.name} {sentences[-1]['ms']}ms  {text}")

    pack = {
        "id": "english_commute",
        "title": "출근길 영어 문장",
        "kind": "language",
        "language": "en",
        "order": 10,
        "description": "출근길과 사무실에서 쓰는 짧은 영어 문장. 이 앱을 위해 직접 쓴 문장입니다.",
        "voice": "Piper en_US-ljspeech-medium 합성 음성",
        "license": "문장: 직접 작성. 소리: Piper(MIT)와 LJ Speech 데이터(퍼블릭 도메인)로 학습한 음성으로 합성.",
        "sentences": sentences,
    }
    (OUT / "pack.json").write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total = sum(s["ms"] for s in sentences) / 1000
    print(f"문장 {len(sentences)}개, 전체 {total:.1f}초")
    return 0


if __name__ == "__main__":
    sys.exit(main())
