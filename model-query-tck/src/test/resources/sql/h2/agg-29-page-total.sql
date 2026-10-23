select count(*) from orders oe1_0 where oe1_0.status=?
select count(*) from orders oe1_0 join customers c1_0 on c1_0.id=oe1_0.customer_id where c1_0.country=?
