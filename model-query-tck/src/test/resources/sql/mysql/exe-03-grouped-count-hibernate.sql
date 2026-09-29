select count(*) from (select c1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1) derived1_0(c0)
select count(*) from (select c1_0.id from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by 1 having sum(oe1_0.total)>?) derived1_0(c0)
