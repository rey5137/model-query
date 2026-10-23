select count(1) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select count(1) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select count(distinct case when oe1_0.status=? then oe1_0.id end) from orders oe1_0
select sum(oe1_0.total) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select avg(oe1_0.total) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select min(oe1_0.total) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select max(oe1_0.total) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
select sum(oe1_0.id) filter (where oe1_0.status=cast(? as varchar)) from orders oe1_0
