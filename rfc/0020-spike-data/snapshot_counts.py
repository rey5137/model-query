#!/usr/bin/env python3
"""F.1: which committed SQL snapshots would change under R-AGG-19's count(*) (list only, nothing is edited).

Usage: python3 -I rfc/0020-spike-data/snapshot_counts.py [model-query-tck/src/test/resources/sql]

Classifies every non-distinct `count(<alias>.id)` and every `count(1)` in a snapshot by how the statement introduces
the alias: `from <table> <alias>` (a root, or a sub-select's root) or `join <table> <alias>` (inner) would render
`count(*)`; `left join <table> <alias>` keeps `count(<alias>.id)`. The inner-join chain is not walked to its root and
customizers are not visible in a snapshot, so the "would change" list is an upper bound.
"""
import re
import sys
from collections import Counter
from pathlib import Path

COUNT_ID = re.compile(r"count\((?!distinct)([a-z][a-z0-9]*_[0-9]+)\.id\)", re.I)
COUNT_ONE = re.compile(r"count\(1\)", re.I)


def kind_of(sql, alias):
    m = re.search(r"(left\s+join|right\s+join|full\s+join|join|from|,)\s+[a-z_][a-z0-9_]*\s+" + re.escape(alias)
                  + r"\b", sql, re.I)
    if not m:
        # A table-per-class root renders as `from (select ... union all ...) <alias>`.
        return "root" if re.search(r"from\s*\(.*\)\s+" + re.escape(alias) + r"\b", sql, re.I) else "unknown"
    word = m.group(1).lower().split()[0]
    return {"left": "LEFT", "right": "RIGHT", "full": "FULL", "join": "INNER", "from": "root", ",": "root"}[word]


def main():
    base = Path(sys.argv[1] if len(sys.argv) > 1 else "model-query-tck/src/test/resources/sql")
    change, unchanged, ones = [], [], []
    totals = Counter()
    for path in sorted(base.rglob("*.sql")):
        kinds = Counter()
        n_one = 0
        for sql in path.read_text().splitlines():  # one statement per line
            kinds.update(kind_of(sql, a) for a in COUNT_ID.findall(sql))
            n_one += len(COUNT_ONE.findall(sql))
        rel = path.relative_to(base).as_posix()
        totals.update(kinds)
        if kinds["root"] or kinds["INNER"]:
            change.append((rel, kinds))
        elif kinds:
            unchanged.append((rel, kinds))
        if n_one:
            ones.append((rel, n_one))
    print(f"# count(<alias>.id) occurrences by alias kind: {dict(totals)}")
    print(f"\n## Would change: root or inner-joined id count ({len(change)} files)")
    for rel, kinds in change:
        print(f"- {rel}  {dict(kinds)}")
    print(f"\n## Unchanged: only LEFT/other-joined id counts ({len(unchanged)} files)")
    for rel, kinds in unchanged:
        print(f"- {rel}  {dict(kinds)}")
    print(f"\n## count(1) (source roots; change only if routed through the R-AGG-19 helper) ({len(ones)} files)")
    for rel, n in ones:
        print(f"- {rel}  x{n}")


if __name__ == "__main__":
    main()
