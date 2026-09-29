select oe1_0.id,c1_0.name from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where oe1_0.total>? and c1_0.country=? and c1_0.name<? order by 1
