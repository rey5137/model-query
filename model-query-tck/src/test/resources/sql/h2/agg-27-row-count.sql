select count(*) from orders oe1_0
select count(*) from orders oe1_0
select count(*) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id
select oe1_0.status c0,count(*) c1,count(*) c2 from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by c0 order by 1
