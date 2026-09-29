select oe1_0.status,c1_0.name,oe1_0.total,oe1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id order by 4
select oe1_0.status,c1_0.name,oe1_0.total from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id order by oe1_0.id
select oe1_0.id from orders oe1_0 order by 1
