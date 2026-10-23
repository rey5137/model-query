select oe1_0.status,count(case when oe1_0.total>cast(? as decimal(38,2)) then 1 else null end) from orders oe1_0 group by 1 having count(case when oe1_0.total>? then 1 else null end)>? order by 1
