#!/usr/bin/env python3
"""Reduces the RFC 0021 spike's raw data to the tables in ../0021-spike-results.md (standard library only).

Usage, from this directory: python3 -I summarize.py [matrix|timings|parity|fails]   (default: matrix timings parity)

- matrix:  PASS/FAIL/NOT_RUN per probe prefix (the part before the first '/') x vendor x line, from rfc0021-*.tsv.
- timings: median ms per timed cell x case x index, one column per vendor and line, from rfc0021-timings-*.tsv,
           with the plan digest's profile-scan and cte flags (PostgreSQL and MySQL).
- parity:  functional cells whose status, value or statement text differs between the two lines. A statement is
           compared with whitespace collapsed and Hibernate's numbered aliases kept, so an alias change shows.
- fails:   every cell that is not PASS, with its value.
"""
import csv
import gzip
import re
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
        for r in rows(f"rfc0021-{line}.tsv"):
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
    out = [p.strip() for p in plan.split(";")
           if p.strip().startswith(("profile scans", "profile_type scans", "cte=", "exec="))]
    return ", ".join(out)


def timings():
    table = defaultdict(dict)
    digests = defaultdict(dict)
    for line in LINES:
        for r in rows(f"rfc0021-timings-{line}.tsv"):
            key = (r["cell"], r["index"], r["case"])
            col = (r["vendor"], line)
            table[key][col] = r["median_ms"] if r["status"] == "PASS" else r["status"]
            if r["status"] == "PASS" and r["vendor"] != "H2":
                digests[key][col] = flags(r["plan"])
    cols = [(v, l) for v in VENDORS for l in LINES]
    print("| Cell | Scope | Case | " + " | ".join(f"{v} {l[:3]}" for v, l in cols) + " |")
    print("|---" * (len(cols) + 3) + "|")
    for key in table:
        cells = [table[key].get(c, "") for c in cols]
        print(f"| {key[0]} | {key[1]} | {key[2]} | " + " | ".join(cells) + " |")
    print()
    for key in digests:
        for col, digest in digests[key].items():
            if digest:
                print(f"- {key[0]} / {key[2]} / {key[1]} / {col[0]} {col[1][:3]}: {digest}")


def squash(s):
    """Whitespace collapsed and the joined source's alias (cte01_0 on 6.6, var_1_11_0 on 7.4) made one name."""
    s = re.sub(r"\b(?:cte0\d_0|var_\d+_\d+_0)\b", "<src>", s)
    return re.sub(r"\s+", " ", s).strip()


def parity():
    cells = defaultdict(dict)
    for line in LINES:
        for r in rows(f"rfc0021-{line}.tsv"):
            cells[(r["vendor"], r["probe"], r["case"])][line] = r
    status = value = sql = missing = 0
    out = []
    for k, v in cells.items():
        if len(v) != len(LINES):
            missing += 1
            out.append(f"- {' | '.join(k)}: only on {list(v)}")
            continue
        a, b = (v[line] for line in LINES)
        if a["status"] != b["status"]:
            status += 1
            out.append(f"- STATUS {' | '.join(k)}: {a['status']} -> {b['status']}")
        elif squash(a["value"]) != squash(b["value"]):
            value += 1
            out.append(f"- VALUE {' | '.join(k)}:\n    6.6: {squash(a['value'])[:300]}\n    7.4: {squash(b['value'])[:300]}")
        elif squash(a["sql"]) != squash(b["sql"]):
            sql += 1
            out.append(f"- SQL {' | '.join(k)}:\n    6.6: {squash(a['sql'])[:400]}\n    7.4: {squash(b['sql'])[:400]}")
    print(f"cells: {len(cells)}, status differs: {status}, value differs: {value}, statement differs: {sql}, "
          f"missing on a line: {missing}")
    print("\n".join(out))


def fails():
    for line in LINES:
        for r in rows(f"rfc0021-{line}.tsv"):
            if r["status"] != "PASS":
                print(f"- {line[:3]} {r['vendor']} {r['probe']} | {r['case']}: {r['status']} {r['value'][:300]}")


if __name__ == "__main__":
    what = sys.argv[1:] or ["matrix", "timings", "parity"]
    for w in what:
        print(f"## {w}\n")
        globals()[w]()
        print()
