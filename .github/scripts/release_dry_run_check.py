#!/usr/bin/env python3
"""AC-REL-04: check the output of a release dry run (`-Prelease -DskipPublishing=true deploy`, signing on).

Derives the module list from the reactor poms, so a new module is checked without editing this script. A module is
published unless it is the TCK or lives under samples/; those must all be named in the central-publishing plugin's
`excludeArtifacts`, and nothing in the exclusion list may be a published module. For every published module the
script asserts a signed .pom, plus a signed main, -sources and -javadoc jar when the packaging is jar, and that the BOM
lists exactly the published jar modules. Exits 1 with one line per problem. Usage: release_dry_run_check.py [root].
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TCK = "model-query-tck"


def text(node, path):
    found = node.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def project(pom: Path):
    root = ET.parse(pom).getroot()
    return root, text(root, "m:artifactId"), text(root, "m:packaging") or "jar"


def main() -> int:
    base = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    root_pom, parent, _ = project(base / "pom.xml")
    version = text(root_pom, "m:version")
    errors = []
    modules = {}  # artifactId -> (directory, packaging)
    for mod in root_pom.findall("m:modules/m:module", NS):
        _, artifact, packaging = project(base / mod.text / "pom.xml")
        modules[artifact] = (base / mod.text, packaging, mod.text)
    modules[parent] = (base, "pom", ".")

    excluded = {a.text.strip() for a in root_pom.findall(
        ".//m:plugin[m:artifactId='central-publishing-maven-plugin']//m:excludeArtifacts/m:artifact", NS)}
    unpublished = {a for a, (_, _, rel) in modules.items() if a == TCK or rel.startswith("samples/")}
    published = {a: v for a, v in modules.items() if a not in unpublished}
    for missing in sorted(unpublished - excluded):
        errors.append(f"{missing} is not published but is missing from excludeArtifacts")
    for wrong in sorted(excluded - unpublished):
        errors.append(f"excludeArtifacts names {wrong}, which is a published module or unknown")

    for artifact, (directory, packaging, _) in sorted(published.items()):
        target = directory / "target"
        names = [f"{artifact}-{version}.pom"]
        if packaging != "pom":
            names += [f"{artifact}-{version}{s}.jar" for s in ("", "-sources", "-javadoc")]
        for name in names:
            for candidate in (name, name + ".asc"):
                if not (target / candidate).is_file():
                    errors.append(f"{artifact}: missing {target.relative_to(base) / candidate}")

    bom_root, _, _ = project(base / "model-query-bom" / "pom.xml")
    managed = bom_root.findall(".//m:dependencyManagement/m:dependencies/m:dependency", NS)
    listed = [text(d, "m:artifactId") for d in managed]
    expected = {a for a, (_, p, _) in published.items() if p != "pom"}
    for a in sorted(expected - set(listed)):
        errors.append(f"BOM does not list published module {a}")
    for a in sorted(set(listed) - expected):
        errors.append(f"BOM lists {a}, which is not a published jar module")
    if len(listed) != len(set(listed)):
        errors.append("BOM lists an artifact more than once")

    for line in errors:
        print("FAIL: " + line)
    if errors:
        return 1
    print(f"release dry run OK: {len(published)} published modules signed, BOM lists {len(expected)} jar modules, "
          f"not published: {', '.join(sorted(unpublished))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
