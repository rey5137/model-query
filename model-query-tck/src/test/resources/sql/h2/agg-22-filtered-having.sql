select oe1_0.status c0,count(1) filter (where oe1_0.total>cast(? as numeric(38,2))) c1 from orders oe1_0 group by c0 having count(1) filter (where oe1_0.total>?)>? order by 1
