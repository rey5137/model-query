select fbre1_0.grp c0,array_agg(fbre1_0.v order by fbre1_0.k desc,fbre1_0.id)[1] c1 from first_by_rows fbre1_0 group by c0 order by 1
select fbre1_0.grp c0,array_agg(fbre1_0.v order by fbre1_0.k desc nulls first,fbre1_0.id)[1] c1 from first_by_rows fbre1_0 group by c0 order by 1
select fbre1_0.grp c0,array_agg(fbre1_0.v order by fbre1_0.k asc nulls last,fbre1_0.id)[1] c1 from first_by_rows fbre1_0 group by c0 order by 1
select fbre1_0.grp c0,array_agg(fbre1_0.v order by fbre1_0.k,fbre1_0.id)[1] c1 from first_by_rows fbre1_0 group by c0 order by 1
