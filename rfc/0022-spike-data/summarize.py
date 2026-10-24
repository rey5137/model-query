#!/usr/bin/env python3
"""RFC 0022 tables, using only the standard library. Timings and parity counts are info only."""
import csv
import re
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
LINES = ("6.6.58.Final", "7.4.11.Final")
VENDORS = ("H2", "MYSQL", "POSTGRESQL")
csv.field_size_limit(sys.maxsize)


def read(path):
    with path.open(newline="") as source:
        return list(csv.DictReader(source, delimiter="\t", quoting=csv.QUOTE_NONE))


def key(row):
    return row["vendor"], row["probe"], row["case"]


def unique(rows):
    result = {}
    for row in rows:
        if key(row) in result:
            raise ValueError("duplicate acceptance key: " + str(key(row)))
        if row["status"] not in ("PASS", "FAIL", "NOT_RUN"):
            raise ValueError("unknown acceptance status: " + row["status"])
        result[key(row)] = row
    return result


def merge_costs(current, saved):
    """Use accepted sub-step 2 measurements only in place of an explicit iteration skip, never a failure."""
    result = unique(current)
    costs = unique(saved)
    for vendor in VENDORS:
        old = [row for row in costs.values() if row["vendor"] == vendor]
        if {row["case"] for row in old} != {"CONTENT", "COUNT", "STREAM"}:
            raise ValueError("missing saved B.2 cells for " + vendor)
        if any(row["probe"] != "B.2" or row["status"] != "PASS" for row in old):
            raise ValueError("invalid saved B.2 measurements for " + vendor)
        new = [row for row in result.values() if row["vendor"] == vendor and row["probe"] == "B.2"]
        if len(new) == 1 and new[0]["status"] == "NOT_RUN" and "-Drfc0022.plans=false" in new[0]["value"]:
            del result[key(new[0])]
            result.update((key(row), row) for row in old)
    return list(result.values())


def functional(line):
    return merge_costs(read(HERE / ("rfc0022-" + line + ".tsv")),
                       read(HERE / ("cost-cells-" + line + ".tsv")))


def matrix():
    print("| Lane | Vendor | Probe | PASS | FAIL | NOT_RUN |")
    print("| --- | --- | --- | ---: | ---: | ---: |")
    lanes = [(line, functional(line)) for line in LINES]
    lanes.append(("EclipseLink 5.0.2", read(HERE / "rfc0022-eclipselink-5.0.2.tsv")))
    for path in sorted(HERE.glob("link-probe-*.tsv")):
        lanes.append((path.stem.removeprefix("link-probe-"), read(path)))
    for lane, rows in lanes:
        counts = Counter((row.get("vendor", "offline"), row["probe"], row["status"]) for row in rows)
        for vendor, probe in sorted({(vendor, probe) for vendor, probe, status in counts}):
            values = [counts[vendor, probe, status] for status in ("PASS", "FAIL", "NOT_RUN")]
            print("| " + " | ".join(map(str, (lane, vendor, probe, *values))) + " |")


def grid_rows(lanes):
    """PASS/FAIL/NOT_RUN per probe (rows) and vendor/lane (columns), plus a total row."""
    columns = [(vendor, lane) for lane, _ in lanes for vendor in VENDORS]
    counts = Counter((row["vendor"], lane, row["probe"], row["status"]) for lane, rows in lanes for row in rows)
    probes = sorted({probe for _, _, probe, _ in counts})
    table = []
    for probe in probes + ["total"]:
        cells = []
        for vendor, lane in columns:
            values = [sum(n for (v, l, p, s), n in counts.items() if v == vendor and l == lane
                          and s == status and probe in (p, "total")) for status in ("PASS", "FAIL", "NOT_RUN")]
            cells.append("/".join(map(str, values)) if any(values) else "-")
        table.append((probe, cells))
    short = {"H2": "H2", "MYSQL": "MySQL", "POSTGRESQL": "PG"}
    return [f"{short[vendor]} {lane}" for vendor, lane in columns], table


def grid():
    lanes = [(line[:3], functional(line)) for line in LINES]
    lanes.append(("EL5", read(HERE / "rfc0022-eclipselink-5.0.2.tsv")))
    header, table = grid_rows(lanes)
    print("PASS/FAIL/NOT_RUN per probe; EL5 is EclipseLink 5.0.2; the offline A.1/A.2 lanes are in `matrix`.")
    print("| Probe | " + " | ".join(header) + " |")
    print("|---" * (len(header) + 1) + "|")
    for probe, cells in table:
        print(f"| {probe} | " + " | ".join(cells) + " |")


def timings():
    print("B.2 medians in ms; info only, no performance threshold.")
    print("| Line | Vendor | Query | Renderer | Native |")
    print("| --- | --- | --- | ---: | ---: |")
    for line in LINES:
        rows = read(HERE / ("rfc0022-timings-" + line + ".tsv"))
        for vendor in VENDORS:
            for cell in ("CONTENT", "COUNT", "STREAM"):
                pair = {row["variant"]: row["median_ms"] for row in rows
                        if row["vendor"] == vendor and row["cell"] == cell}
                print(f"| {line} | {vendor} | {cell} | {pair['renderer']} | {pair['native']} |")
    print("\nD.1 PostgreSQL grouped-count medians in ms; info only, no performance threshold.")
    print("| Line | Anchor | count(*) | count(id) |")
    print("| --- | --- | ---: | ---: |")
    for line in LINES:
        rows = read(HERE / ("joined-count-timings-" + line + ".tsv"))
        for anchor in ("column", "source"):
            pair = {row["form"]: row["median_ms"] for row in rows if row["anchor"] == anchor}
            print(f"| {line} | {anchor} | {pair['star']} | {pair['id']} |")


def normalized(text, alias=False):
    """Whitespace collapsed; with alias, the joined source's alias (cte01_0 on 6.6, var_1_11_0 on 7.4) too."""
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"\b(cte\d+_0|var_\d+_\d+_0)\b", "<source>", text) if alias else text


def parity_data(left, right):
    a, b = unique(left), unique(right)
    common = a.keys() & b.keys()
    differences = {field: sorted(k for k in common if normalized(a[k][field]) != normalized(b[k][field]))
                   for field in ("status", "value", "sql")}
    differences["sql, source alias normalized"] = sorted(
        k for k in common if normalized(a[k]["sql"], True) != normalized(b[k]["sql"], True))
    return len(common), sorted(a.keys() - b.keys()), sorted(b.keys() - a.keys()), differences


def parity():
    common, only_a, only_b, differences = parity_data(*(functional(line) for line in LINES))
    print(f"Common keys: {common}; 6.6-only: {len(only_a)}; 7.4-only: {len(only_b)}. Counts are info only.")
    for field, keys in differences.items():
        print(f"{field} differs: {len(keys)} (info only).")
        for k in keys:
            print("  " + " / ".join(k))


def fails():
    lanes = [(line, functional(line)) for line in LINES]
    lanes.append(("EclipseLink 5.0.2", read(HERE / "rfc0022-eclipselink-5.0.2.tsv")))
    for lane, rows in lanes:
        for row in rows:
            if row["status"] != "PASS":
                print(" | ".join((lane, *key(row), row["status"], row["value"])))


def validate():
    for line in LINES:
        rows = functional(line)
        for vendor in VENDORS:
            for probe, expected in (("C.1", 18), ("C.2", 28), ("D.1", 60), ("B.2", 3)):
                actual = sum(row["vendor"] == vendor and row["probe"] == probe for row in rows)
                if actual != expected:
                    raise ValueError(f"{line} {vendor} {probe}: {actual} cells; expected {expected}")
        timings_rows = read(HERE / ("rfc0022-timings-" + line + ".tsv"))
        expected = {(v, c, form) for v in VENDORS for c in ("CONTENT", "COUNT", "STREAM")
                    for form in ("renderer", "native")}
        actual = {(row["vendor"], row["cell"], row["variant"]) for row in timings_rows}
        if actual != expected or len(timings_rows) != len(expected):
            raise ValueError("missing or duplicate B.2 timings: " + line)
        for vendor in VENDORS:
            plan = (HERE / "plans" / line / (vendor.lower() + ".txt")).read_text()
            if plan.count("## ") != 6:
                raise ValueError("expected six plans: " + line + " " + vendor)
        d = read(HERE / ("joined-count-timings-" + line + ".tsv"))
        if len(d) != 4 or {(r["anchor"], r["form"]) for r in d} != {
                (anchor, form) for anchor in ("source", "column") for form in ("star", "id")}:
            raise ValueError("missing or duplicate D.1 timings: " + line)
    unique(read(HERE / "rfc0022-eclipselink-5.0.2.tsv"))
    print("PASS: complete C.1/C.2/D.1 matrix, unique keys, saved B.2 measurements and all plans/timings")


if __name__ == "__main__":
    commands = {"matrix": matrix, "grid": grid, "timings": timings, "parity": parity, "fails": fails,
                "validate": validate}
    for command in sys.argv[1:] or ("grid", "matrix", "timings", "parity"):
        if command not in commands:
            raise SystemExit("usage: summarize.py [matrix|grid|timings|parity|fails|validate]")
        print("\n## " + command + "\n")
        commands[command]()
