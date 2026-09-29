select oe1_0.id from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.product_code=? and i1_0.quantity>=? and oe1_0.id=i1_0.order_id) order by 1
select oe1_0.id from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.product_code=? and i1_0.quantity>=? and oe1_0.id=i1_0.order_id) order by 1
select oe1_0.id from orders oe1_0 where exists(select 1 from order_items i1_0 where i1_0.quantity>=? and i1_0.product_code=? and oe1_0.id=i1_0.order_id) order by 1
select oe1_0.id,i1_0.product_code from orders oe1_0 join order_items i1_0 on oe1_0.id=i1_0.order_id where i1_0.product_code=? and (exists(select 1 from order_items i2_0 where i2_0.quantity>=? and oe1_0.id=i2_0.order_id) or oe1_0.status=?) order by 1
select ce1_0.id from customers ce1_0 where exists(select 1 from orders o1_0 left join order_items i1_0 on o1_0.id=i1_0.order_id and i1_0.quantity>=? where i1_0.id is null and ce1_0.id=o1_0.customer_id) order by 1
select ce1_0.id from customers ce1_0 where exists(select 1 from orders o1_0 left join order_items i1_0 on o1_0.id=i1_0.order_id and i1_0.quantity>=? where i1_0.id is not null and ce1_0.id=o1_0.customer_id) order by 1
