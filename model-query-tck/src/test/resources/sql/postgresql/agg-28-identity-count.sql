select count(r1_0.id) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
select count(r1_0.id) from orders oe1_0 left join customers r1_0 on r1_0.id=oe1_0.referrer_id
select count(oe1_0.referrer_id) from orders oe1_0
select count(distinct oe1_0.id) from orders oe1_0
select count(oe1_0.id) from orders oe1_0
select count(distinct oe1_0.id) from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.quantity>?
select count(oe1_0.id) from orders oe1_0 where oe1_0.status=?
