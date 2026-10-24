#!/bin/sh
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_root=$(CDPATH= cd -- "$task_dir/../.." && pwd)
scratch="$task_root/spike-rfc0022/target/link-probe"
mkdir -p "$scratch/classes"

resolve() {
    "$task_root/mvnw" -q -f "$task_dir/probe-pom.xml" -P"$2" \
        -Dprobe.provider="$3" -Dprobe.api="$4" \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile="$scratch/$1.cp"
}
resolve hibernate-6.6-api3.1 hibernate 6.6.58.Final 3.1.0
resolve hibernate-6.6-api3.2 hibernate 6.6.58.Final 3.2.0
resolve hibernate-7.4-api3.2 hibernate 7.4.11.Final 3.2.0
resolve eclipselink-4.0.8-api3.2 eclipselink 4.0.8 3.2.0
resolve eclipselink-5.0.2-api3.2 eclipselink 5.0.2 3.2.0

# Compile exactly once against the 3.2 lane, then reuse those bytes even on the 3.1 runtime.
javac --release 17 -cp "$(cat "$scratch/hibernate-7.4-api3.2.cp")" \
    -d "$scratch/classes" "$task_dir/LinkProbe.java"
for lane in hibernate-6.6-api3.1 hibernate-6.6-api3.2 hibernate-7.4-api3.2 \
        eclipselink-4.0.8-api3.2 eclipselink-5.0.2-api3.2; do
    case "$lane" in
        hibernate*) provider=org.hibernate.jpa.HibernatePersistenceProvider ;;
        *) provider=org.eclipse.persistence.jpa.PersistenceProvider ;;
    esac
    python3 - "$scratch" "$task_dir" "$lane" "$provider" <<'PY'
import pathlib
import subprocess
import sys

scratch, output, lane, provider = sys.argv[1:]
classpath = str(pathlib.Path(scratch, "classes")) + ":" + pathlib.Path(scratch, lane + ".cp").read_text().strip()
with pathlib.Path(output, "link-probe-" + lane + ".txt").open("w") as log:
    subprocess.run(["java", "-Xverify:all", "-cp", classpath, "LinkProbe", provider, lane,
                    str(pathlib.Path(output, "link-probe-" + lane + ".tsv"))],
                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
print("Recorded " + lane)
PY
done
