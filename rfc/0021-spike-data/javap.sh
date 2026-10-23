#!/bin/sh
# RFC 0021 spike A.1: the signatures the proposed EntityJoinRenderer depends on, per Hibernate line.
M2=${M2:-$HOME/.m2/repository}
for v in 6.6.58.Final 7.4.11.Final; do
  case $v in 6.6*) J=3.1.0 ;; *) J=3.2.0 ;; esac
  CP=$M2/org/hibernate/orm/hibernate-core/$v/hibernate-core-$v.jar:$M2/jakarta/persistence/jakarta.persistence-api/$J/jakarta.persistence-api-$J.jar
  {
    echo "# javap -public -s, hibernate-core $v, jakarta.persistence-api $J"
    for c in JpaFrom JpaEntityJoin JpaJoin JpaJoinedFrom; do
      echo; echo "## org.hibernate.query.criteria.$c"
      javap -cp "$CP" -public -s org.hibernate.query.criteria.$c 2>&1 | grep -v '^Compiled from' |
        grep -A1 "interface\|join(java.lang.Class\|join(org.hibernate.metamodel.model.domain.EntityDomainType\| on(\|class not found" | grep -v '^--'
    done
  } > javap-$v.txt
done
