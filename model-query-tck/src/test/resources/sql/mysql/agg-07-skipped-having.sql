select c1_0.id,sum(oe1_0.total) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 order by 1
select c1_0.id,sum(oe1_0.total) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 order by 1
select c1_0.id,sum(oe1_0.total) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 having sum(oe1_0.total)>? order by 1
select c1_0.id,sum(oe1_0.total) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 having sum(oe1_0.total)>? order by 1
select c1_0.id,sum(oe1_0.total) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 order by 1
