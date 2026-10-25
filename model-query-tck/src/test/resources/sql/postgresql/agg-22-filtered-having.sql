select oe1_0.status,coalesce(count(1) filter (where oe1_0.total>?),?) from orders oe1_0 group by 1 having coalesce(count(1) filter (where oe1_0.total>?),?)>? order by 1
