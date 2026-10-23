select fbre1_0.grp,(array_agg(fbre1_0.v order by fbre1_0.k desc nulls last,fbre1_0.id))[1] from first_by_rows fbre1_0 group by 1 order by 1
select fbre1_0.grp,(array_agg(fbre1_0.v order by fbre1_0.k desc,fbre1_0.id))[1] from first_by_rows fbre1_0 group by 1 order by 1
select fbre1_0.grp,(array_agg(fbre1_0.v order by fbre1_0.k,fbre1_0.id))[1] from first_by_rows fbre1_0 group by 1 order by 1
select fbre1_0.grp,(array_agg(fbre1_0.v order by fbre1_0.k asc nulls first,fbre1_0.id))[1] from first_by_rows fbre1_0 group by 1 order by 1
