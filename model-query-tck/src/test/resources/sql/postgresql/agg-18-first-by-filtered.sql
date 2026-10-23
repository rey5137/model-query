select fbre1_0.grp,(array_agg(fbre1_0.v order by fbre1_0.k desc nulls last,fbre1_0.id) filter (where fbre1_0.v<>? or fbre1_0.v is null))[1] from first_by_rows fbre1_0 group by 1 order by 1
