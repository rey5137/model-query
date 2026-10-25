select oe1_0.status c0,coalesce(count(1) filter (where oe1_0.total>?),?) c1 from orders oe1_0 group by c0 having coalesce(count(1) filter (where oe1_0.total>?),?)>? order by 1
