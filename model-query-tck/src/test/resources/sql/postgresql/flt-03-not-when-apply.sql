select oe1_0.id from orders oe1_0 order by 1
select oe1_0.id from orders oe1_0 where not(oe1_0.status=? and oe1_0.total>?) order by 1
select oe1_0.id from orders oe1_0 order by 1
select oe1_0.id from orders oe1_0 where oe1_0.status=? order by 1
select oe1_0.id from orders oe1_0 where oe1_0.status<>? or oe1_0.status is null order by 1
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 where nse1_0.sort_int<>? order by 1
select nse1_0.id,nse1_0.sort_int from nullable_sort_rows nse1_0 where nse1_0.sort_int<>? or nse1_0.sort_int is null order by 1
