select cr1_0.id from containment_rows cr1_0 where (cr1_0.tags @> ?) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.tags @> ?) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.tags @> ?) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.tags && ?) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.nums && ?) order by 1
select cr1_0.id from containment_rows cr1_0 where not((cr1_0.tags && ?)) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.doc @> cast(? as jsonb)) order by 1
select cr1_0.id from containment_rows cr1_0 where (cr1_0.doc @> cast(? as jsonb)) order by 1
