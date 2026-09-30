#!/usr/bin/env python3
"""AC audit (delivery/60 R-QA-05, R-QA-11, AC-QA-01). Python 3 standard library only.

Lists every AC-* id defined in the id column of an acceptance-criteria table under docs/spec, decides which are in
scope now, and fails when an in-scope one has no test whose method name starts with its snake-case id
(AC-PAG-07 -> ac_pag_07_).

Scope (see ac-scope.json):
  * a criterion tagged `Future` in its row, or whose file's status line says `Future`, belongs to the milestone in
    its `Future`, Mn tag and is exempt until that milestone is in "started";
  * otherwise it is in scope when a started milestone lists its id or its spec file;
  * a criterion no milestone lists is "unscheduled": reported, never failing;
  * AC-REL-07 (R-REL-15): every MQnnnn literal in */src/main/**/*.java must be defined in docs/spec/reference/90-*.md,
    as a table row or inside a `MQaaaa`-`MQbbbb` range;
  * AC-DIAG-05 (INV-10): the MQ3xxx codes in the processor/32 section 1 table, with no duplicate, equal the MQ3xxx
    codes reference/90 lists, and the rows not tagged `Future` equal the constants of the processor's DiagnosticCode
    enum; skipped when processor/32 does not exist;
  * a criterion in build_proven needs no named test (it is proven by the CI build itself), only a stated reason.

Usage: ac_audit.py [--root DIR] [--spec DIR] [--scope FILE]
Exit status: 0 audit passes, 1 audit fails, 2 bad input.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROW = re.compile(r"^\|\s*(AC-[A-Z]+-\d+)\s*\|(.*)$")
FUTURE = re.compile(r"`Future`(?:\s*,\s*(M\d+))?")
STATUS = re.compile(r"\*\*Status:[^*]*\*\*")
JAVA_TEST = re.compile(r"\bvoid\s+(ac_[a-z]+_\d+_\w*)\s*\(")
PY_TEST = re.compile(r"^\s*def\s+(ac_[a-z]+_\d+_\w*)\s*\(", re.M)


def snake(ac_id):
    return ac_id.lower().replace("-", "_") + "_"


def parse_spec(spec_dir):
    """Return ({id: {file, future_milestone or '', row}}, [duplicate ids])."""
    found, dups = {}, []
    for path in sorted(spec_dir.rglob("*.md")):
        rel = path.relative_to(spec_dir).with_suffix("").as_posix()
        lines = path.read_text(encoding="utf-8").splitlines()
        status_future = ""
        for line in lines[:12]:
            m = STATUS.search(line)
            if m and FUTURE.search(m.group(0)):
                status_future = FUTURE.search(m.group(0)).group(1) or "?"
        for line in lines:
            m = ROW.match(line)
            if not m:
                continue
            ac_id, rest = m.groups()
            fut = FUTURE.search(rest)
            future = (fut.group(1) or "?") if fut else status_future
            if ac_id in found:
                dups.append(ac_id)
            found[ac_id] = {"file": rel, "future": future}
    return found, dups


MQ_CODE = re.compile(r"MQ\d{4}")
MQ_RANGE = re.compile(r"`(MQ\d{4})`\s*[\u2013-]\s*`(MQ\d{4})`")
MQ_ROW = re.compile(r"^\|\s*`(MQ\d{4})`\s*\|", re.M)


def catalogued_codes(spec_dir):
    """Codes defined in reference/90: table rows plus expanded `MQaaaa`-`MQbbbb` ranges."""
    codes = set()
    for path in (spec_dir / "reference").glob("90-*.md"):
        text = path.read_text(encoding="utf-8")
        codes.update(MQ_ROW.findall(text))
        for lo, hi in MQ_RANGE.findall(text):
            codes.update("MQ%04d" % n for n in range(int(lo[2:]), int(hi[2:]) + 1))
    return codes


def uncatalogued_codes(root, spec_dir):
    """MQnnnn literals in production code that reference/90 does not define: {code: first file}."""
    known = catalogued_codes(spec_dir)
    used = {}
    for path in sorted(root.rglob("src/main/**/*.java")):
        for code in MQ_CODE.findall(path.read_text(encoding="utf-8")):
            if code not in known:
                used.setdefault(code, path.relative_to(root).as_posix())
    return used


DIAG_SPEC = Path("processor") / "32-diagnostics.md"
DIAG_ENUM = Path("model-query-processor/src/main/java/com/rey/modelquery/processor/DiagnosticCode.java")
ENUM_CONSTANT = re.compile(r"^\s*(MQ\d{4})\(", re.M)


def diagnostic_mismatches(root, spec_dir):
    """AC-DIAG-05: messages for the processor/32 section 1 codes against reference/90 and DiagnosticCode."""
    path = spec_dir / DIAG_SPEC
    if not path.is_file():
        return []
    rows = [line for line in path.read_text(encoding="utf-8").splitlines() if MQ_ROW.match(line)]
    codes = [MQ_ROW.match(line).group(1) for line in rows]
    live = {MQ_ROW.match(line).group(1) for line in rows if "`Future`" not in line}
    twice = sorted({c for c in codes if codes.count(c) > 1})
    problems = ["%s is listed twice in processor/32 section 1" % c for c in twice]
    listed = {c for c in catalogued_codes(spec_dir) if c.startswith("MQ3")}
    problems += ["%s is in processor/32 section 1 but not in reference/90" % c for c in sorted(set(codes) - listed)]
    problems += ["%s is in reference/90 but not in processor/32 section 1" % c for c in sorted(listed - set(codes))]
    enum = root / DIAG_ENUM
    if enum.is_file():
        declared = set(ENUM_CONSTANT.findall(enum.read_text(encoding="utf-8")))
        problems += ["%s is a live code in processor/32 but not a DiagnosticCode constant" % c
                     for c in sorted(live - declared)]
        problems += ["%s is a DiagnosticCode constant but not a live code in processor/32" % c
                     for c in sorted(declared - live)]
    return problems


def test_names(root):
    names = set()
    for path in root.rglob("src/test/**/*.java"):
        names.update(JAVA_TEST.findall(path.read_text(encoding="utf-8")))
    for path in (root / ".github" / "scripts").glob("*.py"):
        names.update(PY_TEST.findall(path.read_text(encoding="utf-8")))
    return names


def audit(criteria, scope, names):
    """Return (in_scope, exempt, unscheduled, build_proven, missing) as sorted lists of ids."""
    started = set(scope["started"])
    milestones = scope["milestones"]
    proven = scope.get("build_proven", {})
    owner = {}
    for ms, spec in milestones.items():
        for f in spec.get("files", []):
            for ac_id, c in criteria.items():
                if c["file"] == f:
                    owner.setdefault(ac_id, ms)
        for ac_id in spec.get("ids", []):
            owner[ac_id] = ms
    in_scope, exempt, unscheduled, built, missing = [], [], [], [], []
    for ac_id in sorted(criteria):
        c = criteria[ac_id]
        ms = owner.get(ac_id)
        if c["future"]:
            fms = c["future"] if c["future"] != "?" else ms
            if fms is None or fms not in started:
                exempt.append(ac_id)
                continue
        elif ms is None:
            unscheduled.append(ac_id)
            continue
        elif ms not in started:
            exempt.append(ac_id)
            continue
        in_scope.append(ac_id)
        if ac_id in proven:
            built.append(ac_id)
        elif not any(n.startswith(snake(ac_id)) for n in names):
            missing.append(ac_id)
    return in_scope, exempt, unscheduled, built, missing


def main(argv=None):
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(here.parent.parent))
    ap.add_argument("--spec")
    ap.add_argument("--scope", default=str(here / "ac-scope.json"))
    args = ap.parse_args(argv)
    root = Path(args.root)
    spec_dir = Path(args.spec) if args.spec else root / "docs" / "spec"
    try:
        scope = json.loads(Path(args.scope).read_text(encoding="utf-8"))
        criteria, dups = parse_spec(spec_dir)
    except (OSError, ValueError) as e:
        print("ac-audit: cannot read input: %s" % e, file=sys.stderr)
        return 2
    if not criteria:
        print("ac-audit: no AC-* rows found under %s" % spec_dir, file=sys.stderr)
        return 2
    bad = [i for i in scope.get("build_proven", {}) if i not in criteria]
    known_files = {c["file"] for c in criteria.values()}
    bad_ids, bad_files = [], []
    for ms, spec in scope["milestones"].items():
        bad_ids += [(ms, i) for i in spec.get("ids", []) if i not in criteria]
        bad_files += [(ms, f) for f in spec.get("files", []) if f not in known_files]
    names = test_names(root)
    in_scope, exempt, unscheduled, built, missing = audit(criteria, scope, names)
    print("AC audit: %d criteria defined, %d in scope, %d exempt (milestone not started or Future), "
          "%d unscheduled, %d proven by the build" % (len(criteria), len(in_scope), len(exempt),
                                                     len(unscheduled), len(built)))
    if unscheduled:
        print("unscheduled (no milestone lists them; not enforced): " + " ".join(unscheduled))
    failed = False
    for ac_id in dups:
        print("FAIL duplicate definition of %s" % ac_id)
        failed = True
    for ac_id in bad:
        print("FAIL build_proven names unknown criterion %s" % ac_id)
        failed = True
    for ms, ac_id in bad_ids:
        print("FAIL milestone %s names unknown criterion %s" % (ms, ac_id))
        failed = True
    for ms, f in bad_files:
        print("FAIL milestone %s names unknown spec file %s" % (ms, f))
        failed = True
    for code, where in sorted(uncatalogued_codes(root, spec_dir).items()):
        print("FAIL %s is raised in %s but not defined in reference/90 (AC-REL-07)" % (code, where))
        failed = True
    for problem in diagnostic_mismatches(root, spec_dir):
        print("FAIL %s (AC-DIAG-05)" % problem)
        failed = True
    for ac_id in missing:
        print("FAIL %s (%s) has no test named %s*" % (ac_id, criteria[ac_id]["file"], snake(ac_id)))
        failed = True
    print("AC audit FAILED" if failed else "AC audit passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
