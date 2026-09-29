select c1_0.id c0 from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by c0
select c1_0.id c0 from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id group by c0 having sum(oe1_0.total)>?
