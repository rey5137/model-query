select count(1) filter (where r1_0.country=cast(? as varchar)) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
select sum(oe1_0.total) filter (where r1_0.country=cast(? as varchar)) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
