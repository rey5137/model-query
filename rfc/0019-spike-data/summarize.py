#!/usr/bin/env python3
"""Reducer for the RFC 0019 acceptance spike (standard library only).

Reads, per Hibernate line, performance-<line>[-suffix].tsv (JMH timings) and rfc0019-<line>.tsv (functional cells)
from a directory, and prints a Markdown summary: min/median/max per route, the one-sided 99.9% Welch bounds the RFC's
rules compare, and which Part A rule and Part B steps hold.

Usage: python3 summarize.py [data-dir]
"""

import csv
import glob
import math
import os
import statistics
import sys
from collections import defaultdict

CONFIDENCE = 0.999
READS = ("today", "one", "page", "grouped", "orderedBy")
# Routes that could ship, with the saving S = O(RA.1) - O(route); the baseline cancels.
CANDIDATES = {"RA.3_copy": "build cache", "RA.3_nocopy": "build cache (no copy)", "RA.5": "provider"}


# ---------------------------------------------------------------- statistics

def _betacf(a, b, x):
    """Continued fraction for the regularized incomplete beta (Numerical Recipes 6.4)."""
    tiny = 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = 1.0 / (d if abs(d) > tiny else tiny)
    h = d
    for m in range(1, 300):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > tiny else tiny)
        c = 1.0 + aa / c
        c = c if abs(c) > tiny else tiny
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = 1.0 / (d if abs(d) > tiny else tiny)
        c = 1.0 + aa / c
        c = c if abs(c) > tiny else tiny
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < 1e-14:
            break
    return h


def _betai(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    ln = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1.0 - x)
    front = math.exp(ln)
    if x < (a + 1.0) / (a + b + 2.0):
        return front * _betacf(a, b, x) / a
    return 1.0 - front * _betacf(b, a, 1.0 - x) / b


def t_cdf(t, df):
    p = 0.5 * _betai(df / 2.0, 0.5, df / (df + t * t))
    return 1.0 - p if t >= 0 else p


def t_quantile(p, df):
    """Inverse Student t CDF by bisection; p in (0.5, 1)."""
    lo, hi = 0.0, 1000.0
    for _ in range(200):
        mid = (lo + hi) / 2.0
        if t_cdf(mid, df) < p:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2.0


def welch(a, b, confidence=CONFIDENCE):
    """mean(a) - mean(b) with its one-sided (lower, upper) bounds at `confidence`."""
    na, nb = len(a), len(b)
    va, vb = statistics.variance(a) / na, statistics.variance(b) / nb
    diff = statistics.fmean(a) - statistics.fmean(b)
    se = math.sqrt(va + vb)
    if se == 0.0:
        return diff, diff, diff
    df = (va + vb) ** 2 / (va * va / (na - 1) + vb * vb / (nb - 1))
    t = t_quantile(confidence, df)
    return diff, diff - t * se, diff + t * se


def one_sample_upper(a, confidence=CONFIDENCE):
    n = len(a)
    return statistics.fmean(a) + t_quantile(confidence, n - 1) * statistics.stdev(a) / math.sqrt(n)


# ---------------------------------------------------------------- loading

def load_timings(data_dir, line):
    """{(part, query, route): {"ms": [...], "alloc": [...]}}, plus the set of cells written as FAIL.

    A later file (a -suffix rerun) fills cells the main file is missing or failed; it never replaces passing ones.
    """
    cells, failed = {}, set()
    paths = sorted(glob.glob(os.path.join(data_dir, f"performance-{line}*.tsv")), key=len)
    for path in paths:
        found = defaultdict(lambda: {"ms": [], "alloc": []})
        bad = set()
        with open(path, newline="") as f:
            for r in csv.DictReader(f, delimiter="\t"):
                key = (r["part"], r["query"], r["route"])
                if r["vendor"] != "POSTGRESQL":
                    key = (r["vendor"],) + key
                if r["status"] != "PASS":
                    bad.add(key)
                    continue
                found[key]["ms"].append(float(r["score_ms"]))
                found[key]["alloc"].append(float(r["alloc_rate_norm"]))
        for key, v in found.items():
            if key not in cells:
                cells[key] = v
                failed.discard(key)
        failed |= {k for k in bad if k not in cells}
    return cells, failed


def load_functional(data_dir, line):
    rows = []
    path = os.path.join(data_dir, f"rfc0019-{line}.tsv")
    if os.path.exists(path):
        with open(path, newline="") as f:
            rows = [r for r in csv.DictReader(f, delimiter="\t") if r["vendor"] == "POSTGRESQL"]
    return rows


def functional_status(rows, probe, case_prefix=""):
    hits = [r["status"] for r in rows if r["probe"] == probe and r["case"].startswith(case_prefix)]
    if not hits:
        return "MISSING"
    return "PASS" if all(s == "PASS" for s in hits) else ("FAIL" if "FAIL" in hits else hits[0])


# ---------------------------------------------------------------- rules

def part_a(cells, functional, line):
    """Every number Part A's rules read, for one line."""
    ms = lambda q, r: cells.get(("A", q, r), {}).get("ms")
    out = {"line": line}
    base = ms("today", "RA.0")
    out["T"] = max(0.3, 0.1 * statistics.fmean(base)) if base else None
    out["O"] = {r: welch(ms("today", r), base) for r in ("RA.1", "RA.4", "RA.4b")
                if base and ms("today", r)}
    ra2 = ms("today", "RA.2")
    out["B_upper"] = one_sample_upper(ra2) if ra2 else None
    out["savings"] = {}
    for route in CANDIDATES:
        per_read = {}
        for q in READS:
            if ms(q, "RA.1") and ms(q, route):
                per_read[q] = welch(ms(q, "RA.1"), ms(q, route))
        if per_read:
            out["savings"][route] = per_read
    if ms("today", "RA.3_copy") and ms("today", "RA.5"):
        out["Sp_minus_Sb"] = welch(ms("today", "RA.3_copy"), ms("today", "RA.5"))
    out["plan_cache_74"] = functional_status(functional, "A.4/stats")
    out["ra5_hits"] = functional_status(functional, "A.5")
    out["cache_functional"] = all(functional_status(functional, p) in ("PASS", "MISSING")
                                  for p in ("A.3", "A.3/hits", "A.3/oneKey"))
    g = cells.get(("gating", "gating", "gating_cache"), {}).get("ms")
    f = cells.get(("gating", "gating", "gating_fresh"), {}).get("ms")
    out["gating"] = welch(g, f) if g and f else None
    return out


def ships(saving_per_read):
    """The ship condition: a saving whose lower bound is at least 0 on every read cell."""
    return all(lower >= 0 for _, lower, _ in saving_per_read.values()) and len(saving_per_read) == len(READS)


def decide_part_a(a66, a74):
    """Applies rules 1-5 in order over both lines; returns (rule number, name, reasons)."""
    reasons = []
    o4 = a74["O"].get("RA.4")
    o1 = a66["O"].get("RA.1")
    r1 = (o4 is not None and o4[2] <= a74["T"] and a74["plan_cache_74"] == "PASS"
          and o1 is not None and o1[2] <= a66["T"])
    reasons.append(f"rule 1: O(RA.4) upper {fmt(o4 and o4[2])} vs T {fmt(a74['T'])} on 7.4, plan cache "
                   f"{a74['plan_cache_74']}; O(RA.1) upper {fmt(o1 and o1[2])} vs T {fmt(a66['T'])} on 6.6")
    if r1:
        return 1, "Drop", reasons

    eligible = {}
    for a in (a66, a74):
        for route, per_read in a["savings"].items():
            best = max(lower for _, lower, _ in per_read.values())
            ok = ships(per_read)
            reasons.append(f"{a['line']} {route}: best lower-bound saving {fmt(best)} ms, "
                           f"ship condition {'holds' if ok else 'fails'} (min lower {fmt(min(l for _, l, _ in per_read.values()))})")
            eligible.setdefault(route, []).append(ok and best >= 0.2)
    if not any(all(v) for v in eligible.values()):
        return 2, "Neither", reasons

    b_ok = all(a["B_upper"] is not None and a["B_upper"] < 0.2 for a in (a66, a74))
    ra5_ok = all(a["ra5_hits"] == "PASS" for a in (a66, a74))
    ra5_ships = all(ships(a["savings"].get("RA.5", {})) for a in (a66, a74))
    if b_ok and ra5_ok and ra5_ships:
        return 3, "Provider only", reasons
    cache_ok = all(a["cache_functional"] and a["gating"] and a["gating"][2] < 0 for a in (a66, a74))
    threshold = lambda a: max(0.1, 0.1 * a["O"]["RA.1"][0])
    if cache_ok and ra5_ok and all(a.get("Sp_minus_Sb") and a["Sp_minus_Sb"][1] >= threshold(a) for a in (a66, a74)):
        return 4, "Both", reasons
    if cache_ok:
        return 5, "Build only", reasons
    return 2, "Neither", reasons


def part_b(cells):
    med = lambda r: statistics.median(cells[("B", "flat86k", r)]["ms"]) if ("B", "flat86k", r) in cells else None
    rb = {r: med(r) for r in ("RB.0", "RB.1", "RB.2", "RB.3", "RB.4", "RB.5", "RB.6")}
    out = {"medians": rb}
    if None in (rb["RB.0"], rb["RB.2"], rb["RB.4"]):
        return out
    out["gate"] = (rb["RB.4"] - rb["RB.2"]) / (rb["RB.4"] - rb["RB.0"])
    faster = lambda new, old: (old - new) / old
    if rb["RB.5"] is not None:
        out["P-B.1"] = faster(rb["RB.5"], rb["RB.4"])
    base6 = rb["RB.5"] if out.get("P-B.1", 0) >= 0.10 else rb["RB.4"]
    if rb["RB.6"] is not None:
        out["P-B.2"] = faster(rb["RB.6"], base6)
    if rb["RB.3"] is not None:
        out["P-B.3"] = faster(rb["RB.3"], rb["RB.2"])
    return out


# ---------------------------------------------------------------- report

def fmt(x, digits=3):
    return "n/a" if x is None else f"{x:.{digits}f}"


def report(data_dir):
    lines_found = sorted({os.path.basename(p)[len("performance-"):].split(".tsv")[0].split("-")[0]
                          for p in glob.glob(os.path.join(data_dir, "performance-*.tsv"))})
    out = ["# RFC 0019 spike summary", ""]
    loaded = {}
    for line in lines_found:
        cells, failed = load_timings(data_dir, line)
        loaded[line] = (cells, failed, load_functional(data_dir, line))
        out += [f"## {line}", "", "| part | query | route | n | min | median | max | ratio to baseline |",
                "|---|---|---|---|---|---|---|---|"]
        for key in sorted(cells):
            v = cells[key]["ms"]
            base_key = key[:-1] + (("RB.0",) if key[-3] == "B" else ("RA.0",))
            base = cells.get(base_key, {}).get("ms")
            ratio = statistics.median(v) / statistics.median(base) if base else None
            out.append(f"| {' | '.join(key)} | {len(v)} | {fmt(min(v))} | {fmt(statistics.median(v))} "
                       f"| {fmt(max(v))} | {fmt(ratio, 2)} |")
        for key in sorted(failed):
            out.append(f"| {' | '.join(key)} | 0 | FAIL | FAIL | FAIL | n/a |")
        out.append("")

    a = {line: part_a(c, fn, line) for line, (c, _, fn) in loaded.items()}
    l66 = next((l for l in a if l.startswith("6.")), None)
    l74 = next((l for l in a if l.startswith("7.")), None)
    if l66 and l74:
        rule, name, reasons = decide_part_a(a[l66], a[l74])
        out += ["## Part A", "", f"**Rule {rule}: {name}.**", ""] + [f"- {r}" for r in reasons] + [""]
    out += ["## Part B", ""]
    for line, (c, _, _) in loaded.items():
        b = part_b(c)
        steps = ", ".join(f"{k} {100 * b[k]:.1f}%" for k in ("gate", "P-B.1", "P-B.2", "P-B.3") if k in b)
        out.append(f"- {line}: {steps} (need gate >= 25%, P-B.1 >= 10%, P-B.2 >= 5%, P-B.3 >= 15%)")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    sys.stdout.write(report(sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))))
