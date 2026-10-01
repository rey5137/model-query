#!/usr/bin/env python3
"""AC-REL-05: build the published modules twice and compare the SHA-256 of every artifact.

Runs `./mvnw -Prelease -Dgpg.skip -DskipTests clean install` twice, each into its own empty local repository
(the user's repository is attached read-only as a tail so dependencies are not downloaded again), then compares
every .jar and .pom installed under the project's group id. The TCK and the samples are not published, so they
are left out. Exits 1 on any difference or if the two builds installed different sets of files.
"""
import hashlib
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GROUP_DIR = Path("io/github/rey5137")
EXCLUDED_MODULES = "!model-query-tck,!samples/plain-jpa,!samples/spring-boot"
PUBLISHED_SUFFIXES = (".jar", ".pom")


def build(repo: Path, tail: Path) -> None:
    cmd = [
        str(ROOT / "mvnw"), "-B", "-ntp", "-Prelease", "-Dgpg.skip", "-DskipTests",
        f"-Dmaven.repo.local={repo}", f"-Dmaven.repo.local.tail={tail}",
        "-pl", EXCLUDED_MODULES, "clean", "install",
    ]
    print("+ " + " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True)


def checksums(repo: Path) -> dict:
    base = repo / GROUP_DIR
    return {
        str(p.relative_to(base)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(base.rglob("*")) if p.is_file() and p.name.endswith(PUBLISHED_SUFFIXES)
    }


def main() -> int:
    tail = Path(os.environ.get("MQ_TAIL_REPO", Path.home() / ".m2" / "repository"))
    with tempfile.TemporaryDirectory(prefix="mq-repro-") as tmp:
        repos = [Path(tmp) / "repo-1", Path(tmp) / "repo-2"]
        sums = []
        for repo in repos:
            build(repo, tail)
            sums.append(checksums(repo))
    first, second = sums
    if not first:
        print("FAIL: the first build installed no artifacts")
        return 1
    bad = [name for name in sorted(set(first) | set(second)) if first.get(name) != second.get(name)]
    for name in bad:
        print(f"MISMATCH {name}\n  build 1: {first.get(name, 'missing')}\n  build 2: {second.get(name, 'missing')}")
    if bad:
        print(f"FAIL: {len(bad)} of {len(set(first) | set(second))} artifacts differ between two builds")
        return 1
    print(f"OK: {len(first)} artifacts are identical across two builds")
    return 0


if __name__ == "__main__":
    sys.exit(main())
