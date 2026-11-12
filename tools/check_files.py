#!/usr/bin/env python3
"""XML 문법, pack.json 문법과 소리 파일 유무, 제출 글 규칙(가운뎃점 없음)을 확인합니다."""
import json
import pathlib
import sys
import xml.dom.minidom

ROOT = pathlib.Path(__file__).resolve().parent.parent
bad = 0


def fail(msg):
    global bad
    bad += 1
    print("  실패:", msg)


xmls = [p for p in ROOT.rglob("*.xml") if "build" not in p.parts]
for p in xmls:
    try:
        xml.dom.minidom.parse(str(p))
    except Exception as e:  # noqa: BLE001
        fail(f"{p.relative_to(ROOT)}: {e}")
print(f"XML {len(xmls)}개 문법 확인")

packs = sorted((ROOT / "app/src/main/assets/packs").glob("*/pack.json"))
for p in packs:
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        fail(f"{p.relative_to(ROOT)}: {e}")
        continue
    files = {f.name for f in p.parent.iterdir()}
    n_audio = 0
    for i, s in enumerate(d.get("sentences", []), start=1):
        audio = s.get("audio")
        if audio:
            if audio in files:
                n_audio += 1
            else:
                fail(f"{p.parent.name}: {i}번째 문장의 소리 파일 {audio} 이 없음")
        elif not s.get("text"):
            fail(f"{p.parent.name}: {i}번째 문장에 글도 소리도 없음")
    for key in ("id", "title", "kind", "language"):
        if not d.get(key):
            fail(f"{p.parent.name}: {key} 없음")
    if d.get("id") != p.parent.name:
        fail(f"{p.parent.name}: id가 폴더 이름과 다름")
    print(f"묶음 {d.get('id')}: 문장 {len(d.get('sentences', []))}개, 소리 파일 {n_audio}개, 종류 {d.get('kind')}")

dot = chr(0xB7)
texts = [p for p in ROOT.rglob("*") if p.is_file() and p.suffix in {".kt", ".md", ".xml", ".json", ".py", ".kts", ".txt", ".sh"}
         and "build" not in p.parts and ".gradle" not in p.parts]
for p in texts:
    if dot in p.read_text(encoding="utf-8", errors="ignore"):
        fail(f"{p.relative_to(ROOT)}: 가운뎃점이 들어 있음")
print(f"글 파일 {len(texts)}개에서 가운뎃점 확인")

print("파일 점검 실패 0" if bad == 0 else f"파일 점검 실패 {bad}")
sys.exit(1 if bad else 0)
