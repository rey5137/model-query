#!/bin/sh
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_root=$(CDPATH= cd -- "$task_dir/../.." && pwd)
scratch="$task_root/spike-rfc0022/target/link-probe"
for lane in hibernate-7.4-api3.2 eclipselink-5.0.2-api3.2; do
    probe_cp=$(cat "$scratch/$lane.cp")
    case "$lane" in
        hibernate*) implementation=org.hibernate.query.sqm.tree.domain.AbstractSqmFrom ;;
        *) implementation=org.eclipse.persistence.internal.jpa.querydef.FromImpl ;;
    esac
    {
        echo "# API 3.2 and $lane; javap -public -s"
        for type in jakarta.persistence.criteria.From jakarta.persistence.criteria.Join "$implementation"; do
            echo "## $type"
            javap -cp "$probe_cp" -public -s "$type" |
                grep -A1 -E 'interface |class |join\(java.lang.Class| on\(| getOn\(' | grep -v '^--'
        done
        echo '## PortableRenderer: direct invokeinterface descriptors'
        javap -cp "$scratch/classes" -c PortableRenderer
    } > "$task_dir/javap-$lane.txt"
    echo "Recorded javap-$lane.txt"
done
