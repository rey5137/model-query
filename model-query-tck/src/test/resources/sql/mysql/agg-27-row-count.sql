select count(*) from orders oe1_0
select count(*) from orders oe1_0
select count(*) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id
select oe1_0.status,count(*),count(*) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 order by 1
