#!/usr/bin/env python3
"""Regenerates the RFC 0017 spike matrix and line parity from the raw per-line TSVs (stdlib only).

Usage: python3 -I summarize.py [dir]   (reads rfc0017-<version>.tsv or .tsv.gz in dir, default: this script's dir)
Prints the probe x vendor x line matrix as Markdown (PASS / FAIL / NOT_RUN, plus SIMULATED where any) and writes
line-parity.tsv next to the inputs. Line parity compares each cell's status only, never its value or SQL: a cell
that passes on both lines with different SQL or binds (MySQL's array columns) is not listed.
"""
import csv
import gzip
import os
import sys

VENDORS = ["H2", "POSTGRESQL", "MYSQL"]
SHORT = {"H2": "H2", "POSTGRESQL": "PG", "MYSQL": "MySQL"}
PROBES = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "FT"]


def load(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", newline="") as f:
        rows = list(csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE))
    return rows[1:]


def main():
    here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    runs = {}
    for name in sorted(os.listdir(here)):
        if name.startswith("rfc0017-") and (name.endswith(".tsv") or name.endswith(".tsv.gz")):
            version = name[len("rfc0017-"):].split(".tsv")[0]
            runs[version] = load(os.path.join(here, name))
    lines = sorted(runs)
    cols = [(v, line) for v in VENDORS for line in lines]
    print("| Probe | " + " | ".join(f"{SHORT[v]} {line.split('.')[0]}.{line.split('.')[1]}" for v, line in cols) + " |")
    print("|---" * (len(cols) + 1) + "|")
    for probe in PROBES:
        cells = []
        for v, line in cols:
            n = {"PASS": 0, "FAIL": 0, "NOT_RUN": 0, "SIMULATED": 0}
            for row in runs[line]:
                if row[0] == v and row[2] == probe:
                    n[row[4]] += 1
            sim = f" / {n['SIMULATED']} sim" if n["SIMULATED"] else ""
            cells.append(f"{n['PASS']} / {n['FAIL']} / {n['NOT_RUN']}{sim}")
        print(f"| {probe} | " + " | ".join(cells) + " |")
    if len(lines) == 2:
        status = [{(r[0], r[2], r[3]): r[4] for r in runs[line]} for line in lines]
        keys = sorted(set(status[0]) | set(status[1]))
        with open(os.path.join(here, "line-parity.tsv"), "w", newline="") as out:
            w = csv.writer(out, delimiter="\t", lineterminator="\n")
            w.writerow(["vendor", "probe", "case", lines[0], lines[1]])
            changed = 0
            for k in keys:
                a, b = status[0].get(k, "absent"), status[1].get(k, "absent")
                if a != b:
                    w.writerow([*k, a, b])
                    changed += 1
        print(f"\n{changed} cells differ between {lines[0]} and {lines[1]} (line-parity.tsv)")
    for line in lines:
        print(f"{line}: {len(runs[line])} cells")


if __name__ == "__main__":
    main()
