select oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.country=? order by 1
select oe1_0.id from orders oe1_0 order by 1
select oe1_0.id,c1_0.country from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.country=? order by 1
