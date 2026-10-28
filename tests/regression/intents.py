#!/usr/bin/env python3
"""Compares the player commands the hooks saw (PlayerIntents) with what the cast sent to the
renderer from the phone's playback state (the heuristic), per scenario.

Usage:
  tests/regression/intents.py reports/<run>            # all apps of a run
  tests/regression/intents.py reports/<run>/<app>/logcat.txt

For each command the heuristic forwarded (play, pause, seek), a matching hooked command of the
same kind within a few seconds counts as agreement. The rest:
  heuristic only  - sent to the TV without a command to the player: a ghost (or a missing hook)
  intent only     - a command the hooks would forward that the heuristic did not send
  unknown         - hooked commands the prototype can not place yet (learning material)
"""
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

MATCH_S = 3.0
LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+\d+\s+\d+\s+\w\s+(\S+)\s*: (.*)$")
INTENT = re.compile(r"Intent (play|pause|seek)(?: (-?\d+) ms)?(?: source=(\S+))?(?: reason=(\d+))? by (\S+)"
                    r"(?: \((\S+) in front\))? (?:director|player)@\S+ -> (.*)$")
HEURISTIC = [
    ("seek", re.compile(r"Local seek to (\d+) ms")),
    ("pause", re.compile(r"Local pause, mirrored to the renderer")),
    ("play", re.compile(r"Local play, sent to the renderer")),
]


def parse(path: Path):
    scenarios = []
    current = {"name": "(start)", "intents": [], "heuristic": []}
    for raw in path.read_text(errors="replace").splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        stamp, tag, text = m.groups()
        t = datetime.strptime("2000-" + stamp, "%Y-%m-%d %H:%M:%S.%f").timestamp()
        if tag == "YtDlnaTest" and text.startswith("SCENARIO "):
            scenarios.append(current)
            current = {"name": text.split(" ", 1)[1].strip("'"), "intents": [], "heuristic": []}
            continue
        if tag != "YtDlna":
            continue
        im = INTENT.search(text)
        if im:
            kind, pos, source, reason, caller, side, decision = im.groups()
            current["intents"].append({"t": t, "kind": kind, "pos": pos, "source": source, "reason": reason,
                                       "caller": caller, "side": side, "decision": decision, "text": text})
            continue
        for kind, pattern in HEURISTIC:
            if pattern.search(text):
                current["heuristic"].append({"t": t, "kind": kind, "text": text})
    scenarios.append(current)
    return [s for s in scenarios if s["intents"] or s["heuristic"]]


def compare(scenario):
    forwarded = [i for i in scenario["intents"] if i["decision"].startswith("forward")]
    used = set()
    agree, heuristic_only = [], []
    for h in scenario["heuristic"]:
        match = next((n for n, i in enumerate(forwarded) if n not in used and i["kind"] == h["kind"]
                      and abs(i["t"] - h["t"]) <= MATCH_S), None)
        if match is None:
            heuristic_only.append(h)
        else:
            used.add(match)
            agree.append(h)
    intent_only = [i for n, i in enumerate(forwarded) if n not in used]
    unknown = [i for i in scenario["intents"] if i["decision"] == "unknown"]
    return agree, heuristic_only, intent_only, unknown


def report(path: Path):
    print(f"\n# {path.parent.name}")
    totals = defaultdict(int)
    for s in parse(path):
        agree, h_only, i_only, unknown = compare(s)
        totals["agree"] += len(agree)
        totals["heuristic only"] += len(h_only)
        totals["intent only"] += len(i_only)
        totals["unknown"] += len(unknown)
        totals["ignored"] += sum(1 for i in s["intents"] if i["decision"].startswith("ignore"))
        print(f"\n## {s['name']}: {len(agree)} agree, {len(h_only)} heuristic only, "
              f"{len(i_only)} intent only, {len(unknown)} unknown")
        for i in s["intents"]:
            what = f"{i['kind']} {i['pos'] + ' ms ' if i['pos'] else ''}{i['source'] or ''}" \
                   f"{' reason=' + i['reason'] if i['reason'] else ''}".strip()
            print(f"  intent    {what:<40} by {i['caller']:<28} -> {i['decision']}")
        for h in h_only:
            print(f"  HEURISTIC ONLY: {h['text']}")
        for i in i_only:
            print(f"  INTENT ONLY: {i['text']}")
    print("\nTotal: " + ", ".join(f"{k} {v}" for k, v in totals.items()))


def main():
    for arg in sys.argv[1:]:
        p = Path(arg)
        logs = [p] if p.is_file() else sorted(p.glob("*/logcat.txt"))
        for log in logs:
            report(log)


if __name__ == "__main__":
    main()
