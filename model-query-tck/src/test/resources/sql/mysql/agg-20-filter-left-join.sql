select count(case when r1_0.country=cast(? as char) then 1 else null end) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
select sum(case when r1_0.country=cast(? as char) then oe1_0.total else null end) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
