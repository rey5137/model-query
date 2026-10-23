#!/usr/bin/env python3
"""Reduces the RFC 0020 spike's raw data to the tables in ../0020-spike-results.md (standard library only).

Usage, from this directory: python3 -I summarize.py [matrix|timings|parity]   (default: all three)

- matrix:  PASS/FAIL/NOT_RUN per probe prefix (the part before the first '/') x vendor x line, from rfc0020-*.tsv.
- timings: median ms per timed cell x case x index, one column per vendor and line, from rfc0020-timings-*.tsv,
           with the plan digest's index/cte flags (PostgreSQL and MySQL).
- parity:  functional cells whose status differs between the two lines.
"""
import csv
import gzip
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
LINES = ["6.6.58.Final", "7.4.11.Final"]
VENDORS = ["H2", "POSTGRESQL", "MYSQL"]
csv.field_size_limit(sys.maxsize)


def rows(name):
    for suffix, opener in (("", open), (".gz", gzip.open)):
        path = HERE / (name + suffix)
        if path.exists():
            with opener(path, "rt", newline="") as f:
                yield from csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
            return


def matrix():
    counts = defaultdict(lambda: defaultdict(lambda: [0, 0, 0]))
    for line in LINES:
        for r in rows(f"rfc0020-{line}.tsv"):
            group = r["probe"].split("/")[0]
            idx = {"PASS": 0, "FAIL": 1, "NOT_RUN": 2}[r["status"]]
            counts[group][(r["vendor"], line)][idx] += 1
            counts["total"][(r["vendor"], line)][idx] += 1
    cols = [(v, l) for v in VENDORS for l in LINES]
    print("| Probe | " + " | ".join(f"{v} {l[:3]}" for v, l in cols) + " |")
    print("|---" * (len(cols) + 1) + "|")
    for group in sorted(counts, key=lambda g: (g == "total", g)):
        cells = ["/".join(map(str, counts[group][c])) for c in cols]
        print(f"| {group} | " + " | ".join(cells) + " |")


def flags(plan):
    out = [p.strip() for p in plan.split(";") if p.strip().startswith(("index", "cte=", "top buffers="))]
    return ", ".join(out)


def timings():
    table = defaultdict(dict)
    digests = defaultdict(dict)
    for line in LINES:
        for r in rows(f"rfc0020-timings-{line}.tsv"):
            key = (r["cell"], r["index"], r["case"])
            col = (r["vendor"], line)
            table[key][col] = r["median_ms"] if r["status"] == "PASS" else r["status"]
            if r["status"] == "PASS" and r["vendor"] != "H2":
                digests[key][col] = flags(r["plan"])
    cols = [(v, l) for v in VENDORS for l in LINES]
    print("| Cell | Index | Case | " + " | ".join(f"{v} {l[:3]}" for v, l in cols) + " |")
    print("|---" * (len(cols) + 3) + "|")
    for key in table:
        cells = [table[key].get(c, "") for c in cols]
        print(f"| {key[0]} | {key[1]} | {key[2]} | " + " | ".join(cells) + " |")
    print()
    for key in digests:
        for col, digest in digests[key].items():
            if digest:
                print(f"- {key[0]} / {key[2]} / {key[1]} / {col[0]} {col[1][:3]}: {digest}")


def parity():
    status = defaultdict(dict)
    for line in LINES:
        for r in rows(f"rfc0020-{line}.tsv"):
            status[(r["vendor"], r["probe"], r["case"])][line] = r["status"]
    diffs = [(k, v) for k, v in status.items() if len(set(v.values())) > 1 or len(v) != len(LINES)]
    print(f"cells: {len(status)}, status differs or missing on a line: {len(diffs)}")
    for k, v in diffs:
        print(f"- {' | '.join(k)}: {v}")


if __name__ == "__main__":
    what = sys.argv[1:] or ["matrix", "timings", "parity"]
    for w in what:
        print(f"## {w}\n")
        globals()[w]()
        print()
