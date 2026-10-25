select oe1_0.status,coalesce(count(case when oe1_0.total>? then 1 else null end),?) from orders oe1_0 group by 1 having coalesce(count(case when oe1_0.total>? then 1 else null end),?)>? order by 1
