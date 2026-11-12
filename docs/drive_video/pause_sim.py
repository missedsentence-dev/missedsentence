"""출근길 이벤트 기록으로 멈춤 횟수와 조용했던 시간을 계산합니다 (규칙 v4).

사용: python3 pause_sim.py 기록.csv [route|basic]
  route: 차량 기본 내비로 길안내 중(경로 모드), basic: 길안내 없음(기본 모드)
CSV 열
- time_s: 이벤트 시각(초). 회전은 회전 지점을 지나는 시각
- event: left, uturn, merge, exit, right, lane, school, slow30, caution, camera, hardbrake, bigsteer
- duration_s: 구역이나 방향지시등이 이어진 시간(초)
- wait_s: 회전 전에 멈춰 서 있던 시간(초)

원칙: 보행자와 만나는 순간(우회전, 어린이보호구역)은 모든 단계에서 항상 같게,
차와 만나는 복잡한 순간(좌회전, 유턴, 합류, 진출)은 미리, 차로 변경은 예산 안에서.
"""
import csv
import sys

SOFT = 2.0      # 문장이 끝나기를 기다리는 평균 시간
MERGE = 30.0
LEVELS = {
    "높음(기획서 표만)": {"lead": 10, "budget": 6, "gap": 60},
    "보통(기본)": {"lead": 10, "budget": 2, "gap": 90},
    "낮음": {"lead": 8, "budget": 0, "gap": 9999},
}
TURN = {"left", "uturn", "merge", "exit"}
SIGNAL_LEAD = 3.0   # 기본 모드에서 방향지시등은 회전 약 3초 전에 켠다고 가정


def plan(ev, dur, wait, lv, mode):
    """[(시작 오프셋, 길이, 중간 끊김)]"""
    if ev in {"hardbrake", "bigsteer"}:
        return [(0.0, dur + 3 + 1, True)]
    if mode == "basic":
        if ev in {"left", "uturn"}:                 # 방향지시등을 저속에서 켜면 회전으로 판정
            if wait > 5:                            # 서 있는 동안 다시 재생, 브레이크를 떼는 순간 끊음
                return [(-wait - SIGNAL_LEAD, 5.0, True), (-3.0, 3 + 3 + 3 + 1, True)]
            return [(-SIGNAL_LEAD, SIGNAL_LEAD + 3 + 3 + 1, True)]
        if ev == "right":                           # 보행자 순간: 기다리는 동안에도 조용
            return [(-SIGNAL_LEAD - wait, SIGNAL_LEAD + wait + 3 + 2 + 1, True)]
        if ev in {"merge", "exit", "lane"}:         # 빠른 속도에서 켠 방향지시등은 차로 변경으로 판정
            return [(0.0, (dur or 5) + 2 + 1, True)]
        return []                                   # 기본 모드는 구역과 주의 지점을 모름
    if ev in TURN:
        lead = lv["lead"]
        if wait > 5:   # 서 있은 지 5초가 지나면 다시 재생하고, 브레이크 페달을 떼는 순간 끊음
            return [(-lead - wait + SOFT, lead - SOFT + 5, False), (-3.0, 3 + 3 + 3 + 1, True)]
        return [(-lead + SOFT, lead - SOFT + 3 + 3 + 1, False)]
    if ev == "right":   # 보행자 순간: 모든 단계 같음. 6초 전, 4초 안에 문장이 안 끝나면 끊음, 대기 중에도 조용
        return [(-6 - wait + SOFT, 6 - SOFT + wait + 3 + 2 + 1, False)]
    if ev == "school":  # 확인된 어린이보호구역: 모든 단계 같음, 구역을 벗어나면 재생
        return [(SOFT, dur - SOFT + 1, False)]
    if ev == "slow30":
        return [(SOFT, min(dur, 20) - SOFT + 1, False)]
    if ev == "lane":
        return [(0.0, dur + 2 + 1, True)]
    if ev == "caution":
        return [(SOFT, 3 + 3 + 1 - SOFT, False)]
    return []


def budgeted(ev, mode):
    if mode == "basic":
        return ev in {"lane", "merge", "exit"}
    return ev in {"lane", "caution", "slow30"}


BUDGET_WINDOW = 1800.0   # 예산을 세는 구간(초). 앱 엔진의 BUDGET_WINDOW_MS와 같게 둡니다.


def budget_ok(used_times, t, lv):
    """앱 엔진(LoadEngine.laneBudgetOk)과 같은 방식: 지난 30분 안의 멈춤 수가 예산보다 적고, 마지막 멈춤에서 간격이 지났을 때만."""
    while used_times and t - used_times[0] > BUDGET_WINDOW:
        used_times.pop(0)
    if len(used_times) >= lv["budget"]:
        return False
    return not used_times or t - used_times[-1] >= lv["gap"]


def run(rows, lv, mode):
    total = max(r["t"] + r["dur"] for r in rows)
    used_times, last_end = [], -1e9
    pauses, cuts, silence, relisten = 0, 0, 0.0, 0
    for r in sorted(rows, key=lambda x: x["t"]):
        ev = r["ev"]
        if ev == "camera":
            continue
        if budgeted(ev, mode):
            if not budget_ok(used_times, r["t"], lv):
                relisten += 0 if ev == "slow30" else 1
                continue
            used_times.append(r["t"])
        for off, length, cut in plan(ev, r["dur"], r["wait"], lv, mode):
            start = r["t"] + off
            if pauses and start - last_end <= MERGE and start >= last_end - length:
                silence += max(0.0, start + length - max(start, last_end))
                last_end = max(last_end, start + length)
                continue
            pauses += 1
            cuts += 1 if cut else 0
            silence += length
            last_end = start + length
    return pauses, cuts, silence, total, relisten


def main(path, mode):
    with open(path, encoding="utf-8") as fh:
        rows = [{"t": float(r["time_s"]), "ev": r["event"].strip(), "dur": float(r.get("duration_s") or 0),
                 "wait": float(r.get("wait_s") or 0)} for r in csv.DictReader(fh)]
    print(f"[{path}] {'경로 모드' if mode == 'route' else '기본 모드'}")
    print("단계 | 멈춤 전체 | 문장 사이 미룸 | 중간 끊김 | 조용했던 시간 | 비율 | 다시 들을 문장")
    for name, lv in LEVELS.items():
        p, c, s, t, rl = run(rows, lv, mode)
        print(f"{name} | {p} | {p - c} | {c} | {s:.0f}초 | {s / t * 100:.1f}% | {rl}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sample_commute.csv", sys.argv[2] if len(sys.argv) > 2 else "route")
