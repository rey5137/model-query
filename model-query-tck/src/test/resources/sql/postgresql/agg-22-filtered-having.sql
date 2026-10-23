select oe1_0.status,count(oe1_0.id) filter (where oe1_0.total>cast(? as numeric(38,2))) from orders oe1_0 group by 1 having count(oe1_0.id) filter (where oe1_0.total>?)>? order by 1
