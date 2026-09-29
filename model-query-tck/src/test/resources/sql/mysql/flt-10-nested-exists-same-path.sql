select ce1_0.id from customers ce1_0 where exists(select 1 from orders o1_0 where o1_0.total>? and exists(select 1 where o1_0.total<?) and ce1_0.id=o1_0.customer_id) order by 1
