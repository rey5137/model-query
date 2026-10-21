#!/usr/bin/env python3
"""Rebuild RFC 0018 matrices from preserved observations; never run a database."""

import csv
import gzip
import itertools
import json
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path

# The TCK records large chunked-key statements in one SQL field.
csv.field_size_limit(16 * 1024 * 1024)

LINES = ("6.6.58.Final", "7.4.11.Final")
VENDORS = ("H2", "POSTGRESQL", "MYSQL")
STATUSES = ("PASS", "FAIL", "SIMULATED", "NOT_RUN", "SKIP")


def read_rows(path):
    path = Path(path)
    compressed = path.with_suffix(path.suffix + ".gz")
    if path.exists() and compressed.exists():
        raise ValueError(f"Ambiguous raw inputs: {path.name} and {compressed.name}")
    if path.exists():
        stream = path.open(encoding="utf-8", newline="")
    else:
        stream = gzip.open(compressed, "rt", encoding="utf-8", newline="")
    with stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    if not rows or any(row["status"] not in STATUSES for row in rows):
        raise ValueError(f"Empty input or unknown status: {path.name}")
    return rows


def write_rows(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def matrix_rows(sources, part=False):
    groups = defaultdict(Counter)
    for source, rows in sources.items():
        for row in rows:
            probe = row["probe"].split(".")[0] if part else row["probe"]
            groups[(source, probe, row["vendor"], row["hibernate"])][row["status"]] += 1
    return [dict(source=source, probe=probe, vendor=vendor, hibernate=line,
                 **{status: counts[status] for status in STATUSES})
            for (source, probe, vendor, line), counts in sorted(groups.items())]


def timing_rows(rows):
    groups = defaultdict(list)
    for row in rows:
        if row["status"] != "PASS" or int(row["nanos"]) <= 0:
            raise ValueError("Timing input contains a failed/nonpositive measurement")
        groups[tuple(row[key] for key in ("vendor", "hibernate", "shape", "filter", "page"))].append(row)
    output = []
    for (vendor, line, shape, filter_name, page), group in sorted(groups.items()):
        medians = {}
        minima = {}
        maxima = {}
        for route in ("INLINE_COUNT", "COUNT"):
            route_rows = [row for row in group if row["route"] == route]
            phases = {phase: [row for row in route_rows if row["phase"] == phase]
                      for phase in ("warmup", "timed")}
            if (len(route_rows) != 7 or sorted(row["run"] for row in phases["warmup"]) != ["1", "2"]
                    or sorted(row["run"] for row in phases["timed"]) != ["1", "2", "3", "4", "5"]):
                raise ValueError(f"Incomplete/duplicate timing runs: {vendor}/{line}/{shape}/{filter_name}/{page}")
            measured = [int(row["nanos"]) for row in phases["timed"]]
            medians[route] = statistics.median(measured)
            minima[route] = min(measured)
            maxima[route] = max(measured)
        if len({(row["total"], row["ids"]) for row in group}) != 1:
            raise ValueError("Timing routes/runs disagree on row ids or total")
        output.append(dict(vendor=vendor, hibernate=line, shape=shape, filter=filter_name, page=page,
                           measured_runs_per_route=5, excluded_warmups_per_route=2,
                           inline_min_ns=minima["INLINE_COUNT"], inline_median_ns=medians["INLINE_COUNT"],
                           inline_max_ns=maxima["INLINE_COUNT"],
                           count_min_ns=minima["COUNT"], count_median_ns=medians["COUNT"],
                           count_max_ns=maxima["COUNT"],
                           inline_min_ms=f'{minima["INLINE_COUNT"] / 1_000_000:.6f}',
                           inline_median_ms=f'{medians["INLINE_COUNT"] / 1_000_000:.6f}',
                           inline_max_ms=f'{maxima["INLINE_COUNT"] / 1_000_000:.6f}',
                           count_min_ms=f'{minima["COUNT"] / 1_000_000:.6f}',
                           count_median_ms=f'{medians["COUNT"] / 1_000_000:.6f}',
                           count_max_ms=f'{maxima["COUNT"] / 1_000_000:.6f}',
                           inline_over_count=f'{medians["INLINE_COUNT"] / medians["COUNT"]:.6f}',
                           inline_faster=str(medians["INLINE_COUNT"] < medians["COUNT"]).lower(),
                           total=group[0]["total"]))
    return output


def plan_details(row):
    if row["vendor"] == "POSTGRESQL":
        plan = json.loads(row["plan"])[0]
        nodes = []

        def visit(node):
            nodes.append("/".join(str(node[key]) for key in ("Node Type", "Join Type", "Strategy") if key in node))
            for child in node.get("Plans", []):
                visit(child)

        visit(plan["Plan"])
        return " > ".join(nodes), float(plan["Execution Time"])
    tree = row["plan"].split("EXPLAIN ANALYZE:", 1)[1].strip()
    elapsed = re.search(r"actual time=[0-9.]+\.\.([0-9.]+)", tree)
    if elapsed is None:
        raise ValueError("MySQL executed plan lacks root elapsed time")
    return tree.split("  (cost=", 1)[0].removeprefix("-> "), float(elapsed.group(1))


def plan_rows(rows):
    output = []
    elapsed = {}
    for row in rows:
        shape, route = row["case"].split("/")
        if row["status"] != "PASS" or int(row["unique_ids"]) != 10_000:
            raise ValueError("Plan probe did not validate the expected unique root set")
        if int(row["rows"]) != (20_000 if route == "join" else 10_000):
            raise ValueError("Plan probe has unexpected multiplicity")
        strategy, milliseconds = plan_details(row)
        elapsed[(row["vendor"], row["hibernate"], shape, route)] = milliseconds
        output.append(dict(vendor=row["vendor"], hibernate=row["hibernate"], shape=shape, route=route,
                           rows=row["rows"], unique_ids=row["unique_ids"], strategy=strategy,
                           explain_elapsed_ms=f"{milliseconds:.3f}"))
    for row in output:
        key = (row["vendor"], row["hibernate"], row["shape"])
        ms = float(row["explain_elapsed_ms"])
        row["over_join"] = f'{ms / elapsed[(*key, "join")]:.6f}'
        row["over_distinct"] = f'{ms / elapsed[(*key, "distinct")]:.6f}'
    return sorted(output, key=lambda row: (row["vendor"], row["hibernate"], row["shape"], row["route"]))


def index_rows(rows, keys):
    indexed = {}
    for row in rows:
        key = tuple(row[field] for field in keys)
        if key in indexed:
            raise ValueError(f"Duplicate cell: {key}")
        indexed[key] = row
    return indexed


def parity_rows(source, left, right, keys, observations):
    a, b = index_rows(left, keys), index_rows(right, keys)
    output = []
    for key in sorted(a.keys() | b.keys()):
        one, two = a.get(key), b.get(key)
        cell = one or two
        both = one is not None and two is not None
        sql_equal = str(one["sql"] == two["sql"]).lower() if both and "sql" in cell else "n/a"
        plan_equal = (str(plan_details(one)[0] == plan_details(two)[0]).lower()
                      if both and source == "plans" else "n/a")
        output.append(dict(source=source, vendor=cell.get("vendor", "ALL"), probe=cell.get("probe", source),
                           case="/".join(cell[field] for field in keys if field not in ("vendor", "probe")),
                           status_6_6=one["status"] if one else "MISSING",
                           status_7_4=two["status"] if two else "MISSING",
                           status_equal=str(both and one["status"] == two["status"]).lower(),
                           observation_equal=str(both and all(one[field] == two[field]
                                                             for field in observations)).lower(),
                           sql_equal=sql_equal, plan_shape_equal=plan_equal))
    return output


def main(directory=None):
    directory = Path(directory) if directory is not None else Path(__file__).resolve().parent
    families = {}
    for family, prefix in (("functional", "rfc0018"), ("performance", "performance"),
                           ("plans", "plans"), ("tck-tests", "tck-test-results")):
        families[family] = [read_rows(directory / f"{prefix}-{line}.tsv") for line in LINES]
    selected = read_rows(directory / "value-path-observations.tsv")
    families["value-paths"] = [[row for row in selected if row["hibernate"] == line] for line in LINES]
    sources = {name: list(itertools.chain.from_iterable(families[name]))
               for name in ("functional", "performance", "plans")}
    timings = timing_rows(sources["performance"])
    expected = set(itertools.product(VENDORS[1:], LINES, ("flat", "grouped"),
                                     ("selective", "unselective"), ("first", "middle", "deep")))
    if {tuple(row[key] for key in ("vendor", "hibernate", "shape", "filter", "page"))
            for row in timings} != expected:
        raise ValueError("Missing performance scenario")
    plans = plan_rows(sources["plans"])
    if len(plans) != 24:
        raise ValueError("Expected six plan cells per database and line")
    parity = []
    for source, keys, observations in (
            ("functional", ("vendor", "probe", "case"), ("value",)),
            ("performance", ("vendor", "probe", "case", "phase", "run"), ("total", "ids")),
            ("plans", ("vendor", "probe", "case"), ("rows", "unique_ids")),
            ("tck-tests", ("class", "test"), ("failure",)),
            ("value-paths", ("vendor", "path"), ("test",))):
        parity.extend(parity_rows(source, *families[source], keys, observations))
    tck = []
    for line, tests in zip(LINES, families["tck-tests"]):
        summary = json.loads((directory / f"tck-audit-summary-{line}.json").read_text(encoding="utf-8"))
        test_counts = Counter(row["status"] for row in tests)
        dump = directory / f"tck-value-audit-{line}.tsv"
        if dump.exists() or dump.with_name(dump.name + ".gz").exists():
            sql_counts = Counter(row["status"] for row in read_rows(dump) if row["kind"] == "SQL")
        else:  # the raw dumps (4.6 MB each) are kept outside the repo; trust the summary's SQL totals
            sql_counts = Counter(summary["engine_sql"])
        if dict(test_counts) != summary["outcomes"] or dict(sql_counts) != summary["engine_sql"]:
            raise ValueError(f"TCK summary disagrees with raw evidence: {line}")
        for scope, counts in (("tests", test_counts), ("SQL", sql_counts)):
            tck.append(dict(scope=scope, hibernate=line, **{status: counts[status] for status in STATUSES}))
    paths = []
    other_paths = index_rows(families["value-paths"][1], ("path", "vendor"))
    for key, row in sorted(index_rows(families["value-paths"][0], ("path", "vendor")).items()):
        other = other_paths[key]
        paths.append(dict(path=key[0], vendor=key[1], status_6_6=row["status"], status_7_4=other["status"],
                          audit_row_6_6=row["audit_row"], audit_row_7_4=other["audit_row"]))
    outputs = {"probe-matrix.tsv": matrix_rows(sources),
               "part-matrix.tsv": matrix_rows({"functional": sources["functional"]}, part=True),
               "performance-medians.tsv": timings, "plan-summary.tsv": plans,
               "tck-matrix.tsv": tck, "value-path-matrix.tsv": paths, "line-parity.tsv": parity}
    for name, rows in outputs.items():
        write_rows(directory / name, rows)
    matched = sum(row["status_equal"] == "true" for row in parity)
    print(f"Wrote {len(outputs)} summaries; {len(timings)} timing comparisons; "
          f"{matched}/{len(parity)} line-parity statuses match.")


if __name__ == "__main__":
    main()
