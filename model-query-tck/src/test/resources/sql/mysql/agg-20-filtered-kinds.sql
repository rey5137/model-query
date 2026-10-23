select count(case when oe1_0.status=cast(? as char) then 1 else null end) from orders oe1_0
select count(case when oe1_0.status=cast(? as char) then 1 else null end) from orders oe1_0
select count(distinct case when oe1_0.status=? then oe1_0.id end) from orders oe1_0
select sum(case when oe1_0.status=cast(? as char) then oe1_0.total else null end) from orders oe1_0
select avg(case when oe1_0.status=cast(? as char) then oe1_0.total else null end) from orders oe1_0
select min(case when oe1_0.status=cast(? as char) then oe1_0.total else null end) from orders oe1_0
select max(case when oe1_0.status=cast(? as char) then oe1_0.total else null end) from orders oe1_0
select sum(case when oe1_0.status=cast(? as char) then oe1_0.id else null end) from orders oe1_0
